package com.wherewego.domain.group;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GroupMemberTest {

    private static final Long GROUP_ID = 10L;
    private static final Long USER_ID = 7L;

    @DisplayName("활성 멤버를 생성할 때,")
    @Nested
    class CreateActive {

        @DisplayName("joinedAt = now, leftAt = null, isActive() = true 가 된다.")
        @Test
        void createActive_setsFields() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");

            // act
            GroupMember member = GroupMember.createActive(GROUP_ID, USER_ID, now);

            // assert
            assertThat(member.getGroupId()).isEqualTo(GROUP_ID);
            assertThat(member.getUserId()).isEqualTo(USER_ID);
            assertThat(member.getJoinedAt()).isEqualTo(now);
            assertThat(member.getLeftAt()).isNull();
            assertThat(member.isActive()).isTrue();
        }
    }

    @DisplayName("멤버를 탈퇴 처리할 때,")
    @Nested
    class MarkLeft {

        @DisplayName("leftAt 이 기록되고 isActive() = false 가 된다.")
        @Test
        void markLeft_setsLeftAt() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            GroupMember member = GroupMember.createActive(GROUP_ID, USER_ID, now);
            Instant leftAt = now.plusSeconds(3600);

            // act
            member.markLeft(leftAt);

            // assert
            assertThat(member.getLeftAt()).isEqualTo(leftAt);
            assertThat(member.isActive()).isFalse();
        }

        @DisplayName("두 번째 호출은 leftAt 을 변경하지 않는다 (멱등).")
        @Test
        void markLeft_idempotent() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            GroupMember member = GroupMember.createActive(GROUP_ID, USER_ID, now);
            Instant firstLeftAt = now.plusSeconds(3600);
            member.markLeft(firstLeftAt);

            // act
            member.markLeft(firstLeftAt.plusSeconds(60));

            // assert: 최초 호출 시점 유지
            assertThat(member.getLeftAt()).isEqualTo(firstLeftAt);
        }
    }

    @DisplayName("isActive 메서드는,")
    @Nested
    class IsActive {

        @DisplayName("leftAt 이 null 이면 true, set 되면 false 를 반환한다.")
        @Test
        void isActive_reflectsLeftAt() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            GroupMember member = GroupMember.createActive(GROUP_ID, USER_ID, now);

            // assert - leftAt null
            assertThat(member.isActive()).isTrue();

            // act - 탈퇴
            member.markLeft(now.plusSeconds(60));

            // assert - leftAt set
            assertThat(member.isActive()).isFalse();
        }
    }
}
