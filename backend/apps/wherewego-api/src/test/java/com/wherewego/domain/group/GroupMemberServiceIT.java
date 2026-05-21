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
 * 미수락 초대 토큰 일괄 만료(BR-3, BR-6), 마지막 멤버 탈퇴 시 그룹 soft delete + 토큰 만료(AC-12)를 검증한다.</p>
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

    @DisplayName("createGroup - 동일 사용자가 활성 그룹을 보유한 상태에서 재호출하면 GROUP_ALREADY_ACTIVE (AC-10/AC-17).")
    @Test
    void createGroup_doubleAttempt_throwsGroupAlreadyActive() {
        // arrange
        groupMemberService.createGroup(userA, "첫 그룹");

        // act & assert
        assertThatThrownBy(() -> groupMemberService.createGroup(userA, "두 번째 그룹"))
                .isInstanceOf(CoreException.class)
                .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                        .isEqualTo(ErrorType.GROUP_ALREADY_ACTIVE));

        // assert : 첫 그룹만 남음. 두 번째 시도는 사전 검증에서 차단되므로 groups 행은 1개만 존재.
        List<Group> groups = groupJpaRepository.findAll();
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getName()).isEqualTo("첫 그룹");

        // 활성 멤버십 1건만 존재
        Integer activeMemberCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_members WHERE user_id = ? AND left_at IS NULL",
                Integer.class, userA);
        assertThat(activeMemberCount).isEqualTo(1);
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
                "SELECT token, expires_at, accepted_at FROM invite_links WHERE token = ?",
                first.token());
        Instant firstExpiresAt = ((Timestamp) firstRow.get("expires_at")).toInstant();
        assertThat(firstExpiresAt).isBeforeOrEqualTo(beforeSecond.plusSeconds(2));
        assertThat(firstRow.get("accepted_at")).isNull();

        // 두 번째 토큰의 expires_at 은 미래(24h 후)
        Map<String, Object> secondRow = jdbcTemplate.queryForMap(
                "SELECT token, expires_at FROM invite_links WHERE token = ?",
                second.token());
        Instant secondExpiresAt = ((Timestamp) secondRow.get("expires_at")).toInstant();
        assertThat(secondExpiresAt).isAfter(beforeSecond.plusSeconds(60));
    }

    @DisplayName("acceptInviteLink - 수락 시 group_members 활성 행이 추가되고 invite_links.accepted_at 이 기록된다 (AC-6).")
    @Test
    void acceptInviteLink_addsActiveMemberAndStampsAcceptedAt() {
        // arrange
        GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
        InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userA, group.groupId());

        // act
        InviteAcceptResult accepted = groupMemberService.acceptInviteLink(userB, invite.token());

        // assert : 그룹 ID 일치
        assertThat(accepted.groupId()).isEqualTo(group.groupId());

        // assert : group_members 2행 (둘 다 활성)
        List<GroupMember> members = groupMemberJpaRepository.findAll();
        assertThat(members).hasSize(2);
        assertThat(members).allSatisfy(m -> assertThat(m.getLeftAt()).isNull());
        assertThat(members).extracting(GroupMember::getUserId)
                .containsExactlyInAnyOrder(userA, userB);

        // assert : invite_links.accepted_at 기록
        Map<String, Object> linkRow = jdbcTemplate.queryForMap(
                "SELECT accepted_at FROM invite_links WHERE token = ?",
                invite.token());
        assertThat(linkRow.get("accepted_at")).isNotNull();
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

        // assert : 미수락 토큰이 만료 처리 (expires_at <= now & accepted_at IS NULL)
        Map<String, Object> linkRow = jdbcTemplate.queryForMap(
                "SELECT expires_at, accepted_at FROM invite_links WHERE token = ?",
                invite.token());
        Instant linkExpiresAt = ((Timestamp) linkRow.get("expires_at")).toInstant();
        assertThat(linkRow.get("accepted_at")).isNull();
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

        @DisplayName("createGroup - 동일 사용자 5스레드 동시 호출 시 정확히 1건만 성공하고 나머지는 GROUP_ALREADY_ACTIVE (AC-6).")
        @Test
        void createGroup_concurrent_onlyOneSucceeds() throws InterruptedException {
            // arrange : BeforeEach 가 userA 를 사전 시드. 활성 그룹은 없는 상태.

            // act : 5스레드가 동시에 createGroup 호출
            ConcurrentResult result = runConcurrently(5,
                    i -> () -> groupMemberService.createGroup(userA, "g" + i));

            // assert : 정확히 1건 성공
            assertThat(result.successCount()).isEqualTo(1);

            // assert : 활성 group_members 1건만 존재
            Integer activeMemberCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM group_members WHERE user_id = ? AND left_at IS NULL",
                    Integer.class, userA);
            assertThat(activeMemberCount).isEqualTo(1);

            // assert : 활성 groups 1건만 존재 (CONSIDER: groups 행 수 검증)
            Integer activeGroupCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM groups WHERE deleted_at IS NULL",
                    Integer.class);
            assertThat(activeGroupCount).isEqualTo(1);

            // assert : 나머지 4건은 GROUP_ALREADY_ACTIVE
            assertThat(result.errorTypes()).hasSize(4);
            assertThat(result.errorTypes())
                    .allSatisfy(et -> assertThat(et).isEqualTo(ErrorType.GROUP_ALREADY_ACTIVE));

            // assert : 분류되지 않은 예외 0건 (hasSize(4) 강건성 보강)
            assertThat(result.unexpectedCount()).isZero();
        }

        @DisplayName("acceptInviteLink - 서로 다른 5명이 동일 토큰을 동시 수락 시 정확히 1건만 성공, 나머지는 허용 에러 집합 내 (AC-7).")
        @Test
        void acceptInviteLink_concurrent_onlyOneSucceeds() throws InterruptedException {
            // arrange : userA 가 그룹 생성 + 초대 토큰 발급 (정원 2명, MVP 가정)
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

            // assert : 정확히 1건 성공
            assertThat(result.successCount()).isEqualTo(1);

            // assert : 나머지 4건 모두 errorTypes 로 분류됨 (race 중 분류 누락 회귀 방지)
            assertThat(result.errorTypes()).hasSize(4);

            // assert : 나머지 에러는 허용 집합 {INVITE_LINK_ALREADY_USED, GROUP_ALREADY_ACTIVE, GROUP_CAPACITY_EXCEEDED} 부분집합 (M3)
            assertThat(result.errorTypes()).allSatisfy(et ->
                    assertThat(et).isIn(
                            ErrorType.INVITE_LINK_ALREADY_USED,
                            ErrorType.GROUP_ALREADY_ACTIVE,
                            ErrorType.GROUP_CAPACITY_EXCEEDED));

            // assert : 분류되지 않은 예외 0건 (hasSize(4) 강건성 보강)
            assertThat(result.unexpectedCount()).isZero();
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
    }
}
