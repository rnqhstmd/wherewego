package com.wherewego.infrastructure.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NeonKeepWarmSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    private NeonKeepWarmScheduler scheduler(int hour) {
        Instant fixed = ZonedDateTime.of(2026, 5, 30, hour, 0, 0, 0, KST).toInstant();
        Clock clock = Clock.fixed(fixed, KST);
        return new NeonKeepWarmScheduler(dataSource, 7, 23, clock);
    }

    @Test
    void 윈도우_밖이면_DataSource_를_호출하지_않는다() throws SQLException {
        NeonKeepWarmScheduler scheduler = scheduler(3); // 03시 KST — 윈도우 [7,23) 밖

        scheduler.keepWarm();

        verify(dataSource, never()).getConnection();
    }

    @Test
    void 윈도우_안이면_SELECT_1_을_실행한다() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        NeonKeepWarmScheduler scheduler = scheduler(12); // 12시 KST — 윈도우 안

        scheduler.keepWarm();

        verify(statement).execute("SELECT 1");
    }

    @Test
    void DataSource_예외시_전파하지_않고_삼킨다() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("cold start"));
        NeonKeepWarmScheduler scheduler = scheduler(12); // 12시 KST — 윈도우 안

        assertDoesNotThrow(scheduler::keepWarm);
    }

    @Test
    void 런타임_예외시에도_전파하지_않고_삼킨다() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("pool closed"));
        NeonKeepWarmScheduler scheduler = scheduler(12); // 12시 KST — 윈도우 안

        assertDoesNotThrow(scheduler::keepWarm);
    }

    @Test
    void 시작_경계_7시는_윈도우_안이다() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        NeonKeepWarmScheduler scheduler = scheduler(7); // 07시 — [7,23) 시작 경계(포함)

        scheduler.keepWarm();

        verify(statement).execute("SELECT 1");
    }

    @Test
    void 종료_경계_23시는_윈도우_밖이다() throws SQLException {
        NeonKeepWarmScheduler scheduler = scheduler(23); // 23시 — [7,23) 종료 경계(미포함)

        scheduler.keepWarm();

        verify(dataSource, never()).getConnection();
    }
}
