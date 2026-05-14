package com.wherewego.migration;

import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestContainersConfig.class)
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allTablesCreatedWithExpectedColumns() {
        Set<String> expectedTables = Set.of("users", "groups", "group_members", "pins", "bot_user_mappings");

        List<String> actualTables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class
        );

        assertThat(actualTables).containsAll(expectedTables);

        Set<String> usersColumns = columnNames("users");
        assertThat(usersColumns).containsExactlyInAnyOrder(
                "id", "kakao_user_id", "nickname", "profile_image_url",
                "refresh_token", "created_at", "updated_at", "deleted_at"
        );

        Set<String> groupsColumns = columnNames("groups");
        assertThat(groupsColumns).containsExactlyInAnyOrder(
                "id", "name", "created_at", "updated_at", "deleted_at"
        );

        Set<String> groupMembersColumns = columnNames("group_members");
        assertThat(groupMembersColumns).containsExactlyInAnyOrder(
                "id", "group_id", "user_id", "joined_at", "left_at", "created_at", "updated_at"
        );

        Set<String> pinsColumns = columnNames("pins");
        assertThat(pinsColumns).containsExactlyInAnyOrder(
                "id", "group_id", "place_name", "address", "latitude", "longitude",
                "instagram_url", "tag", "memo", "memo_source",
                "created_by", "created_at", "updated_at", "deleted_at"
        );

        Set<String> botUserMappingsColumns = columnNames("bot_user_mappings");
        assertThat(botUserMappingsColumns).containsExactlyInAnyOrder(
                "id", "bot_user_key", "user_id", "linked_at"
        );
    }

    @Test
    void pinsCheckConstraintsExist() {
        List<String> checkConstraints = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'pins'::regclass AND contype = 'c'",
                String.class
        );

        assertThat(checkConstraints).contains("chk_pins_tag", "chk_pins_memo_source");
    }

    @Test
    void pinsUniqueConstraintsExist() {
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT conname, connullsnotdistinct FROM pg_constraint "
                        + "WHERE conrelid = 'pins'::regclass AND contype = 'u' AND conname = 'uq_pins_group_instagram'"
        );

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("connullsnotdistinct")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void pinsTagCheckConstraintRejectsInvalidValue() {
        long userId = insertUser();
        long groupId = insertGroup();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO pins (group_id, place_name, latitude, longitude, tag, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                groupId, "test", 37.0, 127.0, "INVALID", userId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pinsUniqueNullsNotDistinctRejectsDuplicate() {
        long userId = insertUser();
        long groupId = insertGroup();

        jdbcTemplate.update(
                "INSERT INTO pins (group_id, place_name, latitude, longitude, instagram_url, tag, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                groupId, "place1", 37.0, 127.0, "https://instagram.com/p/abc", "PLACE", userId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO pins (group_id, place_name, latitude, longitude, instagram_url, tag, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                groupId, "place2", 37.0, 127.0, "https://instagram.com/p/abc", "PLACE", userId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pinsNullInstagramUrlAllowsMultiple() {
        long userId = insertUser();
        long groupId = insertGroup();

        jdbcTemplate.update(
                "INSERT INTO pins (group_id, place_name, latitude, longitude, tag, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                groupId, "place1", 37.0, 127.0, "PLACE", userId
        );
        jdbcTemplate.update(
                "INSERT INTO pins (group_id, place_name, latitude, longitude, tag, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                groupId, "place2", 37.1, 127.1, "PLACE", userId
        );

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pins WHERE group_id = ? AND instagram_url IS NULL",
                Integer.class, groupId
        );
        assertThat(count).isGreaterThanOrEqualTo(2);
    }

    private Set<String> columnNames(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ?",
                String.class, tableName
        ).stream().collect(Collectors.toSet());
    }

    private long insertUser() {
        // kakao_user_id는 외부 카카오 ID로 users_id_seq와 무관. 테스트 간 UNIQUE 충돌 방지를 위해 랜덤 BIGINT 사용.
        long kakaoUserId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (kakao_user_id, nickname) VALUES (?, ?) RETURNING id",
                Long.class, kakaoUserId, "tester"
        );
    }

    private long insertGroup() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO groups (name) VALUES (?) RETURNING id",
                Long.class, "test-group"
        );
    }
}
