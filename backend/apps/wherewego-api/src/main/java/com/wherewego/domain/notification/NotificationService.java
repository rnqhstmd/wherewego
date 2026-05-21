package com.wherewego.domain.notification;

import com.wherewego.domain.group.GroupMemberRepository;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinRepository;
import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 8 알림 서비스. {@link NotificationRepository}, {@link GroupMemberRepository},
 * {@link PinRepository}, {@link UserRepository}를 조합하여 알림 fan-out / 조회 / 읽음 처리를 담당한다.
 *
 * <p>쓰기 메서드({@link #createForManualPin}, {@link #createForChatbotBatch})는 REQUIRED(기본)
 * 트랜잭션으로 자체 커밋한다. 커밋 후 {@code NotificationSsePushListener}가
 * AFTER_COMMIT 단계에서 SSE push를 수행한다.</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int RECENT_LIMIT_HARD_CAP = 50;
    private static final String FALLBACK_NICKNAME = "멤버";
    private static final String FALLBACK_PLACE_NAME = "저장된 장소";
    private static final String DELETED_PLACE_NAME = "삭제된 장소";

    private final NotificationRepository repository;
    private final GroupMemberRepository groupMemberRepository;
    private final PinRepository pinRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 웹/모바일 직접 등록 트리거 (PinV1Controller.createPin에서 트랜잭션 밖에서 호출).
     * 호출자에 트랜잭션이 없으므로 REQUIRED(기본)로 자체 트랜잭션을 시작·커밋한다.
     * AFTER_COMMIT 리스너가 SSE push.
     * BR-3: 호출자는 try-catch로 본 메서드의 실패를 격리한다.
     */
    @Transactional
    public void createForManualPin(Long groupId, Long registeredBy, Long pinId) {
        fanOutAndPublish(groupId, registeredBy, List.of(pinId), NotificationType.MANUAL_PIN);
    }

    /**
     * 챗봇 핀 저장 배치 트리거 (InstagramLinkHandler, PlaceSelectionHandler).
     * pinIds가 비어 있으면 no-op (FR-4 / BR-5).
     */
    @Transactional
    public void createForChatbotBatch(Long groupId, Long registeredBy, List<Long> pinIds) {
        if (pinIds == null || pinIds.isEmpty()) return;
        fanOutAndPublish(groupId, registeredBy, pinIds, NotificationType.CHATBOT_PINS);
    }

    /**
     * 공통 fan-out 로직. 활성 멤버 중 등록자 본인을 제외한 수신자별로
     * {@link Notification} + {@link NotificationPin} 링크를 저장하고
     * {@link NotificationCreatedEvent}를 발행한다.
     */
    private void fanOutAndPublish(Long groupId, Long registeredBy, List<Long> pinIds, NotificationType type) {
        List<Long> receiverIds = groupMemberRepository.findOtherActiveMemberIds(groupId, registeredBy);
        if (receiverIds.isEmpty()) return;  // 엣지 7: 다른 활성 멤버 없음

        String registeredByNickname = userRepository.findById(registeredBy)
                .map(UserModel::getNickname)
                .orElse(FALLBACK_NICKNAME);
        String firstPlaceName = pinRepository.findById(pinIds.get(0))
                .map(Pin::getPlaceName)
                .orElse(FALLBACK_PLACE_NAME);

        for (Long receiverId : receiverIds) {
            Notification n = repository.save(
                    Notification.create(groupId, receiverId, registeredBy, type));
            List<NotificationPin> links = new ArrayList<>(pinIds.size());
            for (int i = 0; i < pinIds.size(); i++) {
                links.add(NotificationPin.link(n.getId(), pinIds.get(i), i));
            }
            repository.saveAllPins(links);
            eventPublisher.publishEvent(new NotificationCreatedEvent(
                    receiverId,
                    n.getId(),
                    n.getType(),
                    registeredBy,
                    registeredByNickname,
                    firstPlaceName,
                    pinIds.size(),
                    toInstant(n.getCreatedAt())
            ));
        }
    }

    /**
     * 수신자의 최근 알림 목록. 정렬은 repository 책임 (created_at DESC).
     * limit는 {@link #RECENT_LIMIT_HARD_CAP} 으로 상한.
     */
    @Transactional(readOnly = true)
    public NotificationListResult listRecent(Long receiverId, int limit) {
        int cap = Math.min(limit, RECENT_LIMIT_HARD_CAP);
        List<Notification> notifications = repository.findRecentByReceiverId(receiverId, cap);

        List<Long> notificationIds = notifications.stream().map(Notification::getId).toList();
        Map<Long, List<NotificationPin>> pinsMap = repository.findPinsByNotificationIds(notificationIds);

        // 첫 핀 메타(placeName)만 필요 → 첫 pinId만 모아 batch 조회
        Set<Long> firstPinIds = pinsMap.values().stream()
                .filter(list -> !list.isEmpty())
                .map(list -> list.get(0).getPinId())
                .collect(Collectors.toSet());
        Map<Long, Pin> pinById = loadPinsByIds(firstPinIds);

        // 등록자 닉네임 batch 조회 (UserRepository.findNicknamesByIds 활용)
        Set<Long> registeredByIds = notifications.stream()
                .map(Notification::getRegisteredBy)
                .collect(Collectors.toSet());
        Map<Long, String> nicknameById = registeredByIds.isEmpty()
                ? Map.of()
                : userRepository.findNicknamesByIds(registeredByIds);

        List<NotificationItemResult> items = notifications.stream().map(n -> {
            List<NotificationPin> links = pinsMap.getOrDefault(n.getId(), List.of());
            Pin firstPin = links.isEmpty() ? null : pinById.get(links.get(0).getPinId());
            String firstPlaceName = firstPin != null ? firstPin.getPlaceName() : FALLBACK_PLACE_NAME;
            return new NotificationItemResult(
                    n.getId(),
                    n.getType(),
                    n.getRegisteredBy(),
                    nicknameById.getOrDefault(n.getRegisteredBy(), FALLBACK_NICKNAME),
                    firstPlaceName,
                    links.size(),
                    toInstant(n.getCreatedAt()),
                    n.getReadAt()
            );
        }).toList();

        long unread = repository.countUnreadByReceiverId(receiverId);
        return new NotificationListResult(items, unread);
    }

    /**
     * 알림 단건 상세. {@code receiverId} 와 일치하지 않으면 {@link ErrorType#NOT_FOUND}.
     * 핀 메타는 sort_order 보존, soft-delete 된 핀은 좌표/주소 마스킹.
     */
    @Transactional(readOnly = true)
    public NotificationDetailResult getDetail(Long notificationId, Long receiverId) {
        Notification n = repository.findByIdAndReceiverId(notificationId, receiverId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND));

        List<NotificationPin> links = repository.findPinsByNotificationId(notificationId);
        Set<Long> pinIds = links.stream().map(NotificationPin::getPinId).collect(Collectors.toSet());
        Map<Long, Pin> pinById = loadPinsByIds(pinIds);

        String registeredByNickname = userRepository.findById(n.getRegisteredBy())
                .map(UserModel::getNickname)
                .orElse(FALLBACK_NICKNAME);

        List<NotificationPinItemResult> pinItems = links.stream().map(link -> {
            Pin pin = pinById.get(link.getPinId());
            if (pin == null) {
                // pin row 자체가 존재하지 않음 (이론상 거의 없음)
                return new NotificationPinItemResult(link.getPinId(), DELETED_PLACE_NAME, null, null, null, true);
            }
            if (pin.isDeleted()) {
                return new NotificationPinItemResult(
                        pin.getId(), pin.getPlaceName(), null, null, null, true);
            }
            return new NotificationPinItemResult(
                    pin.getId(), pin.getPlaceName(), pin.getAddress(),
                    pin.getLatitude(), pin.getLongitude(), false);
        }).toList();

        return new NotificationDetailResult(
                n.getId(), n.getType(), registeredByNickname, toInstant(n.getCreatedAt()), pinItems);
    }

    /**
     * 수신자의 미읽음 알림을 모두 읽음 처리. 멱등.
     * 반환값은 갱신된 행 수.
     */
    @Transactional
    public int markAllRead(Long receiverId) {
        return repository.markAllReadByReceiverId(receiverId, Instant.now());
    }

    /**
     * 핀 id 집합에 대해 1회 조회로 Map을 구성한다. PinRepository에 batch 조회 메서드가 없어
     * 개별 findById 반복으로 폴백한다 (수신 핀 수가 작아 비용 허용). 빈 집합은 즉시 빈 맵 반환.
     */
    private Map<Long, Pin> loadPinsByIds(Collection<Long> pinIds) {
        if (pinIds == null || pinIds.isEmpty()) return Map.of();
        Map<Long, Pin> result = new LinkedHashMap<>(pinIds.size());
        for (Long pinId : pinIds) {
            Optional<Pin> opt = pinRepository.findById(pinId);
            opt.ifPresent(p -> result.put(p.getId(), p));
        }
        return result;
    }

    /** BaseEntity.createdAt 은 {@link ZonedDateTime} → {@link Instant} 로 정규화. */
    private static Instant toInstant(ZonedDateTime zdt) {
        return zdt == null ? null : zdt.toInstant();
    }

    public record NotificationItemResult(
            Long id,
            NotificationType type,
            Long registeredBy,
            String registeredByNickname,
            String firstPlaceName,
            int totalPinCount,
            Instant createdAt,
            Instant readAt
    ) {}

    public record NotificationListResult(
            List<NotificationItemResult> items,
            long unreadCount
    ) {}

    public record NotificationPinItemResult(
            Long pinId,
            String placeName,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            boolean deleted
    ) {}

    public record NotificationDetailResult(
            Long id,
            NotificationType type,
            String registeredByNickname,
            Instant createdAt,
            List<NotificationPinItemResult> pins
    ) {}
}
