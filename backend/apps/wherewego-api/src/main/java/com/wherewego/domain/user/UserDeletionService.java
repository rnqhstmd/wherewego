package com.wherewego.domain.user;

import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chat.ChatMessageRepository;
import com.wherewego.domain.chat.ChatRoomRepository;
import com.wherewego.domain.device.DeviceRepository;
import com.wherewego.domain.group.GroupMemberRepository;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.infrastructure.auth.apple.AppleTokenRevoker;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

/**
 * P2 PR-3: 계정 삭제 오케스트레이션(FR-21~24, AC-10~13).
 *
 * <p>UserService 에서 삭제 책임을 분리한 신규 서비스다. {@link #deleteAccount(Long)} 가
 * 그룹 탈퇴 → 봇 매핑 해제 → 채팅 정리 → 디바이스 정리 → refresh 무효화 → 사용자 soft delete
 * 를 단일 트랜잭션으로 수행하고, 외부 HTTP 가 될 수 있는 Apple revoke 만 커밋 후(afterCommit)
 * best-effort 로 분리한다(AuthService "외부 HTTP 는 트랜잭션 밖" 원칙).
 *
 * <p>식별자(oauth_id/kakao_user_id)는 변경하지 않는다 — 재가입은 V017 partial unique index 가
 * 활성(deleted_at IS NULL) 행만 강제하여 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDeletionService {

    private final UserRepository userRepository;
    private final GroupMemberService groupMemberService;
    private final GroupMemberRepository groupMemberRepository;
    private final BotUserMappingService botUserMappingService;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final DeviceRepository deviceRepository;
    private final AppleTokenRevoker appleTokenRevoker;

    /**
     * 계정을 삭제한다(soft delete). 멱등하지 않은 진입(이미 탈퇴)은 AUTH_USER_DEACTIVATED 로 차단한다.
     *
     * <p>삭제 순서(설계 계정 삭제 1~7):
     * <ol>
     *     <li>활성 사용자 조회 — 없거나 비활성이면 {@code AUTH_USER_DEACTIVATED}.</li>
     *     <li>활성 그룹만 멱등 순회 leaveGroup — 1인1활성그룹이라 0~1개라 {@code GROUP_NOT_MEMBER} 회피.
     *         leaveGroup 이 마지막 1인 그룹 soft delete + 초대 만료 + 봇 매핑 unlink 까지 수행한다.</li>
     *     <li>봇 매핑 unlink — leaveGroup 이 내부에서 unlink 를 수행하므로, 활성 그룹 0개로
     *         leaveGroup 이 안 불린 경우에만 직접 1회 호출한다(멱등 백업).
     *         MANDATORY 전파라 현재 트랜잭션에 합류한다.</li>
     *     <li>chat_message sender_user_id NULL 처리 + 본인 소유 봇 방 soft delete.</li>
     *     <li>디바이스 토큰 soft delete.</li>
     *     <li>refresh token 해시 무효화.</li>
     *     <li>사용자 soft delete(deletedAt 마킹) — 식별자 무변경.</li>
     * </ol>
     * Apple revoke 는 외부 HTTP 가 미래에 추가돼도 DB 트랜잭션을 점유하지 않도록 커밋 후 best-effort 로 호출한다.
     */
    @Transactional
    public void deleteAccount(Long userId) {
        // 1) 활성 사용자 조회 — 없거나 이미 탈퇴면 차단.
        UserModel user = userRepository.findById(userId)
                .filter(UserModel::isActive)
                .orElseThrow(() -> new CoreException(ErrorType.AUTH_USER_DEACTIVATED));

        // 2) 활성 그룹만 멱등 순회 leaveGroup (1인1활성그룹: 0~1개).
        //    조회-탈퇴 사이 race(동시 그룹 삭제/탈퇴)로 GROUP_NOT_MEMBER 가 throw 되면 deleteAccount 전체가
        //    롤백되므로, 해당 예외만 흡수하고 계속 진행한다(이미 비활성 처리된 것으로 간주).
        Optional<Long> activeGroupId = groupMemberRepository.findLatestActiveGroupIdByUserId(userId);
        boolean unlinkedViaLeaveGroup = false;
        if (activeGroupId.isPresent()) {
            Long groupId = activeGroupId.get();
            // leaveGroup 호출 전에 "내가 마지막 멤버인지" 판정: 다른 활성 멤버가 없으면(empty) 마지막 멤버이고,
            // leaveGroup이 그룹을 soft delete 한다. 이 경우 groupId의 커플 방도 함께 정리해야 고아 활성 방을 막는다.
            // 파트너가 남아 있으면(otherMembers 비어있지 않음) 그룹·커플 방은 파트너가 계속 사용하므로 유지한다.
            boolean wasLastMember = groupMemberRepository.findOtherActiveMemberIds(groupId, userId).isEmpty();
            try {
                groupMemberService.leaveGroup(userId, groupId);  // 내부에서 unlink 수행
                unlinkedViaLeaveGroup = true;
                // leaveGroup 성공 + 마지막 멤버였으면 그룹이 soft delete 됐으므로 커플 방도 정리한다.
                if (wasLastMember) {
                    chatRoomRepository.softDeleteByGroup(groupId);
                }
            } catch (CoreException e) {
                if (e.getErrorType() == ErrorType.GROUP_NOT_MEMBER) {
                    // 조회-탈퇴 사이 race(동시 그룹 삭제/탈퇴) — 이미 탈퇴 처리된 것으로 간주하고 계속
                    log.warn("계정 삭제 중 그룹 탈퇴 race — 이미 비활성 그룹/멤버 (userId={}, groupId={})", userId, groupId);
                } else {
                    throw e;  // 다른 오류는 전파
                }
            }
        }

        // 3) leaveGroup 이 호출 안 됐거나(활성 그룹 0개) race 로 throw 돼 내부 unlink 가 수행되지 않은 경우
        //    직접 unlink 한다(멱등 백업, MANDATORY 전파라 현재 트랜잭션 합류).
        if (!unlinkedViaLeaveGroup) {
            botUserMappingService.unlink(userId);
        }

        // 4) 채팅 정리: 본인 발신 메시지 sender NULL 처리 + 본인 소유 봇 방 soft delete.
        chatMessageRepository.nullifySenderByUserId(userId);
        chatRoomRepository.softDeleteByOwner(userId);

        // 5) 디바이스 토큰 soft delete.
        deviceRepository.softDeleteByUserId(userId);

        // 6) refresh token 무효화.
        user.clearRefreshTokenHash();

        // 7) 사용자 soft delete — 식별자 무변경(재가입은 V017 partial unique index 처리).
        user.delete();
        userRepository.save(user);

        // Apple revoke (best-effort): 외부 HTTP 가 트랜잭션을 점유하지 않도록 커밋 후 호출.
        registerAppleRevokeAfterCommit(user);
    }

    private void registerAppleRevokeAfterCommit(UserModel user) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    appleTokenRevoker.revoke(user);
                }
            });
        } else {
            // 트랜잭션 동기화가 비활성인 예외적 경로 — best-effort 즉시 호출.
            appleTokenRevoker.revoke(user);
        }
    }
}
