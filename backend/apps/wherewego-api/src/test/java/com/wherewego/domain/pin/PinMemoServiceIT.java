package com.wherewego.domain.pin;

import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.user.UserModel;
import com.wherewego.infrastructure.pin.PinJpaRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PinMemoService 통합 테스트. JPQL Modifying 쿼리의 race-safe 조건부 UPDATE 동작을 실제 DB 로 검증.
 *
 * <p>memoSource MANUAL/AUTO/NULL 3종에 대해 {@code updateAutoMemoIfNotManual} 행위 차이를 검증한다 (AC-14, AC-15).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class PinMemoServiceIT {

    @Autowired
    private PinMemoService pinMemoService;

    @Autowired
    private PinJpaRepository pinJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long groupId;

    @BeforeEach
    void cleanUp() {
        truncateAll();

        UserModel user = userJpaRepository.save(UserModel.create(7777777777L, "memo-tester", null));
        this.userId = user.getId();
        this.groupId = jdbcTemplate.queryForObject(
                "INSERT INTO groups (name) VALUES (?) RETURNING id",
                Long.class, "memo-group"
        );
    }

    @AfterEach
    void tearDown() {
        truncateAll();
    }

    private void truncateAll() {
        // Phase 10 V009: notifications.visit_pin_id → pins(id) FK 추가로 인해 pins 보다 먼저 삭제 필요.
        jdbcTemplate.execute("DELETE FROM notification_pins");
        jdbcTemplate.execute("DELETE FROM notifications");
        jdbcTemplate.execute("DELETE FROM pins");
        jdbcTemplate.execute("DELETE FROM group_members");
        jdbcTemplate.execute("DELETE FROM groups");
        jdbcTemplate.execute("DELETE FROM bot_link_codes");
        jdbcTemplate.execute("DELETE FROM bot_user_mappings");
        userJpaRepository.deleteAll();
    }

    private Long savePin(String instagramUrl) {
        PlaceSearchHit hit = new PlaceSearchHit("kakao-1", "장소", "주소", 37.5, 127.0);
        return pinJpaRepository.saveAndFlush(Pin.autoFromInstagram(groupId, userId, hit, instagramUrl)).getId();
    }

    @DisplayName("attachAutoMemoIfWithinWindow - memo_source=NULL 인 핀에 호출하면 memo/memoSource=AUTO 로 갱신되어 true 를 반환한다 (AC-14).")
    @Test
    void attachAutoMemo_memoSourceIsNull_updates() {
        // arrange
        Long pinId = savePin("https://www.instagram.com/p/MEMO_NULL/");

        // act
        boolean result = pinMemoService.attachAutoMemoIfWithinWindow(pinId, userId, "맛있어요");

        // assert
        assertThat(result).isTrue();
        String memo = jdbcTemplate.queryForObject(
                "SELECT memo FROM pins WHERE id = ?", String.class, pinId);
        String memoSource = jdbcTemplate.queryForObject(
                "SELECT memo_source FROM pins WHERE id = ?", String.class, pinId);
        assertThat(memo).isEqualTo("맛있어요");
        assertThat(memoSource).isEqualTo("AUTO");
    }

    @DisplayName("attachAutoMemoIfWithinWindow - 이미 memo_source=AUTO 인 핀도 새 AUTO 메모로 덮어쓸 수 있다.")
    @Test
    void attachAutoMemo_memoSourceIsAuto_overwritesWithAuto() {
        // arrange
        Long pinId = savePin("https://www.instagram.com/p/MEMO_AUTO/");
        pinMemoService.attachAutoMemoIfWithinWindow(pinId, userId, "첫 메모");

        // act
        boolean result = pinMemoService.attachAutoMemoIfWithinWindow(pinId, userId, "새 메모");

        // assert
        assertThat(result).isTrue();
        String memo = jdbcTemplate.queryForObject(
                "SELECT memo FROM pins WHERE id = ?", String.class, pinId);
        String memoSource = jdbcTemplate.queryForObject(
                "SELECT memo_source FROM pins WHERE id = ?", String.class, pinId);
        assertThat(memo).isEqualTo("새 메모");
        assertThat(memoSource).isEqualTo("AUTO");
    }

    @DisplayName("attachAutoMemoIfWithinWindow - memo_source=MANUAL 인 핀에는 갱신 0행으로 false 반환, 기존 memo 가 보존된다 (AC-15).")
    @Test
    void attachAutoMemo_memoSourceIsManual_blocked() {
        // arrange
        Long pinId = savePin("https://www.instagram.com/p/MEMO_MANUAL/");
        // MANUAL 메모를 직접 INSERT (도메인 API 가 MANUAL 진입을 노출하지 않으므로 JDBC 직접 설정)
        jdbcTemplate.update(
                "UPDATE pins SET memo = ?, memo_source = 'MANUAL' WHERE id = ?",
                "수동입력", pinId
        );

        // act
        boolean result = pinMemoService.attachAutoMemoIfWithinWindow(pinId, userId, "자동덮어쓰기");

        // assert
        assertThat(result).isFalse();
        String memo = jdbcTemplate.queryForObject(
                "SELECT memo FROM pins WHERE id = ?", String.class, pinId);
        String memoSource = jdbcTemplate.queryForObject(
                "SELECT memo_source FROM pins WHERE id = ?", String.class, pinId);
        assertThat(memo).isEqualTo("수동입력");
        assertThat(memoSource).isEqualTo("MANUAL");
    }
}
