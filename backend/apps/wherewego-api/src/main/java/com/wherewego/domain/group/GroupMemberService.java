package com.wherewego.domain.group;

import com.wherewego.config.env.InviteProperties;
import com.wherewego.config.env.S3Properties;
import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chat.ChatRoom;
import com.wherewego.domain.chat.ChatRoomRepository;
import com.wherewego.domain.image.AvatarStorage;
import com.wherewego.domain.image.AvatarStorage.StoredAvatar;
import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GroupMemberService {

    // GP-1 FR-8: 그룹 정원 8. >= 검사라 기존 9~10명 그룹은 신규 가입만 자동 차단(강제 퇴장 없음).
    private static final int MAX_GROUP_MEMBERS = 8;
    private static final int SLUG_GENERATION_MAX_RETRIES = 5;

    private final GroupMemberRepository groupMemberRepository;
    private final GroupRepository groupRepository;
    private final InviteLinkRepository inviteLinkRepository;
    private final BotUserMappingService botUserMappingService;
    private final InviteLinkSlugGenerator slugGenerator;
    private final UserRepository userRepository;
    private final InviteProperties inviteProperties;
    private final ChatRoomRepository chatRoomRepository;
    private final S3Properties s3Properties;
    private final AvatarStorage avatarStorage;

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
        // GC-1(FR-GC1-1): 그룹 채팅방을 그룹 생성 시 함께 생성한다(기존 그룹은 V021 백필,
        // 누락 시 GroupChatService 의 get-or-create 안전망). 새 그룹이라 부분 UNIQUE 충돌이 구조적으로 없다.
        chatRoomRepository.save(ChatRoom.createGroupRoom(saved.getId()));
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

    /**
     * 그룹의 현재 활성(미만료) 초대 링크 조회(IC-2 후속, 읽기 전용). 발급(재발급)과 달리 새 코드를 만들지 않으므로
     * '그룹관리 진입 시 코드 자동 표시' 에서 반복 호출해도 기존 공유 코드가 만료되지 않는다(BR-3 회피).
     * 활성 코드가 없으면 empty(클라이언트는 '초대 코드 만들기' 노출). 비멤버는 GROUP_NOT_MEMBER 로 거부.
     */
    @Transactional(readOnly = true)
    public Optional<InviteLinkIssueResult> currentInviteLink(Long userId, Long groupId) {
        requireActiveMembership(userId, groupId);
        return inviteLinkRepository.findActiveByGroupId(groupId, Instant.now())
                .map(link -> new InviteLinkIssueResult(link.getToken(), link.getSlug(), link.getExpiresAt()));
    }

    /**
     * slug 충돌은 사전 존재검사(unique 인덱스 술어와 동일 범위)로 회피하고 재생성한다(PR #118 리뷰 반영).
     *
     * <p>기존 save+catch(DataIntegrityViolationException) 재시도는 참여 트랜잭션이 rollback-only 로
     * 마킹되어 재시도가 성공해도 커밋이 불가능한 결함이 있었다. 검사 통과 후 동시 발급 race 로 남는
     * 잔존 충돌(base56 8자 공간에서 사실상 0)은 전역 INTERNAL_ERROR 로 종결된다 — 기존 최종 실패와 동일 의미.</p>
     */
    private InviteLink saveWithSlugRetry(Long groupId, Long userId, String token, Instant now) {
        for (int attempt = 0; attempt < SLUG_GENERATION_MAX_RETRIES; attempt++) {
            String slug = slugGenerator.generate();
            if (inviteLinkRepository.existsActiveSlug(slug)) {
                // 기존 행과 충돌 — 새 slug 로 재생성.
                continue;
            }
            return inviteLinkRepository.save(
                    InviteLink.issue(groupId, userId, token, slug, now, inviteProperties.ttl()));
        }
        throw new CoreException(ErrorType.INTERNAL_ERROR);
    }

    /**
     * 초대 링크 수락. 토큰 유효성/만료/자기수락 검사 후
     * 그룹 잠금 → 중복 멤버 가드 → 정원 검사 → 멤버 등록 순으로 처리한다.
     * <p>IC-1: 1회용 소진(accepted_at) 시맨틱을 제거하여 코드는 TTL 동안 정원 한도 내에서 복수 사용자가 재사용한다(FR-1).
     * 정원 도달은 '만료'가 아니라 '가입 차단'이라 코드는 TTL 까지 유지하며, 정원 도달 후처리(expirePendingByGroupId)를
     * 호출하지 않는다(Option A) — 이로써 by-slug 가 코드를 찾아 GROUP_CAPACITY_EXCEEDED 를 구분 응답할 수 있다(D4).</p>
     * <p>중복 멤버 가드: 정원 검사 앞에서 findActiveByGroupIdAndUserId 사전검사로 이미 활성 멤버이면
     * {@code GROUP_ALREADY_MEMBER} 를 던진다(FR-3). race 로 가드를 통과한 동시 INSERT 의 uq_group_members_pair
     * 위반은 catch 에서 {@code GROUP_REJOIN_FORBIDDEN} 으로 변환한다(탈퇴 재가입 차단, BR-4 안전망).</p>
     * <p>동시성: findByIdForUpdate(group 비관락)로 직렬화되어 정원 초과 INSERT 가 차단된다(AC-8).</p>
     */
    @Transactional
    public InviteAcceptResult acceptInviteLink(Long userId, String token) {
        Instant now = Instant.now();
        InviteLink link = inviteLinkRepository.findByToken(token)
                .orElseThrow(() -> new CoreException(ErrorType.INVITE_LINK_NOT_FOUND));
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
        // 중복 멤버 사전 가드(FR-3): 이미 활성 멤버이면 가입 처리 없이 GROUP_ALREADY_MEMBER. 정원 검사 앞에 둔다.
        if (groupMemberRepository.findActiveByGroupIdAndUserId(group.getId(), userId).isPresent()) {
            throw new CoreException(ErrorType.GROUP_ALREADY_MEMBER);
        }
        // 정원 검사. 동일 그룹 동시 수락은 findByIdForUpdate(group 비관락, 위)로 직렬화되므로
        //   정원 초과(동시 11번째 진입)는 발생하지 않는다 — 락 안에서 count→검사→INSERT 가 순차 보장된다.
        //   IC-1: 1회용 토큰 소진이 사라져 정원 검사 직렬화가 유일한 동시성 방어선이다(BR-4).
        if (groupMemberRepository.countActiveByGroupId(group.getId()) >= MAX_GROUP_MEMBERS) {
            throw new CoreException(ErrorType.GROUP_CAPACITY_EXCEEDED);
        }
        try {
            groupMemberRepository.save(GroupMember.createActive(group.getId(), userId, now));
        } catch (DataIntegrityViolationException e) {
            // IC-1: 기존 그룹에 INSERT 하므로 catch 필요.
            //   - uq_group_members_pair(탈퇴 후 동일 그룹 재가입) → GROUP_REJOIN_FORBIDDEN (D5).
            //     사전 가드를 race 로 통과한 동시 INSERT 의 잔존 안전망이기도 하다.
            //   - 그 외(FK group_id→groups / user_id→users 위반: 동시 그룹 soft-delete 중 INSERT 등)는
            //     rethrow → 전역 ApiControllerAdvice 가 INTERNAL_ERROR 처리(REJOIN 오분류 방지).
            //   uq_group_members_active_user 는 DROP 됐으므로 이 경로에서 발생하지 않음.
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("uq_group_members_pair")) {
                throw new CoreException(ErrorType.GROUP_REJOIN_FORBIDDEN);
            }
            throw e;
        }
        // IC-1(Option A): 정원 도달 후처리(expirePendingByGroupId)를 호출하지 않는다.
        //   정원 도달은 만료가 아니라 가입 차단이며, 코드는 TTL 까지 유지되어 by-slug 가
        //   count>=MAX_GROUP_MEMBERS 로 GROUP_CAPACITY_EXCEEDED 를 구분 응답할 수 있게 한다(D4).
        return new InviteAcceptResult(group.getId(), now);
    }

    /**
     * slug 로 초대 링크 미리보기. 공개 GET by-slug API 의 진입점.
     * 만료/존재하지 않음/그룹 삭제됨 모두 INVITE_LINK_NOT_FOUND 로 통일한다 (정보 노출 방지).
     * <p>IC-1(D4): 코드가 유효(TTL 미만료)하되 그룹 정원(8) 도달이면 NOT_FOUND 가 아니라
     * GROUP_CAPACITY_EXCEEDED 로 구분 응답한다 — IC-3 웹 랜딩의 "정원 가득" vs "만료" 안내용.</p>
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
        // IC-1(D4): 유효 코드 + 정원 도달은 GROUP_CAPACITY_EXCEEDED 로 구분(만료/없음의 NOT_FOUND 와 별도).
        if (groupMemberRepository.countActiveByGroupId(group.getId()) >= MAX_GROUP_MEMBERS) {
            throw new CoreException(ErrorType.GROUP_CAPACITY_EXCEEDED);
        }
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
        // GM-1: 봇 매핑은 group 무관 user 단위다. 다중 활성 그룹 사용자가 1개 그룹만 탈퇴하면
        //   봇 연동을 끊지 않고(잔여 그룹에서 챗봇 계속 사용), 마지막 활성 그룹 탈퇴(잔여 0개)일 때만 unlink.
        if (groupMemberRepository.listActiveGroupIdsByUserId(userId).isEmpty()) {
            botUserMappingService.unlink(userId);
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
     * <p>GP-1: 리포지토리 projection 은 그룹 대표 이미지의 S3 <b>키</b>를 담아 오므로, 여기서 공개 URL 로
     * 치환한다(PinService.toPublicUrl 와 동일한 "서비스가 변환" 패턴).</p>
     */
    @Transactional(readOnly = true)
    public List<GroupSummary> listMyGroups(Long userId) {
        List<GroupSummary> rows = groupMemberRepository.listActiveGroupSummariesByUserId(userId);
        List<GroupSummary> result = new ArrayList<>(rows.size());
        for (GroupSummary row : rows) {
            result.add(new GroupSummary(
                    row.groupId(),
                    row.name(),
                    row.createdAt(),
                    row.memberCount(),
                    toPublicUrl(row.imageUrl()),
                    toPublicUrl(row.imageThumbUrl())));
        }
        return result;
    }

    /**
     * 내 그룹 목록 + 멤버 프리뷰 조립 (GP-1 FR-4, 컨트롤러용). 그룹 목록(이미지 URL 포함)을 가져온 뒤,
     * 해당 그룹들의 활성 멤버 아바타를 IN 쿼리 1회로 묶어({@link #previewMembersByGroupIds}) 그룹별로 합친다.
     * <p>채팅 방 목록({@code GroupChatService.getRooms})은 멤버 프리뷰가 불필요하므로 기존 {@link #listMyGroups}
     * (그룹 요약만)를 그대로 소비한다 — 채팅 응답 무변경(설계 §1.5). 멤버가 없는 그룹(이론상)은 빈 리스트로 둔다.</p>
     */
    @Transactional(readOnly = true)
    public List<GroupListItem> listMyGroupsWithMembers(Long userId) {
        List<GroupSummary> summaries = listMyGroups(userId);
        if (summaries.isEmpty()) {
            return List.of();
        }
        List<Long> groupIds = summaries.stream().map(GroupSummary::groupId).toList();
        Map<Long, List<GroupMemberPreview>> membersByGroup = previewMembersByGroupIds(groupIds);
        List<GroupListItem> result = new ArrayList<>(summaries.size());
        for (GroupSummary summary : summaries) {
            result.add(new GroupListItem(
                    summary,
                    membersByGroup.getOrDefault(summary.groupId(), List.of())));
        }
        return result;
    }

    /**
     * 여러 그룹의 멤버 프리뷰(가입순 아바타)를 IN 쿼리 1회로 조회해 그룹별로 그룹핑한다 (GP-1).
     * <p>각 멤버의 {@code profileImageUrl} 은 유효 프사 URL 규칙(프사 썸네일 키 → 카카오 URL 폴백 → null)을
     * 적용한 값이다. 반환 맵의 순서/리스트 순서 모두 가입순(joined_at ASC, id ASC)을 보존한다.
     * 활성 그룹별로 항상 엔트리를 두기 위해 빈 입력이 아닌 한 멤버가 없는 그룹은 호출자가 빈 리스트로 처리한다.</p>
     */
    @Transactional(readOnly = true)
    public Map<Long, List<GroupMemberPreview>> previewMembersByGroupIds(java.util.Collection<Long> groupIds) {
        List<GroupMemberAvatarRow> rows = groupMemberRepository.listActiveMembersByGroupIds(groupIds);
        Map<Long, List<GroupMemberPreview>> grouped = new LinkedHashMap<>();
        for (GroupMemberAvatarRow row : rows) {
            grouped.computeIfAbsent(row.groupId(), k -> new ArrayList<>())
                    .add(new GroupMemberPreview(
                            row.userId(),
                            row.nickname(),
                            effectiveProfileImageUrl(row.profileImageThumbKey(), row.profileImageUrl())));
        }
        return grouped;
    }

    /**
     * 유효 프사 URL 규칙(GP-1): 프사 썸네일 키가 있으면 그 공개 URL, 없으면 카카오 profileImageUrl 폴백,
     * 둘 다 없으면 null. UserRepository.findProfilesByIds 의 규칙과 동일하다.
     */
    private String effectiveProfileImageUrl(String thumbKey, String kakaoUrl) {
        if (thumbKey != null && !thumbKey.isBlank()) {
            return toPublicUrl(thumbKey);
        }
        return kakaoUrl;
    }

    /**
     * S3 객체 키 → 공개 URL 조합 (PinService.toPublicUrl 동일). 키가 없으면 null.
     * publicBaseUrl 끝 슬래시를 제거해 "//" 이중 슬래시 broken URL 을 방지한다.
     * 끝 슬래시 제거 결과는 불변(설정값)이라 1회 계산 후 캐싱한다(목록 조회 대량 호출 — PR#123 리뷰).
     */
    private volatile String cachedPublicUrlBase;

    private String toPublicUrl(String key) {
        if (key == null) return null;
        String base = cachedPublicUrlBase;
        if (base == null) {
            base = s3Properties.publicBaseUrl().replaceAll("/+$", "");
            cachedPublicUrlBase = base;   // 동시 진입해도 같은 값 — 멱등이라 락 불필요.
        }
        return base + "/" + key;
    }

    /**
     * 이전 아바타 객체를 트랜잭션 커밋 후 best-effort 회수한다(PR#123 리뷰 — DB/S3 정합).
     * 트랜잭션 내에서 즉시 삭제하면 롤백 시 DB 키는 남고 S3 객체만 사라져 깨진 링크가 된다.
     * 활성 트랜잭션이 없으면(테스트 등) 즉시 삭제로 폴백.
     */
    private void deleteAvatarAfterCommit(String imageKey, String thumbKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    avatarStorage.deleteQuietly(imageKey, thumbKey);
                }
            });
        } else {
            avatarStorage.deleteQuietly(imageKey, thumbKey);
        }
    }

    /**
     * 그룹원 목록 조회 (GM-2 그룹관리). 활성 멤버만 접근 가능(비멤버 GROUP_NOT_MEMBER).
     * 정렬(joined_at ASC, id ASC)된 첫 항목 = 방장(owner)으로 마킹한다 — 별도 owner 컬럼 없이
     * 조회 시점 계산이라 방장 탈퇴 시 다음 최선임이 자동 승계된다.
     */
    @Transactional(readOnly = true)
    public List<GroupMemberResult> listMembers(Long userId, Long groupId) {
        requireActiveMembership(userId, groupId);
        List<GroupMemberInfo> members = groupMemberRepository.listActiveMembersByGroupId(groupId);
        List<GroupMemberResult> results = new java.util.ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            GroupMemberInfo m = members.get(i);
            // GP-1 FR-9: 유효 프사 URL(thumb 키 우선 → 카카오 URL 폴백 → null)을 응답에 합성한다.
            results.add(new GroupMemberResult(
                    m.userId(),
                    m.nickname(),
                    m.joinedAt(),
                    i == 0,
                    effectiveProfileImageUrl(m.profileImageThumbKey(), m.profileImageUrl())));
        }
        return results;
    }

    /**
     * 그룹 대표 이미지 업로드/교체 (GP-1 FR-1/FR-2/BR-2). 권한은 활성 멤버(그룹명 수정과 동일 경로),
     * findByIdForUpdate 로 락을 잡아 삭제/탈퇴/이름수정과 직렬화한다.
     * <p>검증된 원본 bytes → S3 저장({@code groups/{groupId}/avatar}) → 이전 키 백업 → {@code updateImage} →
     * 이전 키가 있었으면 best-effort 회수(교체 시 고아 방지) → 갱신 URL 반환. imageBytes/contentType 은
     * 컨트롤러({@code ImageUploadGuard})가 타입/크기/매직을 검증한 값이며, 픽셀 상한은 어댑터가 검증한다.</p>
     */
    @Transactional
    public GroupImageResult updateGroupImage(Long userId, Long groupId, byte[] imageBytes, String contentType) {
        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new CoreException(ErrorType.GROUP_NOT_MEMBER));
        if (group.getDeletedAt() != null) {
            throw new CoreException(ErrorType.GROUP_NOT_MEMBER);
        }
        requireActiveMembership(userId, groupId);

        String oldImageKey = group.getImageKey();
        String oldThumbKey = group.getImageThumbKey();
        boolean hadImage = oldImageKey != null;

        StoredAvatar stored = avatarStorage.store("groups/" + groupId + "/avatar", imageBytes, contentType);
        group.updateImage(stored.imageKey(), stored.thumbKey());
        groupRepository.save(group);

        if (hadImage) {
            // 교체: 기존 객체 best-effort 회수(실패해도 새 이미지는 유효, BR-3). 커밋 후 실행(롤백 시 보존).
            deleteAvatarAfterCommit(oldImageKey, oldThumbKey);
        }
        return new GroupImageResult(toPublicUrl(stored.imageKey()), toPublicUrl(stored.thumbKey()));
    }

    /**
     * 그룹 대표 이미지 제거 (GP-1 FR-2/Q4). 권한은 활성 멤버(그룹명 수정과 동일). 키를 비운 뒤 S3 2객체를
     * best-effort 삭제한다. 이미지가 없던 그룹은 멱등 성공(S3 호출 불필요). 응답은 두 URL 모두 null.
     */
    @Transactional
    public GroupImageResult clearGroupImage(Long userId, Long groupId) {
        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new CoreException(ErrorType.GROUP_NOT_MEMBER));
        if (group.getDeletedAt() != null) {
            throw new CoreException(ErrorType.GROUP_NOT_MEMBER);
        }
        requireActiveMembership(userId, groupId);

        String oldImageKey = group.getImageKey();
        if (oldImageKey == null) {
            return new GroupImageResult(null, null); // 이미지 없는 그룹: 멱등 성공.
        }
        String oldThumbKey = group.getImageThumbKey();
        group.clearImage();
        groupRepository.save(group);
        // 커밋 후 회수(PR#123 리뷰) — 롤백 시 키가 남으므로 객체도 보존돼야 정합.
        deleteAvatarAfterCommit(oldImageKey, oldThumbKey);
        return new GroupImageResult(null, null);
    }

    /**
     * 그룹명 수정 (GM-2 그룹관리). 권한은 활성 멤버(모든 멤버, 방장 제한 없음).
     * findByIdForUpdate 로 락을 잡아 삭제/탈퇴와 직렬화하고, 검증은 createGroup 과 동일하다.
     */
    @Transactional
    public void renameGroup(Long userId, Long groupId, String rawName) {
        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new CoreException(ErrorType.GROUP_NOT_MEMBER));
        if (group.getDeletedAt() != null) {
            throw new CoreException(ErrorType.GROUP_NOT_MEMBER);
        }
        requireActiveMembership(userId, groupId);
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 30) {
            throw new CoreException(ErrorType.GROUP_NAME_INVALID);
        }
        group.rename(name);
        groupRepository.save(group);
    }

    /**
     * 그룹 삭제 (GM-2 그룹관리). 방장(joined_at 최소)만 가능(비방장 GROUP_OWNER_REQUIRED).
     * findByIdForUpdate 로 락을 잡아 탈퇴/이름수정과 직렬화한다.
     * 전원 markLeft + group soft delete + 미수락 초대 일괄 만료 후,
     * 각 멤버의 잔여 활성 그룹이 0개이면 봇 매핑을 해제한다 (leaveGroup 패턴 확장, R-2).
     */
    @Transactional
    public void deleteGroup(Long userId, Long groupId) {
        Instant now = Instant.now();
        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new CoreException(ErrorType.GROUP_NOT_MEMBER));
        if (group.getDeletedAt() != null) {
            throw new CoreException(ErrorType.GROUP_NOT_MEMBER);
        }
        List<GroupMemberInfo> members = groupMemberRepository.listActiveMembersByGroupId(groupId);
        // 방장 = 정렬된 첫 항목(joined_at 최소). 빈 목록(이론상 비활성)이면 비멤버로 차단.
        if (members.isEmpty() || !members.get(0).userId().equals(userId)) {
            throw new CoreException(ErrorType.GROUP_OWNER_REQUIRED);
        }
        // 전원 탈퇴 마킹.
        for (GroupMemberInfo info : members) {
            groupMemberRepository.findActiveByGroupIdAndUserId(groupId, info.userId())
                    .ifPresent(member -> {
                        member.markLeft(now);
                        groupMemberRepository.save(member);
                    });
        }
        group.markDeleted();
        groupRepository.save(group);
        inviteLinkRepository.expirePendingByGroupId(groupId, now);
        // 각 멤버: 잔여 활성 그룹이 0개면 user 단위 봇 매핑 해제 (leaveGroup 과 동일 규칙).
        for (GroupMemberInfo info : members) {
            if (groupMemberRepository.listActiveGroupIdsByUserId(info.userId()).isEmpty()) {
                botUserMappingService.unlink(info.userId());
            }
        }
    }

    /**
     * 그룹원 목록 항목 결과 (GM-2). 정렬된 첫 항목만 {@code isOwner=true}.
     * <p>GP-1 FR-9: {@code profileImageUrl} 은 유효 프사 URL(thumb 키 우선 → 카카오 URL 폴백 → null)이다.</p>
     */
    public record GroupMemberResult(
            Long userId,
            String nickname,
            Instant joinedAt,
            boolean isOwner,
            String profileImageUrl
    ) {}

    /**
     * 그룹 대표 이미지 업로드/제거 결과 (GP-1). 두 URL 은 공개 URL(키→toPublicUrl)이며, 제거 시 모두 null.
     */
    public record GroupImageResult(
            String imageUrl,
            String imageThumbUrl
    ) {}
}
