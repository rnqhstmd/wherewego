package com.wherewego.domain.user;

import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserModelTest {

    @DisplayName("UserModel을 생성할 때,")
    @Nested
    class Create {
        @DisplayName("유효한 값이 주어지면, 필드가 정상적으로 매핑된다.")
        @Test
        void create_setsFields() {
            // act
            UserModel user = UserModel.create(1L, "닉네임", "http://img.example/p.png");

            // assert
            assertThat(user.getKakaoUserId()).isEqualTo(1L);
            assertThat(user.getNickname()).isEqualTo("닉네임");
            assertThat(user.getProfileImageUrl()).isEqualTo("http://img.example/p.png");
        }

        @DisplayName("kakaoUserId가 null이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void create_withNullKakaoUserId_throws() {
            // act & assert
            assertThatThrownBy(() -> UserModel.create(null, "닉", null))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("nickname이 공백이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void create_withBlankNickname_throws() {
            // act & assert
            assertThatThrownBy(() -> UserModel.create(1L, "  ", null))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("프로필을 갱신할 때,")
    @Nested
    class UpdateProfile {
        @DisplayName("nickname/profileImageUrl이 갱신된다.")
        @Test
        void updateProfile_updatesFields() {
            // arrange
            UserModel user = UserModel.create(1L, "OldName", "old.png");

            // act
            user.updateProfile("NewName", "new.png");

            // assert
            assertThat(user.getNickname()).isEqualTo("NewName");
            assertThat(user.getProfileImageUrl()).isEqualTo("new.png");
        }
    }

    @DisplayName("refresh token hash를 관리할 때,")
    @Nested
    class RefreshTokenHash {
        @DisplayName("replace 후 동일한 해시로 match하면 true, 다른 해시면 false를 반환한다.")
        @Test
        void replaceAndMatchRefreshTokenHash() {
            // arrange
            UserModel user = UserModel.create(1L, "닉", null);
            String hash = "a".repeat(64);

            // act
            user.replaceRefreshTokenHash(hash);

            // assert
            assertThat(user.matchesRefreshTokenHash(hash)).isTrue();
            assertThat(user.matchesRefreshTokenHash("b".repeat(64))).isFalse();
        }

        @DisplayName("clear 후에는 어떤 해시와도 match되지 않는다.")
        @Test
        void clearRefreshTokenHash_setsNull() {
            // arrange
            UserModel user = UserModel.create(1L, "닉", null);
            user.replaceRefreshTokenHash("a".repeat(64));

            // act
            user.clearRefreshTokenHash();

            // assert
            assertThat(user.matchesRefreshTokenHash("a".repeat(64))).isFalse();
            assertThat(user.getRefreshTokenHash()).isNull();
        }
    }

    @DisplayName("활성 상태를 확인할 때,")
    @Nested
    class IsActive {
        @DisplayName("신규 생성된 사용자는 active 상태이며, delete 후에는 비활성이 된다.")
        @Test
        void isActive_dependsOnDeletedAt() {
            // arrange
            UserModel user = UserModel.create(1L, "닉", null);

            // assert - 신규
            assertThat(user.isActive()).isTrue();

            // act - delete
            user.delete();

            // assert - 비활성
            assertThat(user.isActive()).isFalse();
        }
    }
}
