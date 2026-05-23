package com.wherewego.domain.auth;

import com.wherewego.application.auth.AuthResultInfo;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.auth.jwt.RefreshTokenHasher;
import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserLoginPersistenceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenHasher refreshTokenHasher;

    @InjectMocks
    private UserLoginPersistence persistence;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.issueAccessToken(anyLong())).thenReturn("access-token");
        when(jwtTokenProvider.issueRefreshToken(anyLong())).thenReturn("refresh-token");
        when(refreshTokenHasher.sha256Hex(anyString())).thenAnswer(inv -> "hash:" + inv.getArgument(0));
    }

    @DisplayName("upsertAndIssueTokens를 호출할 때,")
    @Nested
    class UpsertAndIssueTokens {

        @DisplayName("신규 사용자면, 저장 후 토큰을 발급한다.")
        @Test
        void newUser_savesAndIssuesTokens() {
            // arrange
            UserModel saved = spy(UserModel.create(12345L, "닉네임", "img.png"));
            when(userRepository.findByKakaoUserId(12345L)).thenReturn(Optional.empty());
            when(userRepository.saveAndFlush(any())).thenReturn(saved);
            when(userRepository.save(any())).thenReturn(saved);

            // act
            AuthResultInfo result = persistence.upsertAndIssueTokens(12345L, "닉네임", "img.png");

            // assert
            verify(userRepository).saveAndFlush(any(UserModel.class));
            assertThat(result.accessToken()).isEqualTo("access-token");
            assertThat(result.refreshToken()).isEqualTo("refresh-token");
            assertThat(result.nickname()).isEqualTo("닉네임");
        }

        @DisplayName("기존 활성 사용자면, 프로필을 갱신하고 토큰을 발급한다.")
        @Test
        void existingActiveUser_updatesProfileAndIssuesTokens() {
            // arrange
            UserModel existing = spy(UserModel.create(12345L, "OldName", "old.png"));
            when(userRepository.findByKakaoUserId(12345L)).thenReturn(Optional.of(existing));
            when(userRepository.save(any())).thenReturn(existing);

            // act
            persistence.upsertAndIssueTokens(12345L, "NewName", "new.png");

            // assert
            verify(existing).updateProfile("NewName", "new.png");
        }

        @DisplayName("기존 비활성(탈퇴) 사용자면, AUTH_USER_DEACTIVATED 예외가 발생한다.")
        @Test
        void deactivatedUser_throwsDeactivated() {
            // arrange
            UserModel existing = UserModel.create(12345L, "닉", null);
            existing.delete();
            when(userRepository.findByKakaoUserId(12345L)).thenReturn(Optional.of(existing));

            // act & assert
            assertThatThrownBy(() -> persistence.upsertAndIssueTokens(12345L, "닉", null))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.AUTH_USER_DEACTIVATED);
        }

        @DisplayName("동시 최초 로그인으로 unique constraint 위반 시, AUTH_KAKAO_API_FAILED 예외가 발생한다.")
        @Test
        void concurrentFirstLogin_dataIntegrityViolation_throwsFriendlyError() {
            // arrange
            when(userRepository.findByKakaoUserId(12345L)).thenReturn(Optional.empty());
            when(userRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

            // act & assert
            assertThatThrownBy(() -> persistence.upsertAndIssueTokens(12345L, "닉네임", "img.png"))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.AUTH_KAKAO_API_FAILED);
        }
    }
}
