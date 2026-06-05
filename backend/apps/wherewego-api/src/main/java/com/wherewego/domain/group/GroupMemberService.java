package com.wherewego.domain.group;

import com.wherewego.config.env.InviteProperties;
import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GroupMemberService {

    private static final int MAX_GROUP_MEMBERS = 10;
    private static final int SLUG_GENERATION_MAX_RETRIES = 5;

    private final GroupMemberRepository groupMemberRepository;
    private final GroupRepository groupRepository;
    private final InviteLinkRepository inviteLinkRepository;
    private final BotUserMappingService botUserMappingService;
    private final InviteLinkSlugGenerator slugGenerator;
    private final UserRepository userRepository;
    private final InviteProperties inviteProperties;

    /**
     * 사용자의 최근 활성 그룹 ID 를 반환한다. 활성 그룹이 없으면 {@link Optional#empty()}.
     * <p>핸들러 측에서 empty 분기를 SimpleText 안내로 변환한다 (FR-GRP-2).</p>
     */
    @Transactional(readOnly = true)
    public Optional<Long> findLatestActiveGroupIdByUserId(Long userId) {
        return groupMemberRepository.findLatestActiveGroupIdByUserId(userId);
    }

    /**
     * 그룹 생성. 생성자(creator)를 최초 활성 멤버로 등록한다.
     * <p>GM-1: 1인 다중 활성 그룹 지원으로 1인1활성 제약(BR-1)을 해제했다 — existsActiveByUserId 사전검사 제거.
     * 새 그룹에 첫 멤버를 INSERT 하므로 group_members 제약 위반(pair/active_user/FK)이 구조적으로 발생 불가하여
     * try-catch 가 불필요하다. 예외적 위반은 전역 {@link com.wherewego.interfaces.api.ApiControllerAdvice}
     * 가 INTERNAL_ERROR 로 처리한다.</p>
     */
    @Transactional
    public GroupCreatedResult createGroup(Long userId, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 30) {
            throw new CoreException(ErrorType.GROUP_NAME_INVALID);
        }
        Group saved = groupRepository.save(Group.create(name));
        groupMemberRepository.save(GroupMember.createActive(saved.getId(), userId, Instant.now()));
        return new GroupCreatedResult(saved.getId(), saved.getName(), saved.getCreatedAt());
    }

    /**
     * 초대 링크 발급. 그룹에 대해 비관적 락을 잡고 멤버십 확인 후
     * 동일 그룹의 미수락 토큰을 일괄 만료(BR-3)한 다음 신규 UUID 토큰 + base56 slug 를 발급한다.
     * slug 충돌 시 최대 5회 재시도하며, 5회 모두 실패하면 INTERNAL_ERROR 를 던진다.
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
        InviteLink saved = saveWithSlugRetry(groupId, userId, token, now);
        return new InviteLinkIssueResult(saved.getToken(), saved.getSlug(), saved.getExpiresAt());
    }

    private InviteLink saveWithSlugRetry(Long groupId, Long userId, String token, Instant now) {
        for (int attempt = 0; attempt < SLUG_GENERATION_MAX_RETRIES; attempt++) {
            String slug = slugGenerator.generate();
            try {
                return inviteLinkRepository.save(
                        InviteLink.issue(groupId, userId, token, slug, now, inviteProperties.ttl()));
            } catch (DataIntegrityViolationException e) {
                // slug unique 제약 충돌 — 재시도. 마지막 시도에서도 실패하면 아래에서 throw.
                if (attempt == SLUG_GENERATION_MAX_RETRIES - 1) {
                    throw new CoreException(ErrorType.INTERNAL_ERROR);
                }
            }
        }
        // unreachable — loop 안에서 마지막 attempt 에 throw 함.
        throw new CoreException(ErrorType.INTERNAL_ERROR);
    }

    /**
     * 초대 링크 수락. 토큰 유효성/만료/중복 사용/자기수락 검사 후
     * 그룹 잠금 → 정원 검사 → 멤버 등록 순으로 처리한다.
     * <p>GM-1: 1인 다중 활성 그룹 지원으로 1인1활성 제약 사전검사(existsActiveByUserId)를 제거했다.
     * 기존 그룹에 INSERT 하므로 createGroup 과 달리 catch 가 필요하며, uq_group_members_pair(동일 그룹 재가입)
     * 만 {@code GROUP_REJOIN_FORBIDDEN} 으로 변환하고 그 외 위반(FK 등)은 rethrow 한다.</p>
     * 정원 도달 직후 미수락 초대를 일괄 만료하여 R-2(폐기된 초대 잔존) 를 차단한다.
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
        // 정원 검사. 동일 그룹 동시 수락은 findByIdForUpdate(group 비관락, 위)로 직렬화되므로
        //   정원 초과(동시 11번째 진입)는 발생하지 않는다 — 락 안에서 count→검사→INSERT 가 순차 보장된다.
        if (groupMemberRepository.countActiveByGroupId(group.getId()) >= MAX_GROUP_MEMBERS) {
            throw new CoreException(ErrorType.GROUP_CAPACITY_EXCEEDED);
        }
        // 토큰 1회용 보장: 조건부 원자적 UPDATE. 동시 수락 시 1건만 1 반환, 나머지 0 → ALREADY_USED.
        //   락 전 빠른 실패 체크(getAcceptedAt != null, 위)는 유지하되, 동시성은 이 UPDATE 로 직렬화한다.
        //   동일 TX 내 실행 — 이후 save 실패(rethrow) 시 롤백되어 accepted_at 도 복원된다(토큰 재사용 없음).
        if (inviteLinkRepository.markAcceptedIfPending(link.getId(), now) == 0) {
            throw new CoreException(ErrorType.INVITE_LINK_ALREADY_USED);
        }
        try {
            groupMemberRepository.save(GroupMember.createActive(group.getId(), userId, now));
        } catch (DataIntegrityViolationException e) {
            // GM-1: acceptInviteLink 는 기존 그룹에 INSERT 하므로 createGroup 과 달리 catch 필요.
            //   - uq_group_members_pair(동일 그룹 재가입) → GROUP_REJOIN_FORBIDDEN (BR-1).
            //   - 그 외(FK group_id→groups / user_id→users 위반: 동시 그룹 soft-delete 중 INSERT 등)는
            //     rethrow → 전역 ApiControllerAdvice 가 INTERNAL_ERROR 처리(REJOIN 오분류 방지).
            //   uq_group_members_active_user 는 DROP 됐으므로 이 경로에서 발생하지 않음.
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("uq_group_members_pair")) {
                throw new CoreException(ErrorType.GROUP_REJOIN_FORBIDDEN);
            }
            throw e;
        }
        // 정원 도달 시 남은 미수락 초대 일괄 만료 (R-2).
        if (groupMemberRepository.countActiveByGroupId(group.getId()) >= MAX_GROUP_MEMBERS) {
            inviteLinkRepository.expirePendingByGroupId(group.getId(), now);
        }
        return new InviteAcceptResult(group.getId(), now);
    }

    /**
     * slug 로 초대 링크 미리보기. 공개 GET by-slug API 의 진입점.
     * 만료/소진/존재하지 않음/그룹 삭제됨 모두 INVITE_LINK_NOT_FOUND 로 통일한다 (정보 노출 방지).
     */
    @Transactional(readOnly = true)
    public InviteLinkPreviewResult previewBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new CoreException(ErrorType.INVITE_LINK_NOT_FOUND);
        }
        Instant now = Instant.now();
        InviteLink link = inviteLinkRepository.findActiveBySlug(slug, now)
                .orElseThrow(() -> new CoreException(ErrorType.INVITE_LINK_NOT_FOUND));
        Group group = groupRepository.findById(link.getGroupId())
                .filter(g -> g.getDeletedAt() == null)
                .orElseThrow(() -> new CoreException(ErrorType.INVITE_LINK_NOT_FOUND));
        UserModel inviter = userRepository.findById(link.getInviterId())
                .orElseThrow(() -> new CoreException(ErrorType.INVITE_LINK_NOT_FOUND));
        return new InviteLinkPreviewResult(
                link.getToken(),
                group.getName(),
                inviter.getNickname(),
                link.getExpiresAt()
        );
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
        }
        // 탈퇴 시점에 미수락 초대 일괄 만료 (R-2): 남은 멤버가 새 초대를 발급해야 한다.
        inviteLinkRepository.expirePendingByGroupId(groupId, now);
        botUserMappingService.unlink(userId);
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
                .filter(group -> group.getDeletedAt() == null)
                .map(group -> new ActiveGroupInfo(
                        group.getId(),
                        group.getName(),
                        group.getCreatedAt(),
                        groupMemberRepository.countActiveByGroupId(group.getId())
                ));
    }

    /**
     * 사용자의 활성 그룹 목록 (GM-1, FR-4/FR-5). 가입 순(joined_at ASC) 정렬, 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    public List<GroupSummary> listMyGroups(Long userId) {
        return groupMemberRepository.listActiveGroupSummariesByUserId(userId);
    }
}
