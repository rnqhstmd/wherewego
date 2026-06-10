package com.wherewego.domain.device;

import com.wherewego.infrastructure.user.UserJpaRepository;
import com.wherewego.domain.user.UserModel;
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
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #118 리뷰 반영: 디바이스 등록 upsert 의 race-safe insert(ON CONFLICT DO NOTHING) 경로 검증.
 *
 * <p>기존 save+catch 폴백은 참여 트랜잭션 rollback-only 마킹으로 작동 불능이었다 — native
 * {@code insertIfAbsent} 쿼리의 문법/동작을 실제 PostgreSQL 위에서 검증한다(신규 등록·재등록 touch·
 * 기존 행 존재 시 멱등). {@link com.wherewego.domain.chat.GroupChatServiceIT} 픽스처 패턴을 따른다.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class DeviceServiceIT {

    private static final String TOKEN = "test-device-token-001";

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userA;

    @BeforeEach
    void setUp() {
        truncateAll();
        userA = userJpaRepository.save(UserModel.create(60000001L, "userA", null)).getId();
    }

    @AfterEach
    void tearDown() {
        truncateAll();
    }

    private void truncateAll() {
        jdbcTemplate.execute("DELETE FROM devices");
        userJpaRepository.deleteAll();
    }

    @DisplayName("register - 신규 토큰은 ON CONFLICT insert 경로로 활성 행 1개가 생성된다.")
    @Test
    void register_createsActiveDevice() {
        Device device = deviceService.register(userA, DevicePlatform.IOS, TOKEN);

        assertThat(device.getUserId()).isEqualTo(userA);
        assertThat(activeCount(userA, TOKEN)).isEqualTo(1);
    }

    @DisplayName("register - 동일 (user, token) 재등록은 행을 늘리지 않고 touch 만 한다(AC-7 멱등).")
    @Test
    void register_isIdempotentForSameToken() {
        deviceService.register(userA, DevicePlatform.IOS, TOKEN);
        deviceService.register(userA, DevicePlatform.IOS, TOKEN);

        assertThat(activeCount(userA, TOKEN)).isEqualTo(1);
    }

    @DisplayName("insertIfAbsent - 활성 행이 이미 있으면 0을 반환하고 예외 없이 멱등하다(race 흡수).")
    @Test
    @Transactional // @Modifying native 쿼리는 트랜잭션 필수(리포지토리 직접 호출이라 서비스 @Transactional 미경유)
    void insertIfAbsent_isRaceSafe() {
        assertThat(deviceRepository.insertIfAbsent(userA, DevicePlatform.IOS, TOKEN)).isEqualTo(1);
        // race 패자 시뮬레이션 — 같은 (user, token) 재삽입은 충돌 예외 없이 0 행.
        assertThat(deviceRepository.insertIfAbsent(userA, DevicePlatform.IOS, TOKEN)).isZero();
        assertThat(activeCount(userA, TOKEN)).isEqualTo(1);
    }

    private Integer activeCount(Long userId, String token) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM devices WHERE user_id = ? AND device_token = ? AND deleted_at IS NULL",
                Integer.class, userId, token);
    }
}
