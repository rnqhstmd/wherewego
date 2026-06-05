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

        // assert : 정원 초과 거부 시 토큰은 소진되지 않는다(accepted_at IS NULL).
        //   정원 검사가 markAcceptedIfPending 보다 앞에 위치하므로 거부된 토큰은 재사용 가능 (통합 감사 MEDIUM 반영).
        Boolean tokenStillPending = jdbcTemplate.queryForObject(
                "SELECT accepted_at IS NULL FROM invite_links WHERE token = ?",
                Boolean.class, lastInvite.token());
        assertThat(tokenStillPending).isTrue();
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

        @DisplayName("acceptInviteLink - 서로 다른 5명이 동일 토큰을 동시 수락 시 정확히 1건만 성공, 나머지는 허용 에러 집합 내 (AC-7).")
        @Test
        void acceptInviteLink_concurrent_onlyOneSucceeds() throws InterruptedException {
            // arrange : userA 가 그룹 생성 + 초대 토큰 발급. 토큰은 1회용(markAccepted)이라
            //   정원(GM-1: 10)과 무관하게 동일 토큰 동시 수락은 1건만 성공한다.
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

            // assert : GM-1 으로 GROUP_ALREADY_ACTIVE 는 발생 불가 → 허용 집합은
            //   {INVITE_LINK_ALREADY_USED, GROUP_CAPACITY_EXCEEDED} 부분집합 (M3).
            assertThat(result.errorTypes()).allSatisfy(et ->
                    assertThat(et).isIn(
                            ErrorType.INVITE_LINK_ALREADY_USED,
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

        @DisplayName("GM-1: 동일 그룹 동시 가입 방어 — 이미 활성 멤버가 같은 그룹의 새 토큰을 수락하면 "
                + "uq_group_members_pair 위반이 GROUP_REJOIN_FORBIDDEN 으로 변환된다 (FK 오분류 없음).")
        @Test
        void acceptInviteLink_samePairConflict_throwsRejoinForbidden() throws InterruptedException {
            // arrange : userA 그룹 생성 + userB 가 합류해 이미 활성 멤버. 이후 userA 가 새 토큰을 발급한다.
            GroupCreatedResult group = groupMemberService.createGroup(userA, "우리 지도");
            InviteLinkIssueResult firstInvite = groupMemberService.issueInviteLink(userA, group.groupId());
            groupMemberService.acceptInviteLink(userB, firstInvite.token());
            InviteLinkIssueResult secondInvite = groupMemberService.issueInviteLink(userA, group.groupId());

            // act : 동일 토큰을 userB 2스레드가 동시 수락 — pair 제약이 재가입을 차단한다.
            //   1건은 INVITE_LINK_ALREADY_USED(토큰 1회성), pair 경로 진입 시 GROUP_REJOIN_FORBIDDEN.
            ConcurrentResult result = runConcurrently(2,
                    i -> () -> groupMemberService.acceptInviteLink(userB, secondInvite.token()));

            // assert : 둘 다 실패(이미 활성 멤버이므로 성공 0건), 허용 집합 내 + pair 가 정원으로 오분류되지 않음
            assertThat(result.successCount()).isZero();
            assertThat(result.errorTypes()).hasSize(2);
            assertThat(result.errorTypes()).allSatisfy(et ->
                    assertThat(et).isIn(
                            ErrorType.INVITE_LINK_ALREADY_USED,
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
