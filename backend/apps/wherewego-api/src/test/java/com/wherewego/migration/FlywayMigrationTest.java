package com.wherewego.migration;

import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

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
                "created_at", "updated_at", "deleted_at" // V003: deleted_at 추가
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

        assertThat(constraintNames).contains("uq_pins_group_instagram");
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
    void migrationV006_convertsPlaceToReel() {
        // Phase 7 회귀 보호: V005 시점의 PLACE 행이 V006 마이그레이션을 통해
        // 실제 REEL 로 변환되는 업그레이드 경로를 end-to-end 로 검증한다.
        //
        // 격리: 기본 public 스키마는 다른 테스트들이 V006 까지 마이그레이션된 상태이므로,
        // 별도 schema 를 만들고 그 schema 만 대상으로 별도 Flyway 인스턴스를 실행한다.
        // (Testcontainers 컨테이너는 공유하되 schema 격리로 데이터/제약 충돌 회피.)
        String schema = "migration_v006_test_" + Math.abs(ThreadLocalRandom.current().nextInt());
        String jdbcUrl = System.getProperty("datasource.postgres-jpa.main.jdbc-url");
        String username = System.getProperty("datasource.postgres-jpa.main.username");
        String password = System.getProperty("datasource.postgres-jpa.main.password");

        try {
            // 1) V005 상태까지 마이그레이션 (PLACE/MEMORY CHECK).
            Flyway flywayV5 = Flyway.configure()
                    .dataSource(jdbcUrl, username, password)
                    .schemas(schema)
                    .createSchemas(true)
                    .locations("classpath:db/migration")
                    .target("5")
                    .cleanDisabled(false)
                    .load();
            flywayV5.clean();
            flywayV5.migrate();

            // 2) V005 상태의 schema 에 PLACE 행 2건과 MEMORY 행 1건 seed.
            JdbcTemplate isolated = new JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(
                            jdbcUrl, username, password));
            isolated.execute("SET search_path TO " + schema);

            long userId = isolated.queryForObject(
                    "INSERT INTO users (kakao_user_id, nickname) VALUES (?, ?) RETURNING id",
                    Long.class,
                    ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE),
                    "v006-tester"
            );
            long groupId = isolated.queryForObject(
                    "INSERT INTO groups (name) VALUES (?) RETURNING id",
                    Long.class, "v006-test-group"
            );
            isolated.update(
                    "INSERT INTO pins (group_id, place_name, latitude, longitude, tag, created_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    groupId, "legacy-place-1", 37.0, 127.0, "PLACE", userId
            );
            isolated.update(
                    "INSERT INTO pins (group_id, place_name, latitude, longitude, tag, created_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    groupId, "legacy-place-2", 37.1, 127.1, "PLACE", userId
            );
            isolated.update(
                    "INSERT INTO pins (group_id, place_name, latitude, longitude, tag, created_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    groupId, "legacy-memory", 37.2, 127.2, "MEMORY", userId
            );

            Integer pre = isolated.queryForObject(
                    "SELECT COUNT(*) FROM pins WHERE tag = 'PLACE'", Integer.class);
            assertThat(pre).isEqualTo(2);

            // 3) V006 까지 마이그레이션 (PLACE → REEL 변환 + CHECK 축소).
            Flyway flywayV6 = Flyway.configure()
                    .dataSource(jdbcUrl, username, password)
                    .schemas(schema)
                    .locations("classpath:db/migration")
                    .load();
            flywayV6.migrate();

            // 4) PLACE 잔존 0, REEL ≥ 2, MEMORY 보존 단언.
            Integer placeCount = isolated.queryForObject(
                    "SELECT COUNT(*) FROM pins WHERE tag = 'PLACE'", Integer.class);
            Integer reelCount = isolated.queryForObject(
                    "SELECT COUNT(*) FROM pins WHERE tag = 'REEL'", Integer.class);
            Integer memoryCount = isolated.queryForObject(
                    "SELECT COUNT(*) FROM pins WHERE tag = 'MEMORY'", Integer.class);

            assertThat(placeCount).isZero();
            assertThat(reelCount).isGreaterThanOrEqualTo(2);
            assertThat(memoryCount).isEqualTo(1);

            // 5) CHECK 정의가 최종 형태(REEL/WISH/MEMORY)로 축소되었는지 정의 본문 검증.
            String definition = isolated.queryForObject(
                    "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                            + "WHERE conrelid = (?::regclass) AND conname = 'chk_pins_tag'",
                    String.class, schema + ".pins"
            );
            assertThat(definition).isNotNull();
            assertThat(definition).contains("'REEL'");
            assertThat(definition).contains("'WISH'");
            assertThat(definition).contains("'MEMORY'");
            assertThat(definition).doesNotContain("'PLACE'");
        } finally {
            // 격리 schema cleanup. 실패해도 컨테이너 종료 시 정리되므로 best-effort.
            try {
                jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    @Test
    void pinsUniqueConstraintRejectsDuplicateNonNull() {
        long userId = insertUser();
        long groupId = insertGroup();

        jdbcTemplate.update(
                "INSERT INTO pins (group_id, place_name, latitude, longitude, instagram_url, tag, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                groupId, "place1", 37.0, 127.0, "https://instagram.com/p/abc", "REEL", userId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO pins (group_id, place_name, latitude, longitude, instagram_url, tag, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                groupId, "place2", 37.0, 127.0, "https://instagram.com/p/abc", "REEL", userId
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
