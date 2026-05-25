package com.wherewego.migration;

import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allTablesCreatedWithExpectedColumns() {
        Set<String> expectedTables = Set.of("users", "groups", "group_members", "invite_links", "pins", "bot_user_mappings");

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
                "id", "group_id", "user_id", "joined_at", "left_at",
                "created_at", "updated_at", "deleted_at" // V004: BaseEntity 상속 일관성
        );

        Set<String> inviteLinksColumns = columnNames("invite_links");
        assertThat(inviteLinksColumns).containsExactlyInAnyOrder(
                "id", "group_id", "inviter_id", "token", "expires_at", "accepted_at",
                "created_at", "updated_at", "deleted_at", // V003: deleted_at 추가
                "slug" // V011: 단축 URL slug 추가
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
    void pinsTagCheckConstraintDefinitionMatchesPhase7Tags() {
        // Phase 7: chk_pins_tag 가 REEL/WISH/MEMORY 만 허용하고 PLACE 는 거부함을 정의 본문 수준에서 검증.
        String definition = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid = 'pins'::regclass AND conname = 'chk_pins_tag'",
                String.class
        );

        assertThat(definition).isNotNull();
        assertThat(definition).contains("'REEL'");
        assertThat(definition).contains("'WISH'");
        assertThat(definition).contains("'MEMORY'");
        assertThat(definition).doesNotContain("'PLACE'");
    }

    @Test
    void pinsUniqueConstraintsExist() {
        // 기본 UNIQUE 제약 존재만 검증한다. NULL 중복 허용 여부(NULLS DISTINCT)는
        // pinsNullInstagramUrlAllowsMultiple (AC-05)에서 실제 INSERT로 검증.
        List<String> constraintNames = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint "
                        + "WHERE conrelid = 'pins'::regclass AND contype = 'u'",
                String.class
        );

        // V005 (relax_pins_unique_to_include_place_name)에서 uq_pins_group_instagram → uq_pins_group_instagram_place 이름 변경됨.
        assertThat(constraintNames).contains("uq_pins_group_instagram_place");
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
    void pinsTagCheckConstraintRejectsPlaceValue() {
        // Phase 7: 레거시 PLACE 태그는 더 이상 허용되지 않는다 (REEL/WISH/MEMORY 만 허용).
        long userId = insertUser();
        long groupId = insertGroup();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO pins (group_id, place_name, latitude, longitude, tag, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                groupId, "test", 37.0, 127.0, "PLACE", userId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migrationV006_sqlContainsExpectedTransformations() throws Exception {
        // Phase 7 회귀 보호: V006 마이그레이션 SQL이 단일 합본 3단계(CHECK 확장 → PLACE→REEL → CHECK 축소)를 포함하는지 정적 검증.
        // CHECK 제약의 실제 동작은 pinsTagCheckConstraintRejectsPlaceValue + pinsTagCheckConstraintDefinitionMatchesPhase7Tags가 보장한다.
        String sql;
        try (InputStream in = getClass()
                .getResourceAsStream("/db/migration/V006__renew_tag_constraint_and_migrate.sql")) {
            assertThat(in).as("V006 마이그레이션 SQL 리소스").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // 1단계 — CHECK 일시 확장 (PLACE/REEL/WISH/MEMORY 모두 허용)
        assertThat(sql).contains("DROP CONSTRAINT IF EXISTS chk_pins_tag");
        assertThat(sql).contains("CHECK (tag IN ('PLACE', 'REEL', 'WISH', 'MEMORY'))");
        // 2단계 — PLACE → REEL 일괄 변환
        assertThat(sql).containsPattern("UPDATE\\s+pins\\s+SET\\s+tag\\s*=\\s*'REEL'\\s+WHERE\\s+tag\\s*=\\s*'PLACE'");
        // 3단계 — CHECK 최종 축소 (REEL/WISH/MEMORY만 허용)
        assertThat(sql).contains("CHECK (tag IN ('REEL', 'WISH', 'MEMORY'))");
    }

    @Test
    void pinsUniqueConstraintRejectsDuplicateNonNull() {
        long userId = insertUser();
        long groupId = insertGroup();

        // V005 이후 uq_pins_group_instagram_place는 (group_id, instagram_url, place_name) 3종 조합.
        // 동일 조합 재삽입 → 중복 차단되어야 한다.
        jdbcTemplate.update(
                "INSERT INTO pins (group_id, place_name, latitude, longitude, instagram_url, tag, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                groupId, "place1", 37.0, 127.0, "https://instagram.com/p/abc", "REEL", userId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO pins (group_id, place_name, latitude, longitude, instagram_url, tag, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                groupId, "place1", 37.0, 127.0, "https://instagram.com/p/abc", "REEL", userId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pinsNullInstagramUrlAllowsMultiple() {
        long userId = insertUser();
        long groupId = insertGroup();

        jdbcTemplate.update(
                "INSERT INTO pins (group_id, place_name, latitude, longitude, tag, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                groupId, "place1", 37.0, 127.0, "REEL", userId
        );
        jdbcTemplate.update(
                "INSERT INTO pins (group_id, place_name, latitude, longitude, tag, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                groupId, "place2", 37.1, 127.1, "REEL", userId
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
