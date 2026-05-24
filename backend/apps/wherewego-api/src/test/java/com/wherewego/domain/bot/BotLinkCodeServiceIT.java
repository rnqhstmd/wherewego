package com.wherewego.domain.bot;

import com.wherewego.domain.user.UserModel;
import com.wherewego.infrastructure.bot.BotLinkCodeJpaRepository;
import com.wherewego.infrastructure.user.UserJpaRepository;
import com.wherewego.infrastructure.bot.BotUserMappingJpaRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BotLinkCodeService 통합 테스트. 실제 PostgreSQL Testcontainer + JPA 매핑 동작 검증.
 *
 * <p>※ V001/V002의 Partial UNIQUE INDEX {@code uq_bot_link_codes_active_user WHERE status='ACTIVE'} 동작도 함께 확인한다.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class BotLinkCodeServiceIT {

    @Autowired
    private BotLinkCodeService botLinkCodeService;

    @Autowired
    private BotLinkCodeJpaRepository botLinkCodeJpaRepository;

    @Autowired
    private BotUserMappingJpaRepository botUserMappingJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void cleanUp() {
        truncateAll();
        UserModel saved = userJpaRepository.save(UserModel.create(1234567890L, "tester", null));
        this.userId = saved.getId();
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
        jdbcTemplate.execute("DELETE FROM group_members");
        jdbcTemplate.execute("DELETE FROM groups");
        botLinkCodeJpaRepository.deleteAll();
        botUserMappingJpaRepository.deleteAll();
        userJpaRepository.deleteAll();
    }

    @DisplayName("issueCode - 신규 발급 시 status=ACTIVE, expiresAt=now+10분으로 저장된다 (AC-1, AC-3).")
    @Test
    void issueCode_first_savesActive() {
        // arrange
        Instant before = Instant.now();

        // act
        BotLinkCodeIssueResult result = botLinkCodeService.issueCode(userId);

        // assert
        List<BotLinkCode> all = botLinkCodeJpaRepository.findAll();
        assertThat(all).hasSize(1);
        BotLinkCode saved = all.get(0);
        assertThat(saved.getStatus()).isEqualTo(BotLinkCodeStatus.ACTIVE);
        assertThat(saved.getCode()).isEqualTo(result.code());
        assertThat(saved.getUserId()).isEqualTo(userId);
        // 10분 TTL (application.yml: bot.link-code.ttl-minutes=10) — issuedAt + 10분 ≈ expiresAt
        long deltaSeconds = ChronoUnit.SECONDS.between(saved.getIssuedAt(), saved.getExpiresAt());
        assertThat(deltaSeconds).isEqualTo(Duration.ofMinutes(10).getSeconds());
        // 발급 시점은 호출 직전 ~ 직후 사이
        assertThat(saved.getIssuedAt()).isAfterOrEqualTo(before.minusSeconds(2));
    }

    @DisplayName("issueCode - 이미 ACTIVE 코드가 있으면 이전 행을 EXPIRED 로 만료시키고 신규 ACTIVE 1건만 남긴다 (AC-2).")
    @Test
    void issueCode_existingActive_expiresOldAndIssuesNew() {
        // arrange : 1차 발급
        BotLinkCodeIssueResult first = botLinkCodeService.issueCode(userId);

        // act : 2차 발급
        BotLinkCodeIssueResult second = botLinkCodeService.issueCode(userId);

        // assert : 1차 EXPIRED, 2차 ACTIVE
        List<BotLinkCode> all = botLinkCodeJpaRepository.findAll();
        assertThat(all).hasSize(2);
        BotLinkCode firstEntity = all.stream()
                .filter(e -> e.getCode().equals(first.code()))
                .findFirst().orElseThrow();
        BotLinkCode secondEntity = all.stream()
                .filter(e -> e.getCode().equals(second.code()))
                .findFirst().orElseThrow();
        assertThat(firstEntity.getStatus()).isEqualTo(BotLinkCodeStatus.EXPIRED);
        assertThat(secondEntity.getStatus()).isEqualTo(BotLinkCodeStatus.ACTIVE);

        // assert : Partial UNIQUE INDEX 검증 — status='ACTIVE' 행이 유저당 1건뿐임
        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bot_link_codes WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class, userId
        );
        assertThat(activeCount).isEqualTo(1);
    }

    @DisplayName("consumeCode - ACTIVE 코드를 정상 소비하면 status=CONSUMED, consumedAt 가 기록된다 (AC-4).")
    @Test
    void consumeCode_validActive_marksConsumed() {
        // arrange
        BotLinkCodeIssueResult issued = botLinkCodeService.issueCode(userId);
        Instant now = Instant.now();

        // act
        BotLinkCodeConsumeResult consumed = botLinkCodeService.consumeCode(issued.code(), now);

        // assert
        assertThat(consumed.userId()).isEqualTo(userId);
        assertThat(consumed.code()).isEqualTo(issued.code());

        BotLinkCode entity = botLinkCodeJpaRepository.findAll().get(0);
        assertThat(entity.getStatus()).isEqualTo(BotLinkCodeStatus.CONSUMED);
        assertThat(entity.getConsumedAt()).isNotNull();
    }
}
