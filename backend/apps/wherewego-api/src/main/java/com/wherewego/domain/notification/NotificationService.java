package com.wherewego.domain.notification;

import com.wherewego.domain.group.Group;
import com.wherewego.domain.group.GroupMemberRepository;
import com.wherewego.domain.group.GroupRepository;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinRepository;
import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 트랜잭션으로 자체 커밋한다. 클라이언트는 mount / visibilitychange / focus 시점에
 * REST 조회로 신규 알림을 감지한다 (옵션 B 다운그레이드, 2026-05-21).</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final int RECENT_LIMIT_HARD_CAP = 50;
    private static final String FALLBACK_NICKNAME = "멤버";
    private static final String FALLBACK_PLACE_NAME = "저장된 장소";
    private static final String DELETED_PLACE_NAME = "삭제된 장소";

    private final NotificationRepository repository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupRepository groupRepository;
    private final PinRepository pinRepository;
    private final UserRepository userRepository;
    private final NotificationVisitWriter visitWriter;

    /**
     * 웹/모바일 직접 등록 트리거 (PinV1Controller.createPin에서 트랜잭션 밖에서 호출).
     * 호출자에 트랜잭션이 없으므로 REQUIRED(기본)로 자체 트랜잭션을 시작·커밋한다.
     * BR-3: 호출자는 try-catch로 본 메서드의 실패를 격리한다.
     */
    @Transactional
    public void createForManualPin(Long groupId, Long registeredBy, Long pinId) {
        fanOut(groupId, registeredBy, List.of(pinId), NotificationType.MANUAL_PIN);
    }

    /**
     * 챗봇 핀 저장 배치 트리거 (InstagramLinkHandler, PlaceSelectionHandler).
     * pinIds가 비어 있으면 no-op (FR-4 / BR-5).
     */
    @Transactional
    public void createForChatbotBatch(Long groupId, Long registeredBy, List<Long> pinIds) {
        if (pinIds == null || pinIds.isEmpty()) return;
        fanOut(groupId, registeredBy, pinIds, NotificationType.CHATBOT_PINS);
    }

    /**
     * Phase 10: WISH/REEL → MEMORY 전환 알림을 그룹 멤버 + 본인에게 fan-out.
     *
     * <p><b>호출 계약</b>: {@code registeredBy} 는 호출자가 사전에 {@code groupId} 의
     * 활성 멤버임을 검증한 사용자 ID여야 한다. 본 메서드는 멤버십 재검증을 수행하지 않는다.
     * 정상 진입 경로는 {@code PinV1Controller#updatePin} 으로, 이미 PinService.updatePin
     * 내부에서 GroupMember 활성 멤버십이 검증된다.</p>
     *
     * <p>본 메서드는 트랜잭션을 시작하지 않는다 (호출자가 PATCH 응답을 차단하지 않도록 try-catch 격리,
     * BR-VD-6). 각 receiver 별로 {@link NotificationVisitWriter#writeOne}을
     * {@code REQUIRES_NEW} 새 트랜잭션으로 호출한다.</p>
     *
     * <p><b>본인 포함 fan-out</b>: 등록자 본인에게도 1행이 생성되어 알림함에 자신의 방문
     * 기록이 남는다 (Phase 11 "우리 기록" 화면 연동 전 과도기 용도). NotificationItem
     * UI 는 본인 분기 시 "내가 다녀온 장소"로 라벨링한다.</p>
     *
     * <p><b>중복 차단</b>: 부분 UNIQUE 인덱스 {@code uq_notifications_visit} 가 동일
     * {@code (groupId, receiverId, registeredBy, pinId)} 조합 1회만 허용한다. 위반 시
     * {@link org.springframework.dao.DataIntegrityViolationException} 을 catch 후 조용히 스킵하여
     * race-free 중복 차단을 보장한다 (BR-3).</p>
     */
    public void createForVisitDetected(Long groupId, Long registeredBy, Long pinId) {
        // 방어적 검증 (gemini-code-assist 권고): 호출자(PinV1Controller)가 PinService.requireActiveMembership
        // 으로 사전 검증하지만, 서비스 레이어 자체에서도 비멤버 ID 차단. 외부 직접 호출/리팩터링 대비.
        if (groupMemberRepository.findActiveByGroupIdAndUserId(groupId, registeredBy).isEmpty()) {
            log.warn("createForVisitDetected skipped — registeredBy {} not an active member of group {}",
                    registeredBy, groupId);
            return;
        }
        List<Long> otherIds = groupMemberRepository.findOtherActiveMemberIds(groupId, registeredBy);
        List<Long> receiverIds = new ArrayList<>(otherIds);
        receiverIds.add(registeredBy);
        for (Long receiverId : receiverIds) {
            try {
                visitWriter.writeOne(groupId, receiverId, registeredBy, pinId);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // 부분 UNIQUE 위반 = 동일 조합이 이미 존재 → 조용히 스킵 (race-free 중복 차단).
                log.debug("visit notification skipped (duplicate) receiverId={} pinId={}", receiverId, pinId);
            } catch (RuntimeException e) {
                // gemini-code-assist 권고: 일시적 DB 오류 등으로 한 receiver 가 실패해도
                // 다른 receiver 까지 fan-out 이 중단되지 않도록 개별 격리. BR-VD-6 호출자(Controller)
                // 격리와 별개로, fan-out 자체의 best-effort 도 보장.
                log.warn("visit notification per-receiver failed receiverId={} pinId={}", receiverId, pinId, e);
            }
        }
    }

    /**
     * 공통 fan-out 로직. 활성 멤버 전원(등록자 본인 포함)에게
     * {@link Notification} + {@link NotificationPin} 링크를 저장한다.
     */
    private void fanOut(Long groupId, Long registeredBy, List<Long> pinIds, NotificationType type) {
        List<Long> otherIds = groupMemberRepository.findOtherActiveMemberIds(groupId, registeredBy);
        List<Long> receiverIds = new ArrayList<>(otherIds);
        receiverIds.add(registeredBy);  // 본인도 알림함에서 저장 기록을 확인할 수 있도록 포함

        for (Long receiverId : receiverIds) {
            Notification n = repository.save(
                    Notification.create(groupId, receiverId, registeredBy, type));
            List<NotificationPin> links = new ArrayList<>(pinIds.size());
            for (int i = 0; i < pinIds.size(); i++) {
                links.add(NotificationPin.link(n.getId(), pinIds.get(i), i));
            }
            repository.saveAllPins(links);
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

        // 첫 핀 메타(placeName)는 모든 타입에서 필요.
        // Phase 13: CHATBOT_PINS 는 위시/발견 분리 표시를 위해 연결 핀 전체의 tag 를 집계해야 하므로
        // CHATBOT_PINS 알림의 연결 핀은 첫 핀뿐 아니라 전체를 batch 조회한다 (MVP 규모, §2.3).
        Set<Long> pinIdsToLoad = new java.util.HashSet<>();
        for (Notification n : notifications) {
            List<NotificationPin> links = pinsMap.getOrDefault(n.getId(), List.of());
            if (links.isEmpty()) continue;
            if (n.getType() == NotificationType.CHATBOT_PINS) {
                links.forEach(link -> pinIdsToLoad.add(link.getPinId()));
            } else {
                pinIdsToLoad.add(links.get(0).getPinId());
            }
        }
        Map<Long, Pin> pinById = loadPinsByIds(pinIdsToLoad);

        // 등록자 닉네임 batch 조회 (UserRepository.findNicknamesByIds 활용)
        Set<Long> registeredByIds = notifications.stream()
                .map(Notification::getRegisteredBy)
                .collect(Collectors.toSet());
        Map<Long, String> nicknameById = registeredByIds.isEmpty()
                ? Map.of()
                : userRepository.findNicknamesByIds(registeredByIds);

        // GM-2: 알림의 groupId 집합 → 그룹명 batch (findById 반복, loadPinsByIds 선례 — MVP 규모 N+1 허용).
        Set<Long> groupIds = notifications.stream()
                .map(Notification::getGroupId)
                .collect(Collectors.toSet());
        Map<Long, String> groupNameById = loadGroupNamesByIds(groupIds);

        List<NotificationItemResult> items = notifications.stream().map(n -> {
            List<NotificationPin> links = pinsMap.getOrDefault(n.getId(), List.of());
            Pin firstPin = links.isEmpty() ? null : pinById.get(links.get(0).getPinId());
            String firstPlaceName = firstPin != null ? firstPin.getPlaceName() : FALLBACK_PLACE_NAME;

            // Phase 13: CHATBOT_PINS 만 연결 핀 tag 를 집계해 위시/발견 카운트를 채운다.
            // 다른 타입(MANUAL_PIN/VISIT_DETECTED)은 0 (프론트는 totalPinCount 사용).
            int wishCount = 0;
            int reelCount = 0;
            if (n.getType() == NotificationType.CHATBOT_PINS) {
                for (NotificationPin link : links) {
                    Pin pin = pinById.get(link.getPinId());
                    if (pin == null) continue;
                    if (pin.getTag() == com.wherewego.domain.pin.PinTag.WISH) {
                        wishCount++;
                    } else if (pin.getTag() == com.wherewego.domain.pin.PinTag.REEL) {
                        reelCount++;
                    }
                }
            }

            return new NotificationItemResult(
                    n.getId(),
                    n.getType(),
                    n.getRegisteredBy(),
                    nicknameById.getOrDefault(n.getRegisteredBy(), FALLBACK_NICKNAME),
                    groupNameById.get(n.getGroupId()),
                    firstPlaceName,
                    links.size(),
                    wishCount,
                    reelCount,
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
        groupMemberRepository.findActiveByGroupIdAndUserId(n.getGroupId(), receiverId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND));

        String registeredByNickname = userRepository.findById(n.getRegisteredBy())
                .map(UserModel::getNickname)
                .orElse(FALLBACK_NICKNAME);

        // GM-2: 그룹명. soft-delete 그룹도 findById 로 그룹명 노출, 미존재 시 null.
        String groupName = groupRepository.findById(n.getGroupId())
                .map(Group::getName)
                .orElse(null);

        List<NotificationPin> links = repository.findPinsByNotificationId(notificationId);
        Set<Long> pinIds = links.stream().map(NotificationPin::getPinId).collect(Collectors.toSet());
        Map<Long, Pin> pinById = loadPinsByIds(pinIds);

        boolean isVisitType = n.getType() == NotificationType.VISIT_DETECTED;
        List<NotificationPinItemResult> pinItems = links.stream().map(link -> {
            Pin pin = pinById.get(link.getPinId());
            if (pin == null) {
                // pin row 자체가 존재하지 않음 (이론상 거의 없음)
                return new NotificationPinItemResult(link.getPinId(), DELETED_PLACE_NAME, null, null, null, true, null, null, null);
            }
            if (pin.isDeleted()) {
                return new NotificationPinItemResult(
                        pin.getId(), pin.getPlaceName(), null, null, null, true, null, null, null);
            }
            // Phase 10: VISIT_DETECTED 알림에서만 핀의 최신 memo를 join.
            // 그 외 타입(MANUAL_PIN/CHATBOT_PINS)은 memo 노출 스코프 밖이므로 항상 null.
            String memo = isVisitType ? pin.getMemo() : null;
            return new NotificationPinItemResult(
                    pin.getId(), pin.getPlaceName(), pin.getAddress(),
                    pin.getLatitude(), pin.getLongitude(), false, pin.getInstagramUrl(), memo,
                    pin.getTag().name());
        }).toList();

        return new NotificationDetailResult(
                n.getId(), n.getType(), registeredByNickname, groupName, toInstant(n.getCreatedAt()), pinItems);
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

    /**
     * 그룹 id 집합에 대해 그룹명 Map 을 구성한다. soft-delete 그룹도 findById 로 그룹명만 노출한다
     * (deletedAt 무관). 미존재 그룹은 Map 에서 빠져 호출부에서 null 로 처리된다.
     * GroupRepository 에 batch 조회가 없어 개별 findById 반복으로 폴백한다 (수신 그룹 수가 작아 비용 허용).
     */
    private Map<Long, String> loadGroupNamesByIds(Collection<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) return Map.of();
        Map<Long, String> result = new LinkedHashMap<>(groupIds.size());
        for (Long groupId : groupIds) {
            if (groupId == null) continue;
            groupRepository.findById(groupId).ifPresent(g -> result.put(groupId, g.getName()));
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
            /** GM-2: 알림이 속한 그룹명. soft-delete 그룹도 노출, 미존재 시 null. */
            String groupName,
            String firstPlaceName,
            int totalPinCount,
            /**
             * Phase 13: CHATBOT_PINS 알림에 연결된 핀 중 WISH 태그 핀 수. 다른 타입은 0.
             * 프론트가 "위시 N곳, 발견 M곳" 분리 표시에 사용 (§2.3).
             */
            int wishCount,
            /**
             * Phase 13: CHATBOT_PINS 알림에 연결된 핀 중 REEL 태그 핀 수. 다른 타입은 0.
             */
            int reelCount,
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
            boolean deleted,
            String instagramUrl,
            String memo,
            /**
             * Phase 10 FR-VD-29: 핀의 현재 태그(REEL/WISH/MEMORY). 알림 상세에서
             * VISIT_DETECTED 케이스의 MEMORY 배지 표시에 사용. soft-delete 또는
             * 핀 자체가 사라진 경우 null.
             */
            String tag
    ) {}

    public record NotificationDetailResult(
            Long id,
            NotificationType type,
            String registeredByNickname,
            /** GM-2: 알림이 속한 그룹명. soft-delete 그룹도 노출, 미존재 시 null. */
            String groupName,
            Instant createdAt,
            List<NotificationPinItemResult> pins
    ) {}
}
