package com.wherewego.domain.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.chat.BotPlaceCardsPayloadBuilder.PlaceCardsPayload;
import com.wherewego.domain.group.GroupMemberRepository;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.group.GroupSummary;
import com.wherewego.domain.pin.PinRepository;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.place.ReelPlaceExtractor;
import com.wherewego.domain.push.PushNotificationService;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * GC-1: 그룹 채팅 서비스(FR-GC1-1~8) — 기존 커플 방(1:1) 서비스를 그룹 공용 방으로 일반화.
 * 봇은 개입하지 않으며 저장 + 발신자 제외 멤버 APNs 푸시만 한다(실시간 표시는 클라이언트
 * 폴링/포그라운드 재조회/푸시가 담당 — WS 재도입 안 함, 설계 D5).
 *
 * <p>전송: 활성 멤버십 검증(비멤버 → GROUP_NOT_MEMBER 403) → 활성 그룹 방 확보(V021 부분 UNIQUE)
 * → kind 분기 append(TEXT/REEL_LINK) → 커밋 후(afterCommit) 발신자 제외 전 활성 멤버에게 APNs 푸시.
 * 1인 그룹이면 푸시를 생략한다. 푸시는 best-effort 로 실패해도 전송 성공에 영향이 없다.</p>
 *
 * <p>조회: REEL_LINK 의 {@code registered} 를 pins 파생으로 배치 계산(페이지당 IN 쿼리 1회 — FR-GC1-4,
 * 상태 컬럼 없음), 발신자 닉네임을 배치 조회하여 {@link GroupChatMessageFrame}으로 조립한다.
 * 조회 시 내 멤버별 읽음 포인터({@code chat_room_reads})를 방 최신 메시지로 전진시킨다(역행 방지 — FR-GC1-2).</p>
 *
 * <p>추출: REEL_LINK 발신자만 온디맨드 추출 API 를 호출할 수 있다(발신자 탈퇴 NULL 포함 위반 403 —
 * FR-GC1-6). 외부 호출(스크래핑/Gemini/장소 검색)은 트랜잭션 밖에서 수행한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupChatService {

    private static final String PREVIEW_PROCESSING = "답장을 준비하고 있어요";
    private static final String PREVIEW_REEL_LINK = "릴스 링크";
    private static final int PREVIEW_MAX_LENGTH = 40;
    private static final int TEXT_MAX_LENGTH = 2000;
    /** REEL_LINK URL 상한 — TEXT 가드와 대칭. INSTAGRAM_URL 패턴이 임의 suffix 를 허용하므로 payload 비대 차단. */
    private static final int REEL_URL_MAX_LENGTH = 2000;

    private final GroupMemberService groupMemberService;
    private final GroupMemberRepository groupMemberRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomReadRepository chatRoomReadRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageAppender chatMessageAppender;
    private final PushNotificationService pushNotificationService;
    private final PinRepository pinRepository;
    private final UserRepository userRepository;
    private final ReelPlaceExtractor reelPlaceExtractor;
    private final BotPlaceCardsPayloadBuilder payloadBuilder;
    private final PlaceProperties placeProperties;
    private final ObjectMapper objectMapper;

    /**
     * 그룹 방에 사용자 메시지를 전송한다(FR-GC1-1/3/8). kind 는 클라이언트가 지정한다.
     *
     * @param userId  발신 사용자 ID
     * @param groupId 대상 그룹 ID
     * @param kind    TEXT 또는 REEL_LINK (그 외 → CHAT_KIND_INVALID 400)
     * @param text    TEXT 본문(1~2000자 — 위반 시 CHAT_TEXT_INVALID 400). REEL_LINK 면 무시.
     * @param url     REEL_LINK 인스타 URL(https + 인스타 패턴 — 위반 시 CHAT_REEL_URL_INVALID 400). TEXT 면 무시.
     * @return 저장된 {@link ChatMessage}(컨트롤러가 응답으로 매핑)
     * @throws CoreException 비활성 멤버이면 GROUP_NOT_MEMBER(403)
     */
    @Transactional
    public ChatMessage postMessage(Long userId, Long groupId, MessageKind kind, String text, String url) {
        groupMemberService.requireActiveMembership(userId, groupId);

        ChatRoom room = ensureGroupRoom(groupId);
        ChatMessage saved = appendByKind(room.getId(), userId, kind, text, url);

        broadcastToOthersAfterCommit(groupId, userId, saved);
        return saved;
    }

    /**
     * 그룹 방 메시지를 cursor 기반 최신순(id DESC)으로 페이지 조회하고 내 읽음 포인터를 전진시킨다
     * (FR-GC1-2/4).
     *
     * <p>조회에도 멤버십 검증을 강제한다(타 그룹 차단). 활성 그룹 방이 아직 없으면 빈 페이지를 반환한다.
     * REEL_LINK 프레임에는 pins 파생 {@code registered} 가, 모든 프레임에는 발신자 닉네임이 합성된다.</p>
     *
     * @param cursor 이 id 미만(과거)만 조회. {@code null}이면 최신부터.
     * @param limit  페이지 크기(컨트롤러에서 1~50으로 클램프하여 전달)
     */
    @Transactional
    public GroupMessagesPage getMessages(Long userId, Long groupId, Long cursor, int limit) {
        groupMemberService.requireActiveMembership(userId, groupId);

        return chatRoomRepository.findActiveGroupRoom(groupId)
                .map(room -> {
                    ChatMessagePageResult page = ChatMessagePageResult.of(
                            chatMessageRepository.findByRoomIdBefore(room.getId(), cursor, limit + 1),
                            limit);
                    GroupMessagesPage assembled = assemblePage(groupId, page);
                    markRoomReadForUser(room.getId(), userId);
                    return assembled;
                })
                .orElseGet(GroupMessagesPage::empty);
    }

    /**
     * 그룹 채팅방 목록 — 사용자의 활성 그룹별 방 요약(FR-GC1-7).
     *
     * <p>{@code listMyGroups}로 그룹마다 1개 항목을 만든 뒤 <b>마지막 메시지 시각(lastAt) 내림차순</b>으로
     * 정렬한다(메신저 통념 — 최근 대화가 위로). 메시지가 없는 방(lastAt=null)은 끝으로 보낸다. lastAt 은
     * ISO-8601 offset 문자열이며 단일 타임존(KST, 동일 offset)이라 사전식 비교가 시각 순서와 일치한다.
     * V021 백필 + 그룹 생성 훅으로 통상은 방이 존재하며, 방이 없는 그룹은 가상 항목(roomId=null)으로 내린다.</p>
     */
    @Transactional(readOnly = true)
    public List<GroupRoomSummary> getRooms(Long userId) {
        List<GroupSummary> groups = groupMemberService.listMyGroups(userId);
        List<GroupRoomSummary> result = new ArrayList<>(groups.size());
        for (GroupSummary group : groups) {
            result.add(chatRoomRepository.findActiveGroupRoom(group.groupId())
                    .map(room -> summarizeRoom(userId, group, room))
                    .orElseGet(() -> emptySummary(group)));
        }
        result.sort(Comparator.comparing(
                GroupRoomSummary::lastAt,
                Comparator.nullsLast(Comparator.<String>reverseOrder())));
        return result;
    }

    /**
     * REEL_LINK 메시지의 장소를 온디맨드 추출한다(FR-GC1-5/6). 채팅 메시지를 append 하지 않는다.
     *
     * <p>의도적으로 클래스 트랜잭션 없이 실행한다 — 검증 조회는 각 리포지토리의 짧은 트랜잭션으로 충분하고,
     * 외부 호출(최대 {@code place.search.extract-deadline-ms})이 DB 커넥션을 점유하지 않게 한다.</p>
     *
     * <p>추출 0곳은 빈 cards(200), 파싱/검색 실패는 PLC_* {@link CoreException} 전파(클라 재시도 가능)다.</p>
     *
     * @throws CoreException GROUP_NOT_MEMBER(403) / CHAT_MESSAGE_NOT_FOUND(404) /
     *                       CHAT_NOT_REEL_LINK(400) / CHAT_EXTRACT_FORBIDDEN(403)
     */
    public PlaceCardsPayload extractPlaces(Long userId, Long groupId, Long messageId) {
        groupMemberService.requireActiveMembership(userId, groupId);

        ChatRoom room = chatRoomRepository.findActiveGroupRoom(groupId)
                .orElseThrow(() -> new CoreException(ErrorType.CHAT_MESSAGE_NOT_FOUND));
        ChatMessage message = chatMessageRepository.findActiveByIdAndRoomId(messageId, room.getId())
                .orElseThrow(() -> new CoreException(ErrorType.CHAT_MESSAGE_NOT_FOUND));

        if (message.getKind() != MessageKind.REEL_LINK) {
            throw new CoreException(ErrorType.CHAT_NOT_REEL_LINK);
        }
        // FR-GC1-6: 발신자만. 발신자 탈퇴(sender_user_id NULL)도 영구 "등록전"으로 거부한다(MVP 확정 정책).
        if (message.getSenderUserId() == null || !message.getSenderUserId().equals(userId)) {
            throw new CoreException(ErrorType.CHAT_EXTRACT_FORBIDDEN);
        }

        String url = reelUrlOf(message);
        List<PlaceSearchHit> hits =
                reelPlaceExtractor.extract(url, placeProperties.search().extractDeadlineMs());
        return payloadBuilder.build(hits, url);
    }

    // ────── 전송 내부 ──────

    /**
     * kind 분기 검증 + append(FR-GC1-3). TEXT 는 1~2000자, REEL_LINK 는 https + 인스타 패턴을 강제한다.
     */
    private ChatMessage appendByKind(Long roomId, Long userId, MessageKind kind, String text, String url) {
        if (kind == MessageKind.TEXT) {
            String body = text == null ? "" : text.trim();
            if (body.isEmpty() || body.length() > TEXT_MAX_LENGTH) {
                throw new CoreException(ErrorType.CHAT_TEXT_INVALID);
            }
            return chatMessageAppender.appendGroupText(roomId, userId, body);
        }
        if (kind == MessageKind.REEL_LINK) {
            return chatMessageAppender.appendReelLink(roomId, userId, validateReelUrl(url));
        }
        throw new CoreException(ErrorType.CHAT_KIND_INVALID);
    }

    /**
     * REEL_LINK URL 검증: https 필수 + 인스타 릴스 패턴({@link ReelPlaceExtractor#INSTAGRAM_URL},
     * {@code Pin.validateInstagramUrl} 의 https 가드 선례) + 2000자 상한(payload 비대 차단).
     */
    private static String validateReelUrl(String url) {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.length() > REEL_URL_MAX_LENGTH
                || !trimmed.startsWith("https://")
                || !ReelPlaceExtractor.INSTAGRAM_URL.matcher(trimmed).matches()) {
            throw new CoreException(ErrorType.CHAT_REEL_URL_INVALID);
        }
        return trimmed;
    }

    /**
     * 활성 그룹 방을 확보한다(get-or-create 안전망 — FR-GC1-1). V021 백필 + 그룹 생성 훅으로 통상은 존재한다.
     *
     * <p>동시 생성 race 는 {@code ON CONFLICT DO NOTHING} insert 가 예외 없이 흡수한다(PR #118 리뷰 반영
     * — 기존 save+catch 폴백은 참여 트랜잭션이 rollback-only 로 마킹되어 커밋 시
     * UnexpectedRollbackException 으로 전체 실패하는 결함이 있었다). insert 후 재조회가 비면 INTERNAL_ERROR.</p>
     */
    private ChatRoom ensureGroupRoom(Long groupId) {
        return chatRoomRepository.findActiveGroupRoom(groupId)
                .orElseGet(() -> createGroupRoomIfAbsent(groupId));
    }

    private ChatRoom createGroupRoomIfAbsent(Long groupId) {
        chatRoomRepository.insertGroupRoomIfAbsent(groupId);
        return chatRoomRepository.findActiveGroupRoom(groupId)
                .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "방 생성 충돌"));
    }

    /**
     * 발신자 제외 활성 멤버가 존재할 때만 커밋 후 APNs 푸시를 등록한다(FR-GC1-8).
     * 1인 그룹(상대 없음)이면 생략한다. kind 별 문구 분기는 {@code PushPayload.groupMessage}가 담당한다.
     */
    private void broadcastToOthersAfterCommit(Long groupId, Long userId, ChatMessage saved) {
        List<Long> otherMemberIds = groupMemberRepository.findOtherActiveMemberIds(groupId, userId);
        if (otherMemberIds.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 커밋 후 발신자 제외 멤버 각각에게 APNs 푸시(best-effort). 실패해도 전송 성공에 영향 없음.
                for (Long memberUserId : otherMemberIds) {
                    pushNotificationService.pushGroupMessage(memberUserId, saved.getRoomId(), saved.getKind());
                }
            }
        });
    }

    // ────── 조회 내부 ──────

    /**
     * 페이지를 프레임으로 조립한다 — registered(REEL_LINK 배치 IN 쿼리 1회) + 발신자 닉네임(배치 1회).
     */
    private GroupMessagesPage assemblePage(Long groupId, ChatMessagePageResult page) {
        List<ChatMessage> messages = page.messages();
        Set<String> registeredUrls = registeredUrlsOf(groupId, messages);
        Map<Long, String> nicknames = nicknamesOf(messages);

        List<GroupChatMessageFrame> frames = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            Boolean registered = null;
            if (message.getKind() == MessageKind.REEL_LINK) {
                String url = readPayloadText(message.getPayloadJson(), "url");
                registered = url != null && registeredUrls.contains(url);
            }
            String nickname = message.getSenderUserId() == null
                    ? null
                    : nicknames.get(message.getSenderUserId());
            frames.add(GroupChatMessageFrame.from(message, objectMapper, nickname, registered));
        }
        return new GroupMessagesPage(frames, page.hasMore(), page.nextCursor());
    }

    /**
     * 페이지 내 REEL_LINK URL 을 모아 그룹 활성 핀에 존재하는 URL 집합을 IN 쿼리 1회로 계산한다(FR-GC1-4).
     */
    private Set<String> registeredUrlsOf(Long groupId, List<ChatMessage> messages) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (ChatMessage message : messages) {
            if (message.getKind() == MessageKind.REEL_LINK) {
                String url = readPayloadText(message.getPayloadJson(), "url");
                if (url != null && !url.isBlank()) {
                    urls.add(url);
                }
            }
        }
        if (urls.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(pinRepository.findActiveInstagramUrlsIn(groupId, urls));
    }

    private Map<Long, String> nicknamesOf(List<ChatMessage> messages) {
        LinkedHashSet<Long> senderIds = new LinkedHashSet<>();
        for (ChatMessage message : messages) {
            if (message.getSenderUserId() != null) {
                senderIds.add(message.getSenderUserId());
            }
        }
        if (senderIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findNicknamesByIds(senderIds);
    }

    /**
     * 내 읽음 포인터를 방 최신 메시지 id 로 전진시킨다(FR-GC1-2, 역행 방지). 메시지가 없으면 갱신하지 않는다.
     * 읽음 행이 없으면 생성한다 — V021 UNIQUE(room, user) 동시 생성 충돌은 {@code ON CONFLICT DO NOTHING}
     * insert 가 예외 없이 흡수한다(PR #118 리뷰 반영 — 기존 save+catch 폴백은 rollback-only 마킹으로
     * getMessages 전체가 실패하는 결함이 있었다).
     */
    private void markRoomReadForUser(Long roomId, Long userId) {
        latestMessage(roomId).ifPresent(latest -> {
            ChatRoomRead read = chatRoomReadRepository.findByRoomIdAndUserId(roomId, userId)
                    .orElseGet(() -> createReadRowIfAbsent(roomId, userId));
            read.markRead(latest.getId());
            chatRoomReadRepository.save(read);
        });
    }

    private ChatRoomRead createReadRowIfAbsent(Long roomId, Long userId) {
        chatRoomReadRepository.insertIfAbsent(roomId, userId);
        return chatRoomReadRepository.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "읽음 행 생성 충돌"));
    }

    // ────── 목록 내부 ──────

    private GroupRoomSummary summarizeRoom(Long userId, GroupSummary group, ChatRoom room) {
        return latestMessage(room.getId())
                .map(latest -> new GroupRoomSummary(
                        room.getId(),
                        group.groupId(),
                        group.name(),
                        previewOf(latest),
                        latest.getSenderUserId(),
                        isUnread(userId, room.getId(), latest),
                        formatCreatedAt(latest.getCreatedAt())))
                .orElseGet(() -> new GroupRoomSummary(
                        room.getId(), group.groupId(), group.name(), null, null, false, null));
    }

    private GroupRoomSummary emptySummary(GroupSummary group) {
        return new GroupRoomSummary(null, group.groupId(), group.name(), null, null, false, null);
    }

    private Optional<ChatMessage> latestMessage(Long roomId) {
        List<ChatMessage> latest = chatMessageRepository.findByRoomIdBefore(roomId, null, 1);
        return latest.isEmpty() ? Optional.empty() : Optional.of(latest.get(0));
    }

    /**
     * unread 판정(FR-GC1-7, 인스타식 boolean): 마지막 메시지가 타인 발신(탈퇴 발신자 NULL 포함)이고
     * 내 읽음 포인터가 없거나 그보다 과거면 true. 내가 보낸 마지막 메시지는 false.
     */
    private boolean isUnread(Long userId, Long roomId, ChatMessage latest) {
        if (userId.equals(latest.getSenderUserId())) {
            return false;
        }
        Long lastRead = chatRoomReadRepository.findByRoomIdAndUserId(roomId, userId)
                .map(ChatRoomRead::getLastReadMessageId)
                .orElse(null);
        return lastRead == null || lastRead < latest.getId();
    }

    /**
     * 미리보기 규칙(FR-GC1-7): TEXT/SYSTEM/MEMO_PROMPT → payload text 앞 40자, REEL_LINK → "릴스 링크",
     * PLACE_CARDS/PROCESSING → 봇 규칙 재사용(그룹 방엔 등장하지 않으나 exhaustive 커버).
     */
    private String previewOf(ChatMessage message) {
        return switch (message.getKind()) {
            case TEXT, SYSTEM, MEMO_PROMPT -> truncate(readPayloadTextOrEmpty(message.getPayloadJson(), "text"));
            case REEL_LINK -> PREVIEW_REEL_LINK;
            case PLACE_CARDS -> "장소 " + placeCardCount(message.getPayloadJson()) + "곳";
            case PROCESSING -> PREVIEW_PROCESSING;
        };
    }

    // ────── payload 파싱 헬퍼 ──────

    /** REEL_LINK payload 의 url 을 읽는다. 메시지 저장 시 검증되므로 누락은 내부 오류다. */
    private String reelUrlOf(ChatMessage message) {
        String url = readPayloadText(message.getPayloadJson(), "url");
        if (url == null || url.isBlank()) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "릴스 URL payload 누락");
        }
        return url;
    }

    private String readPayloadTextOrEmpty(String payloadJson, String field) {
        String value = readPayloadText(payloadJson, field);
        return value == null ? "" : value;
    }

    private String readPayloadText(String payloadJson, String field) {
        JsonNode node = readField(payloadJson, field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private int placeCardCount(String payloadJson) {
        JsonNode cards = readField(payloadJson, "cards");
        return cards != null && cards.isArray() ? cards.size() : 0;
    }

    private JsonNode readField(String payloadJson, String field) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(payloadJson).get(field);
        } catch (Exception e) {
            log.warn("그룹 방 payload 파싱 실패, 빈 값으로 폴백: {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.length() <= PREVIEW_MAX_LENGTH ? text : text.substring(0, PREVIEW_MAX_LENGTH);
    }

    private static String formatCreatedAt(ZonedDateTime createdAt) {
        if (createdAt == null) {
            return null;
        }
        return createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
