package com.wherewego.domain.group;

import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GroupMemberService {

    private static final Duration INVITE_TTL = Duration.ofHours(24);
    private static final int MAX_GROUP_MEMBERS = 2;

    private final GroupMemberRepository groupMemberRepository;
    private final GroupRepository groupRepository;
    private final InviteLinkRepository inviteLinkRepository;

    /**
     * 사용자의 최근 활성 그룹 ID 를 반환한다. 활성 그룹이 없으면 {@link Optional#empty()}.
     * <p>핸들러 측에서 empty 분기를 SimpleText 안내로 변환한다 (FR-GRP-2).</p>
     */
    @Transactional(readOnly = true)
    public Optional<Long> findLatestActiveGroupIdByUserId(Long userId) {
        return groupMemberRepository.findLatestActiveGroupIdByUserId(userId);
    }

    /**
     * 그룹 생성. 1인 1활성 그룹 제약 (BR-1) 사전 검사 후 생성자(creator)를 최초 활성 멤버로 등록한다.
     * TOCTOU 경쟁 조건은 group_members partial UNIQUE 제약으로 차단되어
     * {@link DataIntegrityViolationException} → GROUP_ALREADY_ACTIVE 로 변환한다.
     */
    @Transactional
    public GroupCreatedResult createGroup(Long userId, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 30) {
            throw new CoreException(ErrorType.GROUP_NAME_INVALID);
        }
        if (groupMemberRepository.existsActiveByUserId(userId)) {
            throw new CoreException(ErrorType.GROUP_ALREADY_ACTIVE);
        }
        Group saved = groupRepository.save(Group.create(name));
        try {
            groupMemberRepository.save(
                    GroupMember.createActive(saved.getId(), userId, Instant.now()));
        } catch (DataIntegrityViolationException e) {
            throw new CoreException(ErrorType.GROUP_ALREADY_ACTIVE);
        }
        return new GroupCreatedResult(saved.getId(), saved.getName(), saved.getCreatedAt());
    }

    /**
     * 초대 링크 발급. 그룹에 대해 비관적 락을 잡고 멤버십 확인 후
     * 동일 그룹의 미수락 토큰을 일괄 만료(BR-3)한 다음 신규 UUID 토큰을 발급한다.
     */
    @Transactional
    public InviteLinkIssueResult issueInviteLink(Long userId, Long groupId) {
        Instant now = Instant.now();
        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new CoreException(ErrorType.GROUP_NOT_MEMBER));
        if (group.getDeletedAt() != null) {
            throw new CoreException(ErrorType.GROUP_NOT_MEMBER);
        }
        requireActiveMembership(userId, groupId);
        inviteLinkRepository.expirePendingByGroupId(groupId, now);
        String token = UUID.randomUUID().toString();
        InviteLink saved = inviteLinkRepository.save(
                InviteLink.issue(groupId, userId, token, now, INVITE_TTL));
        return new InviteLinkIssueResult(saved.getToken(), saved.getExpiresAt());
    }

    /**
     * 초대 링크 수락. 토큰 유효성/만료/중복 사용/자기수락 검사 후
     * 그룹 잠금 → 정원 검사 → 멤버 등록 순으로 처리한다.
     * partial UNIQUE 충돌은 GROUP_ALREADY_ACTIVE 로 변환한다.
     */
    @Transactional
    public InviteAcceptResult acceptInviteLink(Long userId, String token) {
        Instant now = Instant.now();
        InviteLink link = inviteLinkRepository.findByToken(token)
                .orElseThrow(() -> new CoreException(ErrorType.INVITE_LINK_NOT_FOUND));
        if (link.getAcceptedAt() != null) {
            throw new CoreException(ErrorType.INVITE_LINK_ALREADY_USED);
        }
        if (link.isExpired(now)) {
            throw new CoreException(ErrorType.INVITE_LINK_EXPIRED);
        }
        if (link.getInviterId().equals(userId)) {
            throw new CoreException(ErrorType.INVITE_LINK_SELF_ACCEPT);
        }
        Group group = groupRepository.findByIdForUpdate(link.getGroupId())
                .orElseThrow(() -> new CoreException(ErrorType.INVITE_LINK_NOT_FOUND));
        if (group.getDeletedAt() != null) {
            throw new CoreException(ErrorType.INVITE_LINK_EXPIRED);
        }
        if (groupMemberRepository.existsActiveByUserId(userId)) {
            throw new CoreException(ErrorType.GROUP_ALREADY_ACTIVE);
        }
        if (groupMemberRepository.countActiveByGroupId(group.getId()) >= MAX_GROUP_MEMBERS) {
            throw new CoreException(ErrorType.GROUP_CAPACITY_EXCEEDED);
        }
        link.markAccepted(now);
        try {
            groupMemberRepository.save(GroupMember.createActive(group.getId(), userId, now));
        } catch (DataIntegrityViolationException e) {
            throw new CoreException(ErrorType.GROUP_ALREADY_ACTIVE);
        }
        return new InviteAcceptResult(group.getId(), now);
    }

    /**
     * 그룹 탈퇴. 활성 멤버십을 leftAt 마킹으로 종료하고,
     * 마지막 멤버 탈퇴 시 그룹을 soft delete + 미수락 토큰 일괄 만료한다.
     */
    @Transactional
    public void leaveGroup(Long userId, Long groupId) {
        Instant now = Instant.now();
        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new CoreException(ErrorType.GROUP_NOT_MEMBER));
        if (group.getDeletedAt() != null) {
            throw new CoreException(ErrorType.GROUP_NOT_MEMBER);
        }
        GroupMember member = groupMemberRepository
                .findActiveByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new CoreException(ErrorType.GROUP_NOT_MEMBER));
        member.markLeft(now);
        groupMemberRepository.save(member);
        long remaining = groupMemberRepository.countActiveByGroupId(groupId);
        if (remaining == 0) {
            group.markDeleted();
            groupRepository.save(group);
            inviteLinkRepository.expirePendingByGroupId(groupId, now);
        }
    }

    /**
     * 활성 멤버십 강제 확인. 비멤버이면 GROUP_NOT_MEMBER 발생.
     * 호출 트랜잭션이 있으면 동일 트랜잭션에서 실행 (REQUIRED).
     */
    @Transactional(readOnly = true)
    public void requireActiveMembership(Long userId, Long groupId) {
        if (groupMemberRepository.findActiveByGroupIdAndUserId(groupId, userId).isEmpty()) {
            throw new CoreException(ErrorType.GROUP_NOT_MEMBER);
        }
    }

    /**
     * 사용자의 현재 활성 그룹 메타 정보. 미가입이면 empty.
     */
    @Transactional(readOnly = true)
    public Optional<ActiveGroupInfo> findMyActiveGroup(Long userId) {
        return groupMemberRepository.findLatestActiveGroupIdByUserId(userId)
                .flatMap(groupRepository::findById)
                .map(group -> new ActiveGroupInfo(group.getId(), group.getName(), group.getCreatedAt()));
    }
}
