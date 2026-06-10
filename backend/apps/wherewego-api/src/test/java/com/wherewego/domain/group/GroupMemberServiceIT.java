package com.wherewego.domain.group;

import com.wherewego.domain.bot.BotUserMapping;
import com.wherewego.domain.user.UserModel;
import com.wherewego.infrastructure.bot.BotUserMappingJpaRepository;
import com.wherewego.infrastructure.group.GroupJpaRepository;
import com.wherewego.infrastructure.group.GroupMemberJpaRepository;
import com.wherewego.infrastructure.group.InviteLinkJpaRepository;
import com.wherewego.infrastructure.user.UserJpaRepository;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GroupMemberService 통합 테스트. 실제 PostgreSQL Testcontainer + JPA 매핑 + DB 제약 동작 검증.
 *
 * <p>Phase 3 group 도메인의 DB 제약(partial UNIQUE), 단일 트랜잭션 동작,
 * 활성 초대 토큰 일괄 만료(BR-3, BR-6), 마지막 멤버 탈퇴 시 그룹 soft delete + 토큰 만료(AC-12)를 검증한다.</p>
 * <p>IC-1: 1회용 소진(accepted_at) 제거 → 코드는 TTL 동안 정원 한도 내 재사용(FR-1).
 * 정원 도달은 만료가 아닌 가입 차단이라 코드는 TTL 까지 유지(Option A)되며, by-slug 가 정원초과를 구분한다(D4).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class GroupMemberServiceIT {

    @Autowired
    private GroupMemberService groupMemberService;

    @Autowired
    private GroupJpaRepository groupJpaRepository;

    @Autowired
    private InviteLinkJpaRepository inviteLinkJpaRepository;

    @Autowired
    private GroupMemberJpaRepository groupMemberJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BotUserMappingJpaRepository botUserMappingJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userA;
    private Long userB;

    @BeforeEach
    void cleanUp() {
        truncateAll();
        userA = userJpaRepository.save(UserModel.create(10000001L, "userA", null)).getId();
        userB = userJpaRepository.save(UserModel.create(10000002L, "userB", null)).getId();
    }

    @AfterEach
    void tearDown() {
        truncateAll();
    }

    private void truncateAll() {
        // users 를 참조하는 자식 테이블을 모두 정리한 뒤 users 삭제 (다른 IT 와 격리 보장).
        // Phase 10 V009: notifications.visit_pin_id → pins(id) FK 추가로 인해 pins 보다 먼저 삭제 필요.
        jdbcTemplate.execute("DELETE FROM notification_pins");
        jdbcTemplate.execute("DELETE FROM notifications");
        jdbcTemplate.execute("DELETE FROM pins");
        jdbcTemplate.execute("DELETE FROM invite_links");
        jdbcTemplate.execute("DELETE FROM group_members");
        jdbcTemplate.execute("DELETE FROM groups");
        jdbcTemplate.execute("DELETE FROM bot_link_codes");
        jdbcTemplate.execute("DELETE FROM bot_user_mappings");
        userJpaRepository.deleteAll();
    }

    @DisplayName("createGroup - groups 1행과 활성 group_members 1행이 단일 TX 로 저장된다 (AC-1).")
    @Test
    void createGroup_persistsGroupAndActiveMember() {
        // act
        GroupCreatedResult result = groupMemberService.createGroup(userA, "우리 지도");

        // assert : groups
        List<Group> groups = groupJpaRepository.findAll();
        assertThat(groups).hasSize(1);
        Group savedGroup = groups.get(0);
        assertThat(savedGroup.getId()).isEqualTo(result.groupId());
        assertThat(savedGroup.getName()).isEqualTo("우리 지도");
        assertThat(savedGroup.getDeletedAt()).isNull();

        // assert : group_members (활성)
        List<GroupMember> members = groupMemberJpaRepository.findAll();
        assertThat(members).hasSize(1);
        GroupMember savedMember = members.get(0);
        assertThat(savedMember.getGroupId()).isEqualTo(savedGroup.getId());
        assertThat(savedMember.getUserId()).isEqualTo(userA);
        assertThat(savedMember.getLeftAt()).isNull();
        assertThat(savedMember.getJoinedAt()).isNotNull();
    }

    @DisplayName("GM-1: 동일 사용자가 활성 그룹을 보유한 상태에서 재호출해도 두 번째 그룹이 생성된다 (1인 다중 활성 그룹, 사양 변경 — 회귀 아님).")
    @Test
    void createGroup_doubleAttempt_allowsSecondGroup() {
        // arrange
        GroupCreatedResult first = groupMemberService.createGroup(userA, "첫 그룹");

        // act : GM-1 으로 사전검사가 제거되어 두 번째 그룹도 정상 생성된다.
        GroupCreatedResult second = groupMemberService.createGroup(userA, "두 번째 그룹");

        // assert : 두 그룹 모두 존재
        List<Group> groups = groupJpaRepository.findAll();
        assertThat(groups).hasSize(2);
        assertThat(groups).extracting(Group::getName)
                .containsExactlyInAnyOrder("첫 그룹", "두 번째 그룹");
        assertThat(second.groupId()).isNotEqualTo(first.groupId());

        // 활성 멤버십 2건 존재 (각 그룹에 1건씩)
        Integer activeMemberCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_members WHERE user_id = ? AND left_at IS NULL",
                Integer.class, userA);
        assertThat(activeMemberCount).isEqualTo(2);
    }

    @DisplayName("GM-1(QE-2): 다른 그룹에 활성 상태인 사용자가 또 다른 그룹 초대를 수락하면 활성 멤버십이 2건이 된다.")
    @Test
    void acceptInviteLink_userAlreadyInAnotherGroup_joinsSecondGroup() {
        // arrange : 그룹X(userA 생성) + 그룹Y(userC 생성). userB 는 먼저 그룹X 에 합류해 활성 상태.
        Long userC = userJpaRepository.save(UserModel.create(10000009L, "userC", null)).getId();
        GroupCreatedResult groupX = groupMemberService.createGroup(userA, "그룹X");
        InviteLinkIssueResult inviteX = groupMemberService.issueInviteLink(userA, groupX.groupId());
        groupMemberService.acceptInviteLink(userB, inviteX.token());

        GroupCreatedResult groupY = groupMemberService.createGroup(userC, "그룹Y");
        InviteLinkIssueResult inviteY = groupMemberService.issueInviteLink(userC, groupY.groupId());

        // act : userB 가 그룹Y 초대도 수락 (GM-1: 1인1활성 제약 해제로 성공)
        InviteAcceptResult accepted = groupMemberService.acceptInviteLink(userB, inviteY.token());

        // assert : 수락 성공 + userB 활성 멤버십 2건 (그룹X, 그룹Y)
        assertThat(accepted.groupId()).isEqualTo(groupY.groupId());
        List<Long> userBGroupIds = jdbcTemplate.queryForList(
                "SELECT group_id FROM group_members WHERE user_id = ? AND left_at IS NULL ORDER BY group_id",
                Long.class, userB);
        assertThat(userBGroupIds).containsExactlyInAnyOrder(groupX.groupId(), groupY.groupId());
    }

    @DisplayName("GM-1(AC-11): 서로 다른 10명(생성자 1 + 수락 9)이 정원을 채운 그룹에 11번째 사용자가 수락하면 GROUP_CAPACITY_EXCEEDED.")
    @Test
    void acceptInviteLink_tenDistinctMembers_eleventhRejected() {
        // arrange : userA 가 그룹 생성 (멤버 1). 서로 다른 9명을 순차 수락시켜 정원 10 을 채운다.
        GroupCreatedResult group = groupMemberService.createGroup(userA, "정원그룹");
        for (int i = 0; i < 9; i++) {
            Long member = userJpaRepository.save(
                    UserModel.create(10000100L + i, "member" + i, null)).getId();
            InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userA, group.groupId());
            groupMemberService.acceptInviteLink(member, invite.token());
        }
        // 정원 10 도달 확인
        assertThat(groupMemberJpaRepository.countActiveByGroupId(group.groupId())).isEqualTo(10L);

        // 11번째: 신규 사용자(서로 다른 사람)로 수락 시도 — pair 위반이 아니라 정원 초과여야 한다.
        Long eleventh = userJpaRepository.save(UserModel.create(10000200L, "eleventh", null)).getId();
        InviteLinkIssueResult lastInvite = groupMemberService.issueInviteLink(userA, group.groupId());

        // act & assert
        assertThatThrownBy(() -> groupMemberService.acceptInviteLink(eleventh, lastInvite.token()))
                .isInstanceOf(CoreException.class)
                .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                        .isEqualTo(ErrorType.GROUP_CAPACITY_EXCEEDED));

        // assert : 활성 멤버 여전히 10명
        assertThat(groupMemberJpaRepository.countActiveByGroupId(group.groupId())).isEqualTo(10L);

        // assert : IC-1(Option A) — 정원 초과 거부 시에도 코드는 만료되지 않고 TTL(expires_at 미래)을 유지한다.
        //   정원 도달은 만료가 아니라 가입 차단이므로 by-slug 가 코드를 찾아 정원초과를 구분할 수 있다(D4).
        Boolean tokenStillActive = jdbcTemplate.queryForObject(
                "SELECT expires_at > now() FROM invite_links WHERE token = ?",
                Boolean.class, lastInvite.token());
        assertThat(tokenStillActive).isTrue();
    }

    @DisplayName("issueInviteLink - 동일 그룹에 재발급 시 기존 미수락 토큰의 expires_at 이 만료 시각으로 갱신된다 (AC-5, BR-3).")
    @Test
    void issueInviteLink_expiresOldPendingTokens() {
        // arrange : 그룹 + 첫 토큰
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        InviteLinkIssueResult first = groupMemberService.issueInviteLink(userA, group.groupId());

        // act : 같은 그룹에 두 번째 발급
        Instant beforeSecond = Instant.now();
        InviteLinkIssueResult second = groupMemberService.issueInviteLink(userA, group.groupId());

        // assert : invite_links 2행 모두 존재
        List<InviteLink> all = inviteLinkJpaRepository.findAll();
        assertThat(all).hasSize(2);

        // 첫 토큰의 expires_at 은 두 번째 발급 시점 직전(now) 으로 갱신되어 즉시 만료
        Map<String, Object> firstRow = jdbcTemplate.queryForMap(
                "SELECT token, expires_at FROM invite_links WHERE token = ?",
                first.token());
        Instant firstExpiresAt = ((Timestamp) firstRow.get("expires_at")).toInstant();
        assertThat(firstExpiresAt).isBeforeOrEqualTo(beforeSecond.plusSeconds(2));

        // 두 번째 토큰의 expires_at 은 미래(24h 후)
        Map<String, Object> secondRow = jdbcTemplate.queryForMap(
                "SELECT token, expires_at FROM invite_links WHERE token = ?",
                second.token());
        Instant secondExpiresAt = ((Timestamp) secondRow.get("expires_at")).toInstant();
        assertThat(secondExpiresAt).isAfter(beforeSecond.plusSeconds(60));
    }

    @DisplayName("currentInviteLink - 발급된 활성 코드를 조회하며 새 코드를 만들지 않는다 (IC-2 후속).")
    @Test
    void currentInviteLink_returnsActiveCode_withoutCreatingNew() {
        // arrange : 그룹 + 코드 1개 발급
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        InviteLinkIssueResult issued = groupMemberService.issueInviteLink(userA, group.groupId());

        // act
        var current = groupMemberService.currentInviteLink(userA, group.groupId());

        // assert : 발급된 코드와 동일 + 조회는 새 행을 만들지 않음(여전히 1행)
        assertThat(current).isPresent();
        assertThat(current.get().slug()).isEqualTo(issued.slug());
        assertThat(current.get().token()).isEqualTo(issued.token());
        assertThat(inviteLinkJpaRepository.findAll()).hasSize(1);
    }

    @DisplayName("currentInviteLink - 활성 코드가 없으면 empty (IC-2 후속).")
    @Test
    void currentInviteLink_emptyWhenNoActiveCode() {
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");

        var current = groupMemberService.currentInviteLink(userA, group.groupId());

        assertThat(current).isEmpty();
    }

    @DisplayName("currentInviteLink - 재발급 후에는 만료된 이전 코드가 아닌 현재 활성 코드를 반환한다 (IC-2 후속).")
    @Test
    void currentInviteLink_returnsLatestAfterReissue() {
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        groupMemberService.issueInviteLink(userA, group.groupId());            // 만료될 첫 코드
        InviteLinkIssueResult second = groupMemberService.issueInviteLink(userA, group.groupId());

        var current = groupMemberService.currentInviteLink(userA, group.groupId());

        assertThat(current).isPresent();
        assertThat(current.get().slug()).isEqualTo(second.slug());
    }

    @DisplayName("currentInviteLink - 비멤버는 GROUP_NOT_MEMBER 로 거부된다 (IC-2 후속).")
    @Test
    void currentInviteLink_nonMemberRejected() {
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        groupMemberService.issueInviteLink(userA, group.groupId());

        assertThatThrownBy(() -> groupMemberService.currentInviteLink(userB, group.groupId()))
                .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                        .isEqualTo(ErrorType.GROUP_NOT_MEMBER));
    }

    @DisplayName("IC-1(AC-1): 동일 코드를 서로 다른 2명이 각각 수락하면 둘 다 활성 멤버가 되고 코드는 TTL 까지 유지된다.")
    @Test
    void acceptInviteLink_sameTokenTwoUsers_bothJoinAndCodeStaysActive() {
        // arrange : userA 생성 + 1개 코드 발급. userC 추가 시드.
        Long userC = userJpaRepository.save(UserModel.create(10000009L, "userC", null)).getId();
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userA, group.groupId());

        // act : 동일 코드를 userB, userC 가 각각 수락
        InviteAcceptResult acceptedB = groupMemberService.acceptInviteLink(userB, invite.token());
        InviteAcceptResult acceptedC = groupMemberService.acceptInviteLink(userC, invite.token());

        // assert : 둘 다 같은 그룹에 가입
        assertThat(acceptedB.groupId()).isEqualTo(group.groupId());
        assertThat(acceptedC.groupId()).isEqualTo(group.groupId());

        // assert : group_members 3행 (생성자 + 2명, 모두 활성)
        List<GroupMember> members = groupMemberJpaRepository.findAll();
        assertThat(members).hasSize(3);
        assertThat(members).allSatisfy(m -> assertThat(m.getLeftAt()).isNull());
        assertThat(members).extracting(GroupMember::getUserId)
                .containsExactlyInAnyOrder(userA, userB, userC);

        // assert : IC-1 — 두 명이 사용해도 코드는 소진되지 않고 TTL(expires_at 미래)을 유지한다.
        Boolean codeStillActive = jdbcTemplate.queryForObject(
                "SELECT expires_at > now() FROM invite_links WHERE token = ?",
                Boolean.class, invite.token());
        assertThat(codeStillActive).isTrue();
    }

    @DisplayName("IC-1(AC-2): 정원 9명에서 1명이 수락하면 정원 10 도달 + 코드는 만료되지 않고 TTL 유지 + 이후 동일 코드 수락은 GROUP_CAPACITY_EXCEEDED.")
    @Test
    void acceptInviteLink_capacityReached_codeStaysActiveAndBlocksFurther() {
        // arrange : userA 생성(1명) + 8명 수락 → 9명. 동일 코드 1개를 끝까지 재사용한다.
        GroupCreatedResult group = groupMemberService.createGroup(userA, "정원그룹");
        InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userA, group.groupId());
        for (int i = 0; i < 8; i++) {
            Long member = userJpaRepository.save(
                    UserModel.create(10000300L + i, "m" + i, null)).getId();
            groupMemberService.acceptInviteLink(member, invite.token());
        }
        assertThat(groupMemberJpaRepository.countActiveByGroupId(group.groupId())).isEqualTo(9L);

        // act : 9 → 10 (마지막 1자리)을 동일 코드로 채운다.
        Long ninth = userJpaRepository.save(UserModel.create(10000400L, "ninth", null)).getId();
        groupMemberService.acceptInviteLink(ninth, invite.token());

        // assert : (a) 정원 10 도달
        assertThat(groupMemberJpaRepository.countActiveByGroupId(group.groupId())).isEqualTo(10L);

        // assert : (b) IC-1(Option A) — 정원 도달 후에도 코드는 만료되지 않고 TTL(expires_at 미래)을 유지한다.
        Boolean codeStillActive = jdbcTemplate.queryForObject(
                "SELECT expires_at > now() FROM invite_links WHERE token = ?",
                Boolean.class, invite.token());
        assertThat(codeStillActive).isTrue();

        // assert : (c) 이후 동일 코드 수락은 GROUP_CAPACITY_EXCEEDED (만료 EXPIRED 가 아니라 정원 초과로 구분).
        Long eleventh = userJpaRepository.save(UserModel.create(10000401L, "eleventh", null)).getId();
        assertThatThrownBy(() -> groupMemberService.acceptInviteLink(eleventh, invite.token()))
                .isInstanceOf(CoreException.class)
                .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                        .isEqualTo(ErrorType.GROUP_CAPACITY_EXCEEDED));
    }

    @DisplayName("IC-1(AC-3): 재발급 후 구 코드로 수락하면 INVITE_LINK_EXPIRED (BR-3 단일 활성 코드).")
    @Test
    void acceptInviteLink_oldTokenAfterReissue_throwsExpired() {
        // arrange : 첫 코드 발급 후 같은 그룹에 재발급 → 구 코드 즉시 만료.
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        InviteLinkIssueResult oldInvite = groupMemberService.issueInviteLink(userA, group.groupId());
        groupMemberService.issueInviteLink(userA, group.groupId());

        // act & assert : 구 코드 수락 시도 → 만료
        assertThatThrownBy(() -> groupMemberService.acceptInviteLink(userB, oldInvite.token()))
                .isInstanceOf(CoreException.class)
                .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                        .isEqualTo(ErrorType.INVITE_LINK_EXPIRED));
    }

    @DisplayName("IC-1(AC-4): 이미 활성 멤버가 코드를 수락하면 GROUP_ALREADY_MEMBER 이고 멤버 수는 불변이다.")
    @Test
    void acceptInviteLink_alreadyMember_throwsAlreadyMemberAndCountUnchanged() {
        // arrange : userB 가 먼저 합류해 활성 멤버. userA 가 새 코드를 발급한다.
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        InviteLinkIssueResult first = groupMemberService.issueInviteLink(userA, group.groupId());
        groupMemberService.acceptInviteLink(userB, first.token());
        long before = groupMemberJpaRepository.countActiveByGroupId(group.groupId());
        InviteLinkIssueResult second = groupMemberService.issueInviteLink(userA, group.groupId());

        // act & assert : 이미 멤버인 userB 가 재수락
        assertThatThrownBy(() -> groupMemberService.acceptInviteLink(userB, second.token()))
                .isInstanceOf(CoreException.class)
                .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                        .isEqualTo(ErrorType.GROUP_ALREADY_MEMBER));

        // assert : 멤버 수 불변
        assertThat(groupMemberJpaRepository.countActiveByGroupId(group.groupId())).isEqualTo(before);
    }

    @DisplayName("IC-1(AC-5): TTL 만료 코드를 수락하면 INVITE_LINK_EXPIRED.")
    @Test
    void acceptInviteLink_expiredTtl_throwsExpired() {
        // arrange : 코드 발급 후 expires_at 을 과거로 직접 갱신해 TTL 만료를 재현.
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userA, group.groupId());
        jdbcTemplate.update(
                "UPDATE invite_links SET expires_at = now() - interval '1 hour' WHERE token = ?",
                invite.token());

        // act & assert
        assertThatThrownBy(() -> groupMemberService.acceptInviteLink(userB, invite.token()))
                .isInstanceOf(CoreException.class)
                .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                        .isEqualTo(ErrorType.INVITE_LINK_EXPIRED));
    }

    @DisplayName("IC-1(AC-10): 탈퇴 시 활성 코드가 만료되어 이후 동일 코드 수락은 INVITE_LINK_EXPIRED.")
    @Test
    void acceptInviteLink_afterLeaveExpiresCode_throwsExpired() {
        // arrange : userA, userB 2명 그룹. userA 가 코드를 발급한 뒤 userA 가 탈퇴(BR-5: 활성 코드 만료).
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        InviteLinkIssueResult firstInvite = groupMemberService.issueInviteLink(userA, group.groupId());
        groupMemberService.acceptInviteLink(userB, firstInvite.token());
        InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userA, group.groupId());
        groupMemberService.leaveGroup(userA, group.groupId());

        // act & assert : 탈퇴로 만료된 코드를 신규 사용자가 수락 시도 → 만료
        Long userC = userJpaRepository.save(UserModel.create(10000009L, "userC", null)).getId();
        assertThatThrownBy(() -> groupMemberService.acceptInviteLink(userC, invite.token()))
                .isInstanceOf(CoreException.class)
                .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                        .isEqualTo(ErrorType.INVITE_LINK_EXPIRED));
    }

    @DisplayName("acceptInviteLink - soft delete 된 그룹의 토큰 수락 시 INVITE_LINK_EXPIRED (AC-9, BR-7).")
    @Test
    void acceptInviteLink_softDeletedGroup_throwsExpired() {
        // arrange : 그룹 생성 + 토큰 발급
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userA, group.groupId());

        // 그룹을 직접 soft delete
        jdbcTemplate.update("UPDATE groups SET deleted_at = now() WHERE id = ?", group.groupId());

        // act & assert
        assertThatThrownBy(() -> groupMemberService.acceptInviteLink(userB, invite.token()))
                .isInstanceOf(CoreException.class)
                .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                        .isEqualTo(ErrorType.INVITE_LINK_EXPIRED));

        // assert : userB 활성 멤버십 없음
        Integer userBActive = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_members WHERE user_id = ? AND left_at IS NULL",
                Integer.class, userB);
        assertThat(userBActive).isEqualTo(0);
    }

    @DisplayName("leaveGroup - 마지막 멤버 탈퇴 시 그룹 soft delete + 미수락 토큰 일괄 만료 (AC-12, BR-6).")
    @Test
    void leaveGroup_lastMember_softDeletesGroupAndExpiresPendingTokens() {
        // arrange : 그룹 생성 + 미수락 토큰
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userA, group.groupId());
        Instant beforeLeave = Instant.now();

        // act : 마지막 멤버 탈퇴
        groupMemberService.leaveGroup(userA, group.groupId());

        // assert : groups.deleted_at != null
        Group reloaded = groupJpaRepository.findById(group.groupId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();

        // assert : group_members.left_at != null
        Map<String, Object> memberRow = jdbcTemplate.queryForMap(
                "SELECT left_at FROM group_members WHERE group_id = ? AND user_id = ?",
                group.groupId(), userA);
        assertThat(memberRow.get("left_at")).isNotNull();

        // assert : 활성 토큰이 만료 처리 (expires_at <= now)
        Map<String, Object> linkRow = jdbcTemplate.queryForMap(
                "SELECT expires_at FROM invite_links WHERE token = ?",
                invite.token());
        Instant linkExpiresAt = ((Timestamp) linkRow.get("expires_at")).toInstant();
        assertThat(linkExpiresAt).isBeforeOrEqualTo(beforeLeave.plusSeconds(2));
    }

    @DisplayName("leaveGroup - 마지막 멤버가 아니면 그룹은 유지되고 탈퇴자만 left_at 기록 (AC-11).")
    @Test
    void leaveGroup_notLastMember_keepsGroup() {
        // arrange : 그룹 + userB 수락하여 2명 활성
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userA, group.groupId());
        groupMemberService.acceptInviteLink(userB, invite.token());

        // act : userA 탈퇴
        groupMemberService.leaveGroup(userA, group.groupId());

        // assert : 그룹은 활성 유지
        Group reloaded = groupJpaRepository.findById(group.groupId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNull();

        // assert : userA.left_at NOT NULL
        Map<String, Object> userARow = jdbcTemplate.queryForMap(
                "SELECT left_at FROM group_members WHERE group_id = ? AND user_id = ?",
                group.groupId(), userA);
        assertThat(userARow.get("left_at")).isNotNull();

        // assert : userB.left_at NULL (활성 유지)
        Map<String, Object> userBRow = jdbcTemplate.queryForMap(
                "SELECT left_at FROM group_members WHERE group_id = ? AND user_id = ?",
                group.groupId(), userB);
        assertThat(userBRow.get("left_at")).isNull();
    }

    @DisplayName("leaveGroup - 탈퇴 후에도 핀은 그룹에 잔류한다 (AC-13, FR-GRP-6).")
    @Test
    void pins_remain_afterLeave() {
        // arrange : 2명 활성 그룹 + userA 가 등록한 핀 1개
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userA, group.groupId());
        groupMemberService.acceptInviteLink(userB, invite.token());

        jdbcTemplate.update(
                "INSERT INTO pins (group_id, created_by, place_name, latitude, longitude, tag) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                group.groupId(), userA, "TestCafe",
                new BigDecimal("37.5000000"), new BigDecimal("127.0000000"), "REEL");

        // act : userA 탈퇴 (남은 활성 멤버 userB 존재)
        groupMemberService.leaveGroup(userA, group.groupId());

        // assert : 핀이 그대로 잔류 (group_id/created_by 불변, deleted_at = null)
        List<Map<String, Object>> pinRows = jdbcTemplate.queryForList(
                "SELECT group_id, created_by, deleted_at FROM pins WHERE group_id = ?",
                group.groupId());
        assertThat(pinRows).hasSize(1);
        Map<String, Object> pinRow = pinRows.get(0);
        assertThat(((Number) pinRow.get("group_id")).longValue()).isEqualTo(group.groupId());
        assertThat(((Number) pinRow.get("created_by")).longValue()).isEqualTo(userA);
        assertThat(pinRow.get("deleted_at")).isNull();
    }

    @DisplayName("leaveGroup - 봇 매핑이 존재하던 사용자가 탈퇴하면 bot_user_mappings 행도 함께 삭제된다 (AC-B6).")
    @Test
    void leaveGroup_removesBotUserMapping() {
        // arrange : 그룹 + 봇 매핑 사전 등록
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        botUserMappingJpaRepository.save(BotUserMapping.link("kakao-bot-A", userA, Instant.now()));
        assertThat(botUserMappingJpaRepository.findByUserId(userA)).isPresent();

        // act
        groupMemberService.leaveGroup(userA, group.groupId());

        // assert : 봇 매핑 0건
        Integer mappingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bot_user_mappings WHERE user_id = ?",
                Integer.class, userA);
        assertThat(mappingCount).isZero();
    }

    @DisplayName("leaveGroup - 봇 미연동 사용자도 정상 탈퇴 처리되며 예외가 발생하지 않는다 (AC-B7).")
    @Test
    void leaveGroup_withoutBotMapping_succeeds() {
        // arrange : 봇 매핑 없음
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        assertThat(botUserMappingJpaRepository.findByUserId(userA)).isEmpty();

        // act
        groupMemberService.leaveGroup(userA, group.groupId());

        // assert : group_members.left_at 기록
        Map<String, Object> memberRow = jdbcTemplate.queryForMap(
                "SELECT left_at FROM group_members WHERE group_id = ? AND user_id = ?",
                group.groupId(), userA);
        assertThat(memberRow.get("left_at")).isNotNull();
    }

    @Nested
    @DisplayName("동시성 — Race Condition 방어")
    class Concurrency {

        private record ConcurrentResult(int successCount, List<ErrorType> errorTypes, int unexpectedCount) {
        }

        private ConcurrentResult runConcurrently(int threadCount, IntFunction<Runnable> actionFactory)
                throws InterruptedException {
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            try {
                CountDownLatch startGate = new CountDownLatch(1);
                CountDownLatch doneGate = new CountDownLatch(threadCount);
                AtomicInteger successCount = new AtomicInteger();
                ConcurrentLinkedQueue<ErrorType> errors = new ConcurrentLinkedQueue<>();
                AtomicInteger unexpectedCount = new AtomicInteger();

                for (int i = 0; i < threadCount; i++) {
                    final int idx = i;
                    pool.submit(() -> {
                        try {
                            startGate.await();
                            actionFactory.apply(idx).run();
                            successCount.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            unexpectedCount.incrementAndGet();
                        } catch (CoreException e) {
                            errors.add(e.getErrorType());
                        } catch (Exception e) {
                            // 동시성 race 중 발생하는 DB/래퍼 예외는 errorTypes 에 누적하지 않고
                            // unexpectedCount 로 별도 집계하여 분류되지 않은 예외 0건을 검증한다.
                            unexpectedCount.incrementAndGet();
                        } finally {
                            doneGate.countDown();
                        }
                    });
                }
                startGate.countDown();
                boolean done = doneGate.await(15, TimeUnit.SECONDS);
                assertThat(done).isTrue();
                return new ConcurrentResult(successCount.get(), List.copyOf(errors), unexpectedCount.get());
            } finally {
                pool.shutdown();
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            }
        }

        @DisplayName("GM-1: createGroup - 동일 사용자 5스레드 동시 호출 시 다중 그룹 허용으로 5건 모두 성공한다 (사양 변경 — 회귀 아님).")
        @Test
        void createGroup_concurrent_allSucceed() throws InterruptedException {
            // arrange : BeforeEach 가 userA 를 사전 시드. 활성 그룹은 없는 상태.
            //   GM-1 으로 1인1활성 제약(existsActiveByUserId + partial unique index)이 제거되어
            //   동시 createGroup 5건이 모두 독립 그룹을 생성한다(이전엔 1건만 성공 → 사양 변경).

            // act : 5스레드가 동시에 createGroup 호출
            ConcurrentResult result = runConcurrently(5,
                    i -> () -> groupMemberService.createGroup(userA, "g" + i));

            // assert : 5건 모두 성공
            assertThat(result.successCount()).isEqualTo(5);

            // assert : 활성 group_members 5건 존재
            Integer activeMemberCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM group_members WHERE user_id = ? AND left_at IS NULL",
                    Integer.class, userA);
            assertThat(activeMemberCount).isEqualTo(5);

            // assert : 활성 groups 5건 존재
            Integer activeGroupCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM groups WHERE deleted_at IS NULL",
                    Integer.class);
            assertThat(activeGroupCount).isEqualTo(5);

            // assert : 에러 0건, 분류되지 않은 예외 0건
            assertThat(result.errorTypes()).isEmpty();
            assertThat(result.unexpectedCount()).isZero();
        }

        @DisplayName("IC-1(AC-1): 서로 다른 5명이 동일 토큰을 동시 수락 시 정원(10) 여유로 5건 모두 성공한다 (기대 반전 — 1회용 소진 제거).")
        @Test
        void acceptInviteLink_concurrentWithinCapacity_allSucceed() throws InterruptedException {
            // arrange : userA 가 그룹 생성(1명) + 1개 코드 발급. IC-1 으로 코드는 재사용 가능하고
            //   정원 10 에 여유가 있어 서로 다른 5명 동시 수락이 전부 성공한다(이전엔 1회용이라 1건만).
            GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
            InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userA, group.groupId());
            String token = invite.token();

            // userC~userG 5명 사전 생성 (모두 활성 그룹 없음)
            Long[] users = new Long[]{
                    userJpaRepository.save(UserModel.create(10000003L, "userC", null)).getId(),
                    userJpaRepository.save(UserModel.create(10000004L, "userD", null)).getId(),
                    userJpaRepository.save(UserModel.create(10000005L, "userE", null)).getId(),
                    userJpaRepository.save(UserModel.create(10000006L, "userF", null)).getId(),
                    userJpaRepository.save(UserModel.create(10000007L, "userG", null)).getId(),
            };

            // act : 5명이 동시에 동일 토큰으로 acceptInviteLink
            ConcurrentResult result = runConcurrently(5,
                    i -> () -> groupMemberService.acceptInviteLink(users[i], token));

            // assert : 5건 모두 성공 (정원 여유)
            assertThat(result.successCount()).isEqualTo(5);
            assertThat(result.errorTypes()).isEmpty();
            assertThat(result.unexpectedCount()).isZero();

            // assert : 활성 멤버 6명(생성자 + 5명)
            assertThat(groupMemberJpaRepository.countActiveByGroupId(group.groupId())).isEqualTo(6L);
        }

        @DisplayName("IC-1(AC-8): 정원 9 세팅 후 서로 다른 10명이 마지막 1자리를 동일 토큰으로 동시 수락 → 정확히 1건만 성공, 나머지 9건 GROUP_CAPACITY_EXCEEDED.")
        @Test
        void acceptInviteLink_concurrentLastSeat_onlyOneSucceeds() throws InterruptedException {
            // arrange : userA 생성(1명) + 8명 수락 → 정원 9. 동일 코드 1개를 재사용한다.
            GroupCreatedResult group = groupMemberService.createGroup(userA, "정원그룹");
            InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userA, group.groupId());
            for (int i = 0; i < 8; i++) {
                Long member = userJpaRepository.save(
                        UserModel.create(10000500L + i, "seed" + i, null)).getId();
                groupMemberService.acceptInviteLink(member, invite.token());
            }
            assertThat(groupMemberJpaRepository.countActiveByGroupId(group.groupId())).isEqualTo(9L);
            String token = invite.token();

            // 마지막 1자리를 노리는 서로 다른 10명 사전 생성
            Long[] contenders = new Long[10];
            for (int i = 0; i < 10; i++) {
                contenders[i] = userJpaRepository.save(
                        UserModel.create(10000600L + i, "race" + i, null)).getId();
            }

            // act : 10명이 동시에 동일 토큰으로 마지막 자리 수락
            ConcurrentResult result = runConcurrently(10,
                    i -> () -> groupMemberService.acceptInviteLink(contenders[i], token));

            // assert : 정확히 1건만 성공 (group 비관락 직렬화로 정원 초과 INSERT 차단, BR-4)
            assertThat(result.successCount()).isEqualTo(1);

            // assert : 나머지 9건 모두 GROUP_CAPACITY_EXCEEDED (서로 다른 사람이라 pair/already_member 아님)
            assertThat(result.errorTypes()).hasSize(9);
            assertThat(result.errorTypes()).allSatisfy(et ->
                    assertThat(et).isEqualTo(ErrorType.GROUP_CAPACITY_EXCEEDED));
            assertThat(result.unexpectedCount()).isZero();

            // assert : 활성 멤버 정확히 10명
            assertThat(groupMemberJpaRepository.countActiveByGroupId(group.groupId())).isEqualTo(10L);
        }

        @DisplayName("leaveGroup - 동일 사용자 5스레드 동시 호출 시 정확히 1건만 성공하고 나머지는 GROUP_NOT_MEMBER (AC-8).")
        @Test
        void leaveGroup_concurrent_onlyOneSucceeds() throws InterruptedException {
            // arrange : userA 로 그룹 생성 (활성 멤버 1건)
            GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
            Long groupId = group.groupId();

            // act : 5스레드가 동시에 동일 사용자/그룹으로 leaveGroup
            ConcurrentResult result = runConcurrently(5,
                    i -> () -> groupMemberService.leaveGroup(userA, groupId));

            // assert : 정확히 1건 성공
            assertThat(result.successCount()).isEqualTo(1);

            // assert : 나머지 4건은 GROUP_NOT_MEMBER
            assertThat(result.errorTypes()).hasSize(4);
            assertThat(result.errorTypes())
                    .allSatisfy(et -> assertThat(et).isEqualTo(ErrorType.GROUP_NOT_MEMBER));

            // assert : 분류되지 않은 예외 0건 (hasSize(4) 강건성 보강)
            assertThat(result.unexpectedCount()).isZero();
        }

        @DisplayName("GM-1: 동일 그룹 동시 가입 방어 — 이미 활성 멤버가 같은 그룹의 새 토큰을 수락하면 "
                + "uq_group_members_pair 위반이 GROUP_REJOIN_FORBIDDEN 으로 변환된다 (FK 오분류 없음).")
        @Test
        void acceptInviteLink_samePairConflict_throwsRejoinForbidden() throws InterruptedException {
            // arrange : userA 그룹 생성 + userB 가 합류해 이미 활성 멤버. 이후 userA 가 새 토큰을 발급한다.
            GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
            InviteLinkIssueResult firstInvite = groupMemberService.issueInviteLink(userA, group.groupId());
            groupMemberService.acceptInviteLink(userB, firstInvite.token());
            InviteLinkIssueResult secondInvite = groupMemberService.issueInviteLink(userA, group.groupId());

            // act : 동일 토큰을 userB 2스레드가 동시 수락 — 이미 활성 멤버라 가입은 차단된다.
            //   IC-1: 사전 가드(findActiveByGroupIdAndUserId)가 GROUP_ALREADY_MEMBER 로 차단하고,
            //   race 로 가드를 통과한 동시 INSERT 는 uq_group_members_pair 위반 → GROUP_REJOIN_FORBIDDEN.
            ConcurrentResult result = runConcurrently(2,
                    i -> () -> groupMemberService.acceptInviteLink(userB, secondInvite.token()));

            // assert : 둘 다 실패(이미 활성 멤버이므로 성공 0건), 허용 집합 내 + pair 가 정원으로 오분류되지 않음
            assertThat(result.successCount()).isZero();
            assertThat(result.errorTypes()).hasSize(2);
            assertThat(result.errorTypes()).allSatisfy(et ->
                    assertThat(et).isIn(
                            ErrorType.GROUP_ALREADY_MEMBER,
                            ErrorType.GROUP_REJOIN_FORBIDDEN));
            assertThat(result.unexpectedCount()).isZero();

            // assert : userB 활성 멤버십은 여전히 1건(중복 INSERT 차단)
            Integer userBActive = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM group_members WHERE user_id = ? AND group_id = ? AND left_at IS NULL",
                    Integer.class, userB, group.groupId());
            assertThat(userBActive).isEqualTo(1);
        }
    }
}
