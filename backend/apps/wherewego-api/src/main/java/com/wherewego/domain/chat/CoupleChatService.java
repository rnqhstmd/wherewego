package com.wherewego.domain.chat;

import com.wherewego.domain.group.GroupMemberRepository;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.push.PushNotificationService;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * P2: 커플 방(1:1) 메시지 전송 서비스(FR-10). 봇은 개입하지 않으며 저장 + 상대 멤버 APNs 푸시만 한다.
 * (채팅 이벤트 전환: STOMP 실시간 발행 제거 — 실시간 표시는 클라이언트 폴링/포그라운드 재조회/푸시가 담당.)
 *
 * <p>흐름: 활성 멤버십 검증(BR-3/AC-5, 비멤버 → GROUP_NOT_MEMBER 403) → 활성 커플 방 확보(BR-2 부분 UNIQUE)
 * → 사용자 텍스트 append → 트랜잭션 <b>커밋 후(afterCommit)</b> 상대 멤버에게 APNs 푸시.</p>
 *
 * <p>상대 멤버가 없는 1인 그룹이면 푸시 대상이 없으므로 푸시를 생략하고 저장만 한다(BR-5/AC-15).
 * 푸시는 단방향 best-effort이며 실패해도 호출자에게 전파하지 않는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoupleChatService {

    private final GroupMemberService groupMemberService;
    private final GroupMemberRepository groupMemberRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageAppender chatMessageAppender;
    private final PushNotificationService pushNotificationService;

    /**
     * 커플 방에 사용자 텍스트 메시지를 전송한다.
     *
     * @param userId  발신 사용자 ID
     * @param groupId 대상 커플 그룹 ID
     * @param text    메시지 본문
     * @return 저장된 {@link ChatMessage}(컨트롤러가 응답으로 매핑)
     * @throws com.wherewego.support.error.CoreException 비활성 멤버이면 GROUP_NOT_MEMBER(403)
     */
    @Transactional
    public ChatMessage postCoupleMessage(Long userId, Long groupId, String text) {
        groupMemberService.requireActiveMembership(userId, groupId);

        ChatRoom room = ensureCoupleRoom(groupId);
        ChatMessage saved = chatMessageAppender.appendCoupleText(room.getId(), userId, text);

        broadcastToOthersAfterCommit(groupId, userId, saved);
        return saved;
    }

    /**
     * 커플 방 메시지를 cursor 기반 최신순(id DESC)으로 페이지 조회한다(FR-9, AC-3).
     *
     * <p><b>조회에도 멤버십 검증을 강제한다</b>(타 그룹 메시지 차단): 비활성 멤버이면
     * {@code requireActiveMembership}이 GROUP_NOT_MEMBER(403)를 던진다. 활성 커플 방이 아직 없으면
     * 빈 페이지를 반환한다(AC-3). 방이 있으면 {@code limit + 1}개를 조회하여 hasMore를 판정한다.</p>
     *
     * @param userId  조회 사용자 ID
     * @param groupId 대상 커플 그룹 ID
     * @param cursor  이 id 미만(과거)만 조회. {@code null}이면 최신부터.
     * @param limit   페이지 크기(컨트롤러에서 1~50으로 클램프하여 전달)
     * @return cursor 페이지 결과. 방이 없으면 {@code {[], false, null}}.
     * @throws com.wherewego.support.error.CoreException 비활성 멤버이면 GROUP_NOT_MEMBER(403)
     */
    @Transactional(readOnly = true)
    public ChatMessagePageResult getCoupleMessages(Long userId, Long groupId, Long cursor, int limit) {
        groupMemberService.requireActiveMembership(userId, groupId);

        return chatRoomRepository.findActiveCoupleRoom(groupId)
                .map(room -> ChatMessagePageResult.of(
                        chatMessageRepository.findByRoomIdBefore(room.getId(), cursor, limit + 1),
                        limit))
                .orElseGet(() -> new ChatMessagePageResult(List.of(), false, null));
    }

    /**
     * 활성 커플 방을 확보한다. 없으면 신규 생성하여 저장한다(BR-2: 부분 UNIQUE 인덱스가 활성 1개 강제).
     *
     * <p>동시 진입 시 두 트랜잭션이 모두 활성 방을 못 찾고 동시에 save하면 부분 UNIQUE 위반
     * ({@link DataIntegrityViolationException})이 발생한다 — 패자는 이를 잡아 승자가 만든 행을 재조회하여 반환한다
     * (GroupMemberService의 optimistic insert + conflict 폴백 패턴과 동일). 재조회도 비면 INTERNAL_ERROR.</p>
     */
    private ChatRoom ensureCoupleRoom(Long groupId) {
        return chatRoomRepository.findActiveCoupleRoom(groupId)
                .orElseGet(() -> saveCoupleRoomOnConflict(groupId));
    }

    private ChatRoom saveCoupleRoomOnConflict(Long groupId) {
        try {
            return chatRoomRepository.save(ChatRoom.createCoupleRoom(groupId));
        } catch (DataIntegrityViolationException e) {
            // 동시 생성 충돌 — 승자가 만든 활성 방을 재조회하여 반환.
            return chatRoomRepository.findActiveCoupleRoom(groupId)
                    .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "방 생성 충돌"));
        }
    }

    /**
     * 상대 활성 멤버가 존재할 때만 커밋 후 APNs 푸시를 등록한다(BR-5/AC-15).
     *
     * <p>1인 그룹(상대 없음)이면 푸시 대상이 없으므로 생략한다. 상대 멤버 각각에게 roomId 기준으로
     * 푸시한다(실시간 표시는 클라이언트 폴링/포그라운드 재조회가 담당 — STOMP 발행 제거).</p>
     */
    private void broadcastToOthersAfterCommit(Long groupId, Long userId, ChatMessage saved) {
        List<Long> otherMemberIds = groupMemberRepository.findOtherActiveMemberIds(groupId, userId);
        if (otherMemberIds.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // FR-17②: 커밋 후 상대 멤버 각각에게 APNs 푸시(best-effort). roomId는 저장된 메시지 기준.
                // 실시간 표시는 클라이언트의 전송 직후 폴링/포그라운드 복귀 재조회가 담당(STOMP 발행 제거).
                for (Long partnerUserId : otherMemberIds) {
                    pushNotificationService.pushCoupleMessage(partnerUserId, saved.getRoomId());
                }
            }
        });
    }
}
