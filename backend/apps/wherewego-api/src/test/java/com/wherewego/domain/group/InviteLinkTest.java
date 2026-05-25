package com.wherewego.domain.group;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InviteLinkTest {

    private static final Long GROUP_ID = 10L;
    private static final Long INVITER_ID = 7L;
    private static final String TOKEN = "11111111-2222-3333-4444-555555555555";
    private static final String SLUG = "Ab23CdEf";

    @DisplayName("초대 링크를 발급할 때,")
    @Nested
    class Issue {

        @DisplayName("expiresAt = now + ttl, slug 가 채워지고, acceptedAt = null, isPending() = true 가 된다.")
        @Test
        void issue_setsFields() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            Duration ttl = Duration.ofDays(7);

            // act
            InviteLink link = InviteLink.issue(GROUP_ID, INVITER_ID, TOKEN, SLUG, now, ttl);

            // assert
            assertThat(link.getGroupId()).isEqualTo(GROUP_ID);
            assertThat(link.getInviterId()).isEqualTo(INVITER_ID);
            assertThat(link.getToken()).isEqualTo(TOKEN);
            assertThat(link.getSlug()).isEqualTo(SLUG);
            assertThat(link.getExpiresAt()).isEqualTo(now.plus(ttl));
            assertThat(link.getAcceptedAt()).isNull();
            assertThat(link.isPending()).isTrue();
        }
    }

    @DisplayName("초대 링크를 수락 처리할 때,")
    @Nested
    class MarkAccepted {

        @DisplayName("acceptedAt 이 기록되고 isPending() = false 가 된다.")
        @Test
        void markAccepted_setsAcceptedAt() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            InviteLink link = InviteLink.issue(GROUP_ID, INVITER_ID, TOKEN, SLUG, now, Duration.ofDays(7));
            Instant acceptedAt = now.plusSeconds(60);

            // act
            link.markAccepted(acceptedAt);

            // assert
            assertThat(link.getAcceptedAt()).isEqualTo(acceptedAt);
            assertThat(link.isPending()).isFalse();
        }
    }

    @DisplayName("만료 여부를 확인할 때,")
    @Nested
    class IsExpired {

        @DisplayName("expiresAt 이 now 이후이면 false 를 반환한다.")
        @Test
        void isExpired_expiresAfterNow_returnsFalse() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            InviteLink link = InviteLink.issue(GROUP_ID, INVITER_ID, TOKEN, SLUG, now, Duration.ofHours(1));

            // act & assert
            assertThat(link.isExpired(now)).isFalse();
        }

        @DisplayName("expiresAt 이 now 와 동일하면 true 를 반환한다 (경계).")
        @Test
        void isExpired_expiresAtEqualsNow_returnsTrue() {
            // arrange: expiresAt = base + 0 = base
            Instant base = Instant.parse("2026-01-01T00:00:00Z");
            InviteLink link = InviteLink.issue(GROUP_ID, INVITER_ID, TOKEN, SLUG, base, Duration.ZERO);

            // act & assert: !expiresAt.isAfter(now) → !false → true
            assertThat(link.isExpired(base)).isTrue();
        }

        @DisplayName("expiresAt 이 now 이전이면 true 를 반환한다.")
        @Test
        void isExpired_expiresBeforeNow_returnsTrue() {
            // arrange
            Instant base = Instant.parse("2026-01-01T00:00:00Z");
            InviteLink link = InviteLink.issue(GROUP_ID, INVITER_ID, TOKEN, SLUG, base, Duration.ofMinutes(5));

            // act & assert
            assertThat(link.isExpired(base.plus(Duration.ofMinutes(10)))).isTrue();
        }
    }
}
