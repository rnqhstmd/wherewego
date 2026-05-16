package com.wherewego.domain.group;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class GroupTest {

    @DisplayName("그룹을 생성할 때,")
    @Nested
    class Create {

        @DisplayName("name 필드가 설정된 Group 이 만들어진다.")
        @Test
        void create_setsName() {
            // act
            Group group = Group.create("우리커플");

            // assert
            assertThat(group.getName()).isEqualTo("우리커플");
            assertThat(group.getDeletedAt()).isNull();
        }
    }

    @DisplayName("그룹을 soft delete 할 때,")
    @Nested
    class MarkDeleted {

        @DisplayName("deletedAt 이 기록된다.")
        @Test
        void markDeleted_setsDeletedAt() {
            // arrange
            Group group = Group.create("우리커플");

            // act
            group.markDeleted();

            // assert
            assertThat(group.getDeletedAt()).isNotNull();
        }

        @DisplayName("두 번 호출해도 deletedAt 은 최초 호출 시점으로 유지된다 (멱등).")
        @Test
        void markDeleted_idempotent() {
            // arrange
            Group group = Group.create("우리커플");
            ZonedDateTime firstDeletedAt = ZonedDateTime.parse("2026-01-01T00:00:00Z");
            ReflectionTestUtils.setField(group, "deletedAt", firstDeletedAt);

            // act
            group.markDeleted();

            // assert
            assertThat(group.getDeletedAt()).isEqualTo(firstDeletedAt);
        }
    }
}
