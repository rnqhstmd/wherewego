package com.wherewego.domain.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BotLinkCodeTest {

    @DisplayName("코드를 발급할 때,")
    @Nested
    class Issue {
        @DisplayName("status=ACTIVE, issuedAt 설정, expiresAt = issuedAt + TTL 이 된다.")
        @Test
        void issue_setsActiveAndExpiry() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            Duration ttl = Duration.ofMinutes(5);

            // act
            BotLinkCode code = BotLinkCode.issue(1L, "123456", now, ttl);

            // assert
            assertThat(code.getStatus()).isEqualTo(BotLinkCodeStatus.ACTIVE);
            assertThat(code.getIssuedAt()).isEqualTo(now);
            assertThat(code.getExpiresAt()).isEqualTo(now.plus(ttl));
            assertThat(code.isActive()).isTrue();
        }
    }

    @DisplayName("코드를 소비 처리할 때,")
    @Nested
    class MarkConsumed {
        @DisplayName("status=CONSUMED 로 바뀌고 consumedAt 이 기록된다.")
        @Test
        void markConsumed_setsConsumedStatusAndTimestamp() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            BotLinkCode code = BotLinkCode.issue(1L, "123456", now, Duration.ofMinutes(5));
            Instant consumedAt = now.plusSeconds(60);

            // act
            code.markConsumed(consumedAt);

            // assert
            assertThat(code.getStatus()).isEqualTo(BotLinkCodeStatus.CONSUMED);
            assertThat(code.getConsumedAt()).isEqualTo(consumedAt);
        }
    }

    @DisplayName("코드를 만료 처리할 때,")
    @Nested
    class MarkExpired {
        @DisplayName("status=EXPIRED 로 바뀐다.")
        @Test
        void markExpired_setsExpiredStatus() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            BotLinkCode code = BotLinkCode.issue(1L, "123456", now, Duration.ofMinutes(5));

            // act
            code.markExpired();

            // assert
            assertThat(code.getStatus()).isEqualTo(BotLinkCodeStatus.EXPIRED);
            assertThat(code.isActive()).isFalse();
        }
    }

    @DisplayName("만료 여부를 확인할 때,")
    @Nested
    class IsExpired {
        @DisplayName("expiresAt 이 now 보다 이전이면 true 를 반환한다.")
        @Test
        void isExpired_pastExpiresAt_returnsTrue() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            BotLinkCode code = BotLinkCode.issue(1L, "123456", now, Duration.ofMinutes(5));

            // assert - 만료 전
            assertThat(code.isExpired(now.plusSeconds(60))).isFalse();

            // assert - 만료 후
            assertThat(code.isExpired(now.plus(Duration.ofMinutes(10)))).isTrue();
        }
    }
}
