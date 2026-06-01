package com.wherewego.domain.chat;

import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * P2 PR-1: 앱 봇 방 메시지 전송 서비스(FR-4/BR-4).
 *
 * <p>요청 트랜잭션(TX-A)에서 봇 방 보장 → 사용자 메시지(USER/TEXT) → PROCESSING 플레이스홀더를 append하고,
 * PROCESSING을 즉시 반환한다(컨트롤러가 {@code {messageId, kind:PROCESSING}}로 응답). 실제 1턴 처리는
 * {@link BotChatProcessor#processAsync}가 PROCESSING <b>커밋 후</b> 별도 비동기 트랜잭션(TX-B/TX-C)에서
 * 수행한다 — afterCommit 트리거로 read-after-write 일관성을 보장한다.</p>
 */
@Service
@RequiredArgsConstructor
public class BotChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageAppender appender;
    private final BotChatProcessor botChatProcessor;

    /**
     * 봇 방에 사용자 메시지를 전송하고 PROCESSING 플레이스홀더를 반환한다.
     *
     * <p>봇 방은 사용자당 활성 1개(V015 부분 UNIQUE)로 보장된다 — 활성 방이 없으면 생성하며,
     * 동시성은 DB 인덱스가 보호한다. PROCESSING 커밋 후 {@link BotChatProcessor#processAsync}를
     * afterCommit으로 트리거한다(다른 빈 호출이라 @Async 프록시 정상 동작).</p>
     *
     * @param userId 봇 방 소유자
     * @param text   사용자 입력 원문(인스타 URL 후보)
     * @return PROCESSING 플레이스홀더 메시지(컨트롤러가 messageId/kind 매핑에 사용)
     */
    @Transactional
    public ChatMessage postMessage(Long userId, String text) {
        ChatRoom room = ensureBotRoom(userId);
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
     * 봇 방 메시지를 cursor 기반 최신순(id DESC)으로 페이지 조회한다(FR-5, AC-3).
     *
     * <p>활성 봇 방이 없으면(아직 한 번도 메시지를 보낸 적 없는 사용자) 빈 페이지를 반환한다(AC-3).
     * 방이 있으면 {@code limit + 1}개를 조회하여 {@link ChatMessagePageResult#of}로 hasMore를 판정한다.</p>
     *
     * @param userId 봇 방 소유자
     * @param cursor 이 id 미만(과거)만 조회. {@code null}이면 최신부터.
     * @param limit  페이지 크기(컨트롤러에서 1~50으로 클램프하여 전달)
     * @return cursor 페이지 결과. 방이 없으면 {@code {[], false, null}}.
     */
    @Transactional(readOnly = true)
    public ChatMessagePageResult getBotMessages(Long userId, Long cursor, int limit) {
        return chatRoomRepository.findActiveBotRoom(userId)
                .map(room -> ChatMessagePageResult.of(
                        chatMessageRepository.findByRoomIdBefore(room.getId(), cursor, limit + 1),
                        limit))
                .orElseGet(() -> new ChatMessagePageResult(List.of(), false, null));
    }

    /**
     * 활성 봇 방을 보장한다. 없으면 생성한다(부분 UNIQUE라 동시성은 DB가 보호).
     *
     * <p>동시 진입 시 두 트랜잭션이 모두 활성 방을 못 찾고 동시에 save하면 V015 부분 UNIQUE 위반
     * ({@link DataIntegrityViolationException})이 발생한다 — 패자는 이를 잡아 승자가 만든 행을 재조회하여 반환한다
     * (GroupMemberService의 optimistic insert + conflict 폴백 패턴과 동일). 재조회도 비면 INTERNAL_ERROR.</p>
     */
    private ChatRoom ensureBotRoom(Long userId) {
        return chatRoomRepository.findActiveBotRoom(userId)
                .orElseGet(() -> saveBotRoomOnConflict(userId));
    }

    private ChatRoom saveBotRoomOnConflict(Long userId) {
        try {
            return chatRoomRepository.save(ChatRoom.createBotRoom(userId));
        } catch (DataIntegrityViolationException e) {
            // 동시 생성 충돌 — 승자가 만든 활성 방을 재조회하여 반환.
            return chatRoomRepository.findActiveBotRoom(userId)
                    .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "방 생성 충돌"));
        }
    }
}
