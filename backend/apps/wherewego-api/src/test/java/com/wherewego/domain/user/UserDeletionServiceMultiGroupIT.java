package com.wherewego.domain.user;

import com.wherewego.domain.group.GroupCreatedResult;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.group.InviteLinkIssueResult;
import com.wherewego.infrastructure.user.UserJpaRepository;
import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GM-1 (QE-3): 계정 삭제 시 다중 활성 그룹 전체 순회 탈퇴 통합 테스트.
 *
 * <p>1인 다중 활성 그룹 환경에서 {@code UserDeletionService.deleteAccount} 가
 * group_id 오름차순으로 모든 활성 그룹을 탈퇴 처리하고, 마지막 멤버였던 그룹은 soft delete +
 * 커플 방 정리하며, 파트너가 남은 그룹은 유지함을 실제 PostgreSQL Testcontainer 로 검증한다.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class UserDeletionServiceMultiGroupIT {

    @Autowired
    private UserDeletionService userDeletionService;

    @Autowired
    private GroupMemberService groupMemberService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userA;
    private Long userB;

    @BeforeEach
    void cleanUp() {
        truncateAll();
        userA = userJpaRepository.save(UserModel.create(30000001L, "userA", null)).getId();
        userB = userJpaRepository.save(UserModel.create(30000002L, "userB", null)).getId();
    }

    @AfterEach
    void tearDown() {
        truncateAll();
    }

    private void truncateAll() {
        jdbcTemplate.execute("DELETE FROM notification_pins");
        jdbcTemplate.execute("DELETE FROM notifications");
        jdbcTemplate.execute("DELETE FROM pins");
        jdbcTemplate.execute("DELETE FROM chat_message");
        jdbcTemplate.execute("DELETE FROM chat_room");
        jdbcTemplate.execute("DELETE FROM invite_links");
        jdbcTemplate.execute("DELETE FROM group_members");
        jdbcTemplate.execute("DELETE FROM groups");
        jdbcTemplate.execute("DELETE FROM devices");
        jdbcTemplate.execute("DELETE FROM bot_link_codes");
        jdbcTemplate.execute("DELETE FROM bot_user_mappings");
        userJpaRepository.deleteAll();
    }

    @DisplayName("deleteAccount - userA 가 3개 활성 그룹(2개 단독 + 1개 파트너 동반)일 때, 전부 left_at 처리되고 "
            + "단독 그룹만 soft delete + 커플 방 정리되며 파트너 그룹은 유지된다.")
    @Test
    void deleteAccount_multipleGroups_leavesAll() {
        // arrange : userA 단독 그룹 2개
        GroupCreatedResult soloGroup1 = groupMemberService.createGroup(userA, "단독그룹1");
        GroupCreatedResult soloGroup2 = groupMemberService.createGroup(userA, "단독그룹2");

        // userA + userB 공유 그룹 1개 (userB 가 합류 → userA 탈퇴해도 유지되어야 함)
        GroupCreatedResult sharedGroup = groupMemberService.createGroup(userA, "공유그룹");
        InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userA, sharedGroup.groupId());
        groupMemberService.acceptInviteLink(userB, invite.token());

        // 각 그룹의 그룹 방(type=GROUP, GC-1: 커플 방 일반화) 존재를 보장하여 softDeleteByGroup 동작도 검증한다.
        // (그룹 생성 시 자동 생성 훅과 충돌하지 않도록 멱등 — 없을 때만 insert.)
        ensureGroupRoom(soloGroup1.groupId());
        ensureGroupRoom(soloGroup2.groupId());
        ensureGroupRoom(sharedGroup.groupId());

        // userA 가 3개 그룹 모두 활성인지 사전 확인
        Integer activeBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_members WHERE user_id = ? AND left_at IS NULL",
                Integer.class, userA);
        assertThat(activeBefore).isEqualTo(3);

        // act
        userDeletionService.deleteAccount(userA);

        // assert : userA 의 모든 멤버십이 left_at 처리됨 (활성 0건)
        Integer activeAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_members WHERE user_id = ? AND left_at IS NULL",
                Integer.class, userA);
        assertThat(activeAfter).isZero();

        // assert : 단독 그룹 2개는 soft delete (마지막 멤버 탈퇴)
        assertThat(groupDeletedAt(soloGroup1.groupId())).isNotNull();
        assertThat(groupDeletedAt(soloGroup2.groupId())).isNotNull();

        // assert : 파트너(userB)가 남은 공유 그룹은 유지 (deleted_at NULL)
        assertThat(groupDeletedAt(sharedGroup.groupId())).isNull();
        Integer userBActiveInShared = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_members WHERE user_id = ? AND group_id = ? AND left_at IS NULL",
                Integer.class, userB, sharedGroup.groupId());
        assertThat(userBActiveInShared).isEqualTo(1);

        // assert : 단독 그룹 그룹 방은 soft delete, 공유 그룹 그룹 방은 유지
        assertThat(groupRoomActiveCount(soloGroup1.groupId())).isZero();
        assertThat(groupRoomActiveCount(soloGroup2.groupId())).isZero();
        assertThat(groupRoomActiveCount(sharedGroup.groupId())).isEqualTo(1);

        // assert : userA 본인 soft delete
        UserModel reloaded = userJpaRepository.findById(userA).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
    }

    private void ensureGroupRoom(Long groupId) {
        if (groupRoomActiveCount(groupId) == 0) {
            jdbcTemplate.update(
                    "INSERT INTO chat_room (type, group_id, owner_user_id) VALUES ('GROUP', ?, NULL)",
                    groupId);
        }
    }

    private Object groupDeletedAt(Long groupId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT deleted_at FROM groups WHERE id = ?", groupId);
        return row.get("deleted_at");
    }

    private Integer groupRoomActiveCount(Long groupId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_room WHERE group_id = ? AND type = 'GROUP' AND deleted_at IS NULL",
                Integer.class, groupId);
    }
}
