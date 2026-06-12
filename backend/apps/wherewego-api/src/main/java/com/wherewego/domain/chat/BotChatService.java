package com.wherewego.domain.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.group.GroupSummary;
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
import java.util.List;
import java.util.Optional;

/**
 * P2 PR-1 / GM-2(B): 앱 봇 방 메시지 전송·조회·DM 목록 서비스(FR-1~FR-7).
 *
 * <p>GM-2: 봇 방을 그룹별((owner_user_id, group_id) 활성 1개)로 재구성한다. 전송/조회는 활성 멤버십을 강제하고
 * (비멤버 → GROUP_NOT_MEMBER 403), DM 목록은 사용자의 활성 그룹 전부를 봇 방 유무와 무관하게 표시한다.
 * 봇 방은 첫 전송 시 {@link #ensureBotRoom}이 lazy 생성한다(그룹 참여 시 자동 생성 안 함).</p>
 *
 * <p>전송 흐름: 멤버십 검증 → 봇 방 보장 → 사용자 메시지(USER/TEXT) → PROCESSING 플레이스홀더 append 후
 * PROCESSING 을 즉시 반환한다. 실제 1턴 처리는 {@link BotChatProcessor#processAsync}가 PROCESSING
 * <b>커밋 후</b> 별도 비동기 트랜잭션에서 수행한다 — afterCommit 트리거로 read-after-write 일관성을 보장한다.</p>
 *
 * <p>하위호환: develop 의 현 iOS 봇이 groupId 없이 호출하는 {@code /chat/bot/messages} 를 보존하기 위해
 * {@code postMessage(userId, text)}/{@code getBotMessages(userId, cursor, limit)} 래퍼를 유지한다 —
 * {@code findLatestActiveGroupIdByUserId} 로 최신 활성 그룹을 폴백 선택하여 그룹별 메서드에 위임한다(deprecated).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotChatService {

    private static final String PREVIEW_PROCESSING = "답장을 준비하고 있어요";
    private static final int PREVIEW_MAX_LENGTH = 40;

    private final GroupMemberService groupMemberService;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageAppender appender;
    private final BotChatProcessor botChatProcessor;
    private final ObjectMapper objectMapper;

    /**
     * 그룹별 봇 방에 사용자 메시지를 전송하고 PROCESSING 플레이스홀더를 반환한다(FR-3).
     *
     * <p>활성 멤버십을 강제하고(비멤버 → GROUP_NOT_MEMBER 403), 봇 방은 (owner, group)당 활성 1개
     * (V020 부분 UNIQUE)로 보장된다 — 없으면 생성하며 동시성은 DB 인덱스가 보호한다. PROCESSING 커밋 후
     * {@link BotChatProcessor#processAsync}를 afterCommit 으로 트리거한다(다른 빈 호출이라 @Async 정상 동작).</p>
     *
     * @param userId  봇 방 소유자
     * @param groupId 대상 그룹
     * @param text    사용자 입력 원문(인스타 URL 후보)
     * @return PROCESSING 플레이스홀더 메시지(컨트롤러가 messageId/kind 매핑에 사용)
     * @throws CoreException 비활성 멤버이면 GROUP_NOT_MEMBER(403)
     */
    @Transactional
    public ChatMessage postMessage(Long userId, Long groupId, String text) {
        groupMemberService.requireActiveMembership(userId, groupId);

        ChatRoom room = ensureBotRoom(userId, groupId);
        Long roomId = room.getId();

        appender.appendUserText(roomId, userId, text);
        ChatMessage processing = appender.appendBotProcessing(roomId);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                botChatProcessor.processAsync(userId, roomId, text);
            }
        });

        return processing;
    }

    /**
     * 그룹별 봇 방 메시지를 cursor 기반 최신순(id DESC)으로 페이지 조회하고 읽음 처리한다(FR-4/FR-5, AC-4/AC-5).
     *
     * <p>조회에도 활성 멤버십을 강제한다(타 그룹 차단): 비멤버이면 GROUP_NOT_MEMBER(403). 활성 봇 방이 없으면
     * 빈 페이지를 반환한다. 방이 있으면 {@code limit + 1}개를 조회하여 hasMore 를 판정하고, 방 최신 메시지 id 로
     * {@link ChatRoom#markRead}(역행 방지) 후 save 하여 last_read 를 갱신한다(읽음 처리, AC-5).</p>
     *
     * @param userId  봇 방 소유자
     * @param groupId 대상 그룹
     * @param cursor  이 id 미만(과거)만 조회. {@code null}이면 최신부터.
     * @param limit   페이지 크기(컨트롤러에서 1~50으로 클램프하여 전달)
     * @return cursor 페이지 결과. 방이 없으면 {@code {[], false, null}}.
     * @throws CoreException 비활성 멤버이면 GROUP_NOT_MEMBER(403)
     */
    @Transactional
    public ChatMessagePageResult getBotMessages(Long userId, Long groupId, Long cursor, int limit) {
        groupMemberService.requireActiveMembership(userId, groupId);

        return chatRoomRepository.findActiveBotRoom(userId, groupId)
                .map(room -> {
                    ChatMessagePageResult page = ChatMessagePageResult.of(
                            chatMessageRepository.findByRoomIdBefore(room.getId(), cursor, limit + 1),
                            limit);
                    markRoomRead(room);
                    return page;
                })
                .orElseGet(() -> new ChatMessagePageResult(List.of(), false, null));
    }

    /**
     * DM 목록 — 사용자의 활성 그룹별 봇 방 요약(FR-2/FR-6, AC-2/AC-6/AC-7).
     *
     * <p>{@code listMyGroups}(가입 순)를 순회하여 그룹마다 1개 항목을 만든다. 봇 방이 있으면 방 최신 메시지로
     * preview/lastSenderType/unread/lastAt 을 계산하고, 없으면 가상 항목(roomId/preview/lastSenderType/
     * lastAt=null, unread=false)을 내려 활성 그룹 전부를 표시한다(AC-7).</p>
     */
    @Transactional(readOnly = true)
    public List<BotRoomSummary> getBotRooms(Long userId) {
        List<GroupSummary> groups = groupMemberService.listMyGroups(userId);
        List<BotRoomSummary> result = new ArrayList<>(groups.size());
        for (GroupSummary group : groups) {
            result.add(chatRoomRepository.findActiveBotRoom(userId, group.groupId())
                    .map(room -> summarizeRoom(group, room))
                    .orElseGet(() -> emptySummary(group)));
        }
        return result;
    }

    /**
     * 하위호환 래퍼(deprecated). groupId 없는 현 iOS 봇 호출을 최신 활성 그룹 봇 방으로 폴백시킨다.
     * 활성 그룹이 없으면 GROUP_NOT_MEMBER(403)로 거부한다. A단계서 iOS 가 신규 API 로 전환한 뒤 제거한다.
     *
     * @deprecated GM-2 그룹별 봇 방으로 전환됨. {@link #postMessage(Long, Long, String)} 사용.
     */
    @Deprecated
    @Transactional
    public ChatMessage postMessage(Long userId, String text) {
        return postMessage(userId, requireLatestActiveGroupId(userId), text);
    }

    /**
     * 하위호환 래퍼(deprecated). groupId 없는 현 iOS 봇 호출을 최신 활성 그룹 봇 방으로 폴백시킨다.
     * 활성 그룹이 없으면 빈 페이지를 반환한다(현행 무방 사용자 동작 보존). A단계서 신규 API 전환 후 제거한다.
     *
     * @deprecated GM-2 그룹별 봇 방으로 전환됨. {@link #getBotMessages(Long, Long, Long, int)} 사용.
     */
    @Deprecated
    @Transactional
    public ChatMessagePageResult getBotMessages(Long userId, Long cursor, int limit) {
        return groupMemberService.findLatestActiveGroupIdByUserId(userId)
                .map(groupId -> getBotMessages(userId, groupId, cursor, limit))
                .orElseGet(() -> new ChatMessagePageResult(List.of(), false, null));
    }

    /**
     * 활성 봇 방을 보장한다(GM-2). 없으면 생성한다(V020 부분 UNIQUE 라 동시성은 DB 가 보호).
     *
     * <p>동시 생성 race 는 {@code ON CONFLICT DO NOTHING} insert 가 예외 없이 흡수한다(PR #118 리뷰 반영
     * — 기존 save+catch 폴백은 참여 트랜잭션이 rollback-only 로 마킹되어 커밋 시
     * UnexpectedRollbackException 으로 전체 실패하는 결함이 있었다). insert 후 재조회가 비면 INTERNAL_ERROR.</p>
     */
    private ChatRoom ensureBotRoom(Long userId, Long groupId) {
        return chatRoomRepository.findActiveBotRoom(userId, groupId)
                .orElseGet(() -> createBotRoomIfAbsent(userId, groupId));
    }

    private ChatRoom createBotRoomIfAbsent(Long userId, Long groupId) {
        chatRoomRepository.insertBotRoomIfAbsent(userId, groupId);
        return chatRoomRepository.findActiveBotRoom(userId, groupId)
                .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "방 생성 충돌"));
    }

    /**
     * 최신 활성 그룹 ID 를 강제로 가져온다(하위호환 전송 래퍼용). 활성 그룹이 없으면 GROUP_NOT_MEMBER(403).
     */
    private Long requireLatestActiveGroupId(Long userId) {
        return groupMemberService.findLatestActiveGroupIdByUserId(userId)
                .orElseThrow(() -> new CoreException(ErrorType.GROUP_NOT_MEMBER));
    }

    /**
     * 방 최신 메시지 id 로 읽음 포인터를 전진시킨다(AC-5). 메시지가 없으면 갱신하지 않는다.
     */
    private void markRoomRead(ChatRoom room) {
        latestMessage(room.getId()).ifPresent(latest -> {
            room.markRead(latest.getId());
            chatRoomRepository.save(room);
        });
    }

    private BotRoomSummary summarizeRoom(GroupSummary group, ChatRoom room) {
        return latestMessage(room.getId())
                .map(latest -> new BotRoomSummary(
                        room.getId(),
                        group.groupId(),
                        group.name(),
                        previewOf(latest),
                        latest.getSenderType(),
                        isUnread(room, latest),
                        formatCreatedAt(latest.getCreatedAt())))
                .orElseGet(() -> new BotRoomSummary(
                        room.getId(), group.groupId(), group.name(), null, null, false, null));
    }

    private BotRoomSummary emptySummary(GroupSummary group) {
        return new BotRoomSummary(null, group.groupId(), group.name(), null, null, false, null);
    }

    /**
     * 방 최신 메시지(있으면) — {@code findByRoomIdBefore(roomId, null, 1)} 의 첫 원소(설계 §5).
     */
    private Optional<ChatMessage> latestMessage(Long roomId) {
        List<ChatMessage> latest = chatMessageRepository.findByRoomIdBefore(roomId, null, 1);
        return latest.isEmpty() ? Optional.empty() : Optional.of(latest.get(0));
    }

    /**
     * unread 판정(FR-5): 마지막 메시지가 봇(BOT)이고 그 이후 미조회(last_read 가 없거나 최신보다 과거)면 true.
     * 마지막이 USER 면 false.
     */
    private static boolean isUnread(ChatRoom room, ChatMessage latest) {
        if (latest.getSenderType() != SenderType.BOT) {
            return false;
        }
        Long lastRead = room.getLastReadMessageId();
        return lastRead == null || lastRead < latest.getId();
    }

    /**
     * 미리보기 규칙(FR-7, 설계 §5): TEXT/SYSTEM/MEMO_PROMPT → payload text 앞 40자,
     * PLACE_CARDS → "장소 N곳", PROCESSING → "답장을 준비하고 있어요",
     * REEL_LINK → "릴스 링크", PIN_REPLY → "핀 답장", PIN_VISIT → "다녀갔어요", PIN_MEMORY → "추억"
     * (그룹 방 전용 kind — 봇 방엔 등장하지 않으나 exhaustive 커버).
     */
    private String previewOf(ChatMessage message) {
        return switch (message.getKind()) {
            case TEXT, SYSTEM, MEMO_PROMPT -> truncate(textPayload(message.getPayloadJson()));
            case PLACE_CARDS -> "장소 " + placeCardCount(message.getPayloadJson()) + "곳";
            case PROCESSING -> PREVIEW_PROCESSING;
            case REEL_LINK -> "릴스 링크";
            case PIN_REPLY -> "핀 답장";
            case PIN_VISIT -> "다녀갔어요";
            case PIN_MEMORY -> "추억";
        };
    }

    private String textPayload(String payloadJson) {
        JsonNode text = readField(payloadJson, "text");
        return text == null || text.isNull() ? "" : text.asText();
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
            log.warn("봇 방 미리보기 payload 파싱 실패, 빈 값으로 폴백: {}", e.getMessage());
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
