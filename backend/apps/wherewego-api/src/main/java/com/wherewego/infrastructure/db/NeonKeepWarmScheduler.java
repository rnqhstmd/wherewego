package com.wherewego.infrastructure.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Neon suspend(scale-to-zero) 방지용 keep-warm 스케줄러.
 *
 * <p>활성 윈도우({@code [activeStartHour, activeEndHour)}) 동안 주기적으로 {@code SELECT 1} 을 실행해
 * Neon 무료 티어 컴퓨트가 유휴로 suspend 되는 것을 막는다. 콜드 스타트로 인한 로그인 502 완화 목적.</p>
 *
 * <p>{@code db.keep-warm.enabled=true} 일 때만 빈이 생성되어 스케줄이 등록된다 (기본 OFF — 무료 컴퓨트 한도 보호).
 * 윈도우 밖이면 핑을 건너뛰며(BR-1), 예외는 로그 후 삼켜 다음 주기에 재실행한다.</p>
 */
@Component
@ConditionalOnProperty(prefix = "db.keep-warm", name = "enabled", havingValue = "true")
public class NeonKeepWarmScheduler {

    private static final Logger log = LoggerFactory.getLogger(NeonKeepWarmScheduler.class);

    private final DataSource dataSource;
    private final int activeStartHour;
    private final int activeEndHour;
    private final Clock clock;

    public NeonKeepWarmScheduler(
            DataSource dataSource,
            @Value("${db.keep-warm.active-start-hour:7}") int activeStartHour,
            @Value("${db.keep-warm.active-end-hour:23}") int activeEndHour,
            @Value("${db.keep-warm.zone:Asia/Seoul}") String zone) {
        this(dataSource, activeStartHour, activeEndHour, Clock.system(ZoneId.of(zone)));
    }

    /** 테스트 전용 — 결정적 시각 고정을 위해 {@link Clock} 을 직접 주입한다. */
    NeonKeepWarmScheduler(DataSource dataSource, int activeStartHour, int activeEndHour, Clock clock) {
        this.dataSource = dataSource;
        this.activeStartHour = activeStartHour;
        this.activeEndHour = activeEndHour;
        this.clock = clock;
    }

    @Scheduled(fixedRateString = "${db.keep-warm.interval-ms:240000}")
    public void keepWarm() {
        int hour = LocalTime.now(clock).getHour();
        if (hour < activeStartHour || hour >= activeEndHour) {
            return; // 윈도우 밖 — skip (BR-1).
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            log.debug("Neon keep-warm ping ok hour={}", hour);
        } catch (Exception e) {
            // 핑 실패는 로그 후 삼킴 — 다음 주기에 재실행 (BR-5).
            log.warn("Neon keep-warm ping failed cause={}", e.getMessage());
        }
    }
}
