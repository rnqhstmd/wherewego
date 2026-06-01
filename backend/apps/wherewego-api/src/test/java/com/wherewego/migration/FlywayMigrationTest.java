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

import javax.sql.DataSource;
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

    @Autowired
    private DataSource dataSource;

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
                "refresh_token", "created_at", "updated_at", "deleted_at",
                "cleanup_snoozed_until", // V012: 정리 배너 snooze
                "oauth_provider", "oauth_id", "email" // V014: OAuth provider 일반화
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
                "created_by", "created_at", "updated_at", "deleted_at",
                "memo_updated_by", // V008
                "visited_at",      // V010
                // V013: 추억핀 사진
                "photo_key", "photo_thumbnail_key", "photo_uploaded_by", "photo_uploaded_at"
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

    @Test
    void usersOauthUniqueConstraintsExist() {
        // V014 (AC-20): uq_users_oauth(신규) + uq_users_kakao_user_id(기존) 둘 다 유지.
        List<String> uniqueConstraints = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint "
                        + "WHERE conrelid = 'users'::regclass AND contype = 'u'",
                String.class
        );

        assertThat(uniqueConstraints).contains("uq_users_oauth", "uq_users_kakao_user_id");
    }

    @Test
    void usersKakaoUserIdColumnRetainedAndNullable() {
        // V014 (AC-20, BR-10): kakao_user_id 컬럼 유지 + Apple 행 대비 nullable 로 완화.
        String isNullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'kakao_user_id'",
                String.class
        );
        assertThat(isNullable).isEqualTo("YES");
    }

    @Test
    void migrationV014_sqlBackfillsOauthIdFromKakaoUserId() throws Exception {
        // V014 (AC-19): 기존 Kakao 행을 oauth_provider='KAKAO', oauth_id=kakao_user_id::text 로 백필하는지 정적 검증.
        // 컨테이너에서는 마이그레이션이 이미 적용 완료라 신규 INSERT 행은 백필 대상이 아니므로 SQL 정의로 검증한다.
        String sql;
        try (InputStream in = getClass()
                .getResourceAsStream("/db/migration/V014__generalize_oauth_provider.sql")) {
            assertThat(in).as("V014 마이그레이션 SQL 리소스").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // 컬럼 추가 + DEFAULT 'KAKAO'
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS oauth_provider VARCHAR(20) NOT NULL DEFAULT 'KAKAO'");
        // 백필 — oauth_id = kakao_user_id::text
        assertThat(sql).containsPattern(
                "UPDATE\\s+users\\s+SET\\s+oauth_id\\s*=\\s*kakao_user_id::text\\s+WHERE\\s+oauth_id\\s+IS\\s+NULL");
        // kakao_user_id NOT NULL 완화
        assertThat(sql).contains("ALTER COLUMN kakao_user_id DROP NOT NULL");
        // 신규 UNIQUE (oauth_provider, oauth_id)
        assertThat(sql).contains("ADD CONSTRAINT uq_users_oauth UNIQUE (oauth_provider, oauth_id)");
    }

    @Test
    void usersBackfilledKakaoRowHasMatchingOauthId() {
        // V014 (AC-19) 실제 동작: insertUser 가 (KAKAO, kakao_user_id::text) 로 저장 → 일치 확인.
        long userId = insertUser();

        String oauthProvider = jdbcTemplate.queryForObject(
                "SELECT oauth_provider FROM users WHERE id = ?", String.class, userId);
        String oauthId = jdbcTemplate.queryForObject(
                "SELECT oauth_id FROM users WHERE id = ?", String.class, userId);
        Long kakaoUserId = jdbcTemplate.queryForObject(
                "SELECT kakao_user_id FROM users WHERE id = ?", Long.class, userId);

        assertThat(oauthProvider).isEqualTo("KAKAO");
        assertThat(oauthId).isEqualTo(String.valueOf(kakaoUserId));
    }

    @Test
    void migrationV014_backfillsExistingKakaoRowInRealDb() {
        // V014 (AC-19) 실제 백필 검증: SQL 텍스트가 아니라 실제 DB 동작으로 확인한다.
        // 격리 스키마에서 V013 까지만 마이그레이션(oauth_* 컬럼 없는 상태) → Kakao 행 INSERT →
        // V014 적용 → oauth_id = kakao_user_id::text 로 백필됐는지 SELECT 검증.
        // (공유 컨테이너 public 스키마는 이미 V014 까지 적용돼 있어 기존 행 백필 시점을 재현할 수 없으므로
        //  별도 스키마에 독립 Flyway 를 구동한다.)
        String schema = "v014_backfill_" + ThreadLocalRandom.current().nextInt(1, 1_000_000);
        try {
            // 1) V013 까지만 마이그레이션 (oauth_provider/oauth_id/email 컬럼 미존재 상태).
            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .locations("classpath:db/migration")
                    .target("13")
                    .load()
                    .migrate();

            // 2) 기존 Kakao 유저 INSERT — V013 스키마는 oauth_* 컬럼이 없다.
            long kakaoUserId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
            jdbcTemplate.update(
                    "INSERT INTO " + schema + ".users (kakao_user_id, nickname) VALUES (?, ?)",
                    kakaoUserId, "기존카카오유저");

            // 3) V014 적용 → 기존 행 백필.
            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .locations("classpath:db/migration")
                    .target("14")
                    .load()
                    .migrate();

            // 4) 백필 검증: oauth_provider='KAKAO', oauth_id=kakao_user_id::text.
            String oauthProvider = jdbcTemplate.queryForObject(
                    "SELECT oauth_provider FROM " + schema + ".users WHERE kakao_user_id = ?",
                    String.class, kakaoUserId);
            String oauthId = jdbcTemplate.queryForObject(
                    "SELECT oauth_id FROM " + schema + ".users WHERE kakao_user_id = ?",
                    String.class, kakaoUserId);

            assertThat(oauthProvider).isEqualTo("KAKAO");
            assertThat(oauthId).isEqualTo(String.valueOf(kakaoUserId));
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void usersAppleRowWithNullKakaoUserIdInsertable() {
        // V014 (AC-22): Apple 행은 kakao_user_id NULL 로 INSERT 가능해야 한다.
        String appleSub = "apple-sub-" + ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        Long appleUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (nickname, oauth_provider, oauth_id, email) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, "Apple 사용자", "APPLE", appleSub, "relay@privaterelay.appleid.com"
        );

        assertThat(appleUserId).isNotNull();
        Long kakaoUserId = jdbcTemplate.queryForObject(
                "SELECT kakao_user_id FROM users WHERE id = ?", Long.class, appleUserId);
        assertThat(kakaoUserId).isNull();
    }

    @Test
    void usersOauthUniqueConstraintRejectsDuplicateProviderAndId() {
        // V014 (AC-20/21): (oauth_provider, oauth_id) 중복은 uq_users_oauth 로 차단.
        String appleSub = "apple-dup-" + ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        jdbcTemplate.update(
                "INSERT INTO users (nickname, oauth_provider, oauth_id) VALUES (?, ?, ?)",
                "Apple 사용자", "APPLE", appleSub
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO users (nickname, oauth_provider, oauth_id) VALUES (?, ?, ?)",
                "Apple 사용자2", "APPLE", appleSub
        )).isInstanceOf(DataIntegrityViolationException.class);
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
        // V014: oauth_provider/oauth_id NOT NULL — Kakao 유저로 oauth_id = kakao_user_id::text 채운다.
        long kakaoUserId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (kakao_user_id, nickname, oauth_provider, oauth_id) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, kakaoUserId, "tester", "KAKAO", String.valueOf(kakaoUserId)
        );
    }

    private long insertGroup() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO groups (name) VALUES (?) RETURNING id",
                Long.class, "test-group"
        );
    }
}
