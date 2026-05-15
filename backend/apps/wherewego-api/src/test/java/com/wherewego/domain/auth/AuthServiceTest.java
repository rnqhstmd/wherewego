package com.wherewego.domain.auth;

import com.wherewego.application.auth.AuthResultInfo;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.auth.jwt.JwtValidationResult;
import com.wherewego.domain.auth.jwt.RefreshTokenHasher;
import com.wherewego.domain.auth.kakao.KakaoLoginUrlGenerator;
import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.infrastructure.auth.kakao.KakaoOAuthClient;
import com.wherewego.infrastructure.auth.kakao.KakaoTokenResponse;
import com.wherewego.infrastructure.auth.kakao.KakaoUserInfoResponse;
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

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock
    private KakaoOAuthClient kakaoClient;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenHasher refreshTokenHasher;

    @Mock
    private KakaoLoginUrlGenerator kakaoLoginUrlGenerator;

    @InjectMocks
    private AuthService authService;

    private KakaoTokenResponse kakaoTokenResponse;
    private KakaoUserInfoResponse kakaoUserInfo;

    @BeforeEach
    void setUp() {
        kakaoTokenResponse = new KakaoTokenResponse("kakao-access", "bearer", 3600L, "kakao-refresh", "profile");
        kakaoUserInfo = new KakaoUserInfoResponse(
                12345L,
                new KakaoUserInfoResponse.Properties("닉네임", "http://img.example/p.png", null),
                null
        );
        when(kakaoClient.exchangeCodeForToken(anyString())).thenReturn(kakaoTokenResponse);
        when(kakaoClient.fetchUserInfo(anyString())).thenReturn(kakaoUserInfo);
        when(jwtTokenProvider.issueAccessToken(anyLong())).thenReturn("new-access");
        when(jwtTokenProvider.issueRefreshToken(anyLong())).thenReturn("new-refresh");
        when(refreshTokenHasher.sha256Hex(anyString())).thenAnswer(inv -> "hash-of:" + inv.getArgument(0));
    }

    @DisplayName("카카오 로그인을 처리할 때,")
    @Nested
    class LoginWithKakao {
        @DisplayName("신규 사용자면, 사용자를 저장하고 AuthResultInfo를 반환한다.")
        @Test
        void loginWithKakao_newUser_createsAndIssuesTokens() {
            // arrange
            when(userRepository.findByKakaoUserId(12345L)).thenReturn(Optional.empty());
            UserModel savedUser = spy(UserModel.create(12345L, "닉네임", "http://img.example/p.png"));
            when(userRepository.save(any(UserModel.class))).thenReturn(savedUser);

            // act
            AuthResultInfo result = authService.loginWithKakao("code-123");

            // assert
            verify(userRepository, times(2)).save(any(UserModel.class));
            assertThat(result.accessToken()).isEqualTo("new-access");
            assertThat(result.refreshToken()).isEqualTo("new-refresh");
            assertThat(result.nickname()).isEqualTo("닉네임");
        }

        @DisplayName("기존 활성 사용자면, 프로필을 갱신한다.")
        @Test
        void loginWithKakao_existingActiveUser_updatesProfile() {
            // arrange
            UserModel existing = spy(UserModel.create(12345L, "OldName", "old.png"));
            when(userRepository.findByKakaoUserId(12345L)).thenReturn(Optional.of(existing));
            when(userRepository.save(any(UserModel.class))).thenReturn(existing);

            // act
            authService.loginWithKakao("code-123");

            // assert
            verify(existing).updateProfile("닉네임", "http://img.example/p.png");
        }

        @DisplayName("기존 비활성(탈퇴) 사용자면, AUTH_USER_DEACTIVATED 예외가 발생한다.")
        @Test
        void loginWithKakao_deactivatedUser_throwsDeactivated() {
            // arrange
            UserModel existing = UserModel.create(12345L, "닉", null);
            existing.delete();
            when(userRepository.findByKakaoUserId(12345L)).thenReturn(Optional.of(existing));

            // act & assert
            assertThatThrownBy(() -> authService.loginWithKakao("code-123"))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.AUTH_USER_DEACTIVATED);
        }
    }

    @DisplayName("토큰을 리프레시할 때,")
    @Nested
    class RefreshTokens {
        @DisplayName("유효한 refresh token이면, 신규 토큰을 발급하고 해시를 교체한다.")
        @Test
        void refreshTokens_validRefresh_rotates() {
            // arrange
            String rawRefresh = "valid-refresh";
            String storedHash = "hash-of:" + rawRefresh;
            UserModel user = spy(UserModel.create(12345L, "닉", null));
            user.replaceRefreshTokenHash(storedHash);

            when(jwtTokenProvider.parseRefreshToken(rawRefresh))
                    .thenReturn(new JwtValidationResult.Valid(7L, Instant.now().plusSeconds(60)));
            when(userRepository.findById(7L)).thenReturn(Optional.of(user));
            when(userRepository.save(any(UserModel.class))).thenReturn(user);

            // act
            AuthResultInfo result = authService.refreshTokens(rawRefresh);

            // assert
            assertThat(result.accessToken()).isEqualTo("new-access");
            assertThat(result.refreshToken()).isEqualTo("new-refresh");
            verify(user).replaceRefreshTokenHash(eq("hash-of:new-refresh"));
        }

        @DisplayName("typ이 잘못된 토큰이면, AUTH_REFRESH_TOKEN_INVALID 예외가 발생한다.")
        @Test
        void refreshTokens_invalidTyp_throws() {
            // arrange
            when(jwtTokenProvider.parseRefreshToken("x")).thenReturn(JwtValidationResult.Invalid.INVALID_TYPE);

            // act & assert
            assertThatThrownBy(() -> authService.refreshTokens("x"))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.AUTH_REFRESH_TOKEN_INVALID);
        }

        @DisplayName("만료된 토큰이면, AUTH_REFRESH_TOKEN_INVALID 예외가 발생한다.")
        @Test
        void refreshTokens_expired_throws() {
            // arrange
            when(jwtTokenProvider.parseRefreshToken("x")).thenReturn(JwtValidationResult.Invalid.EXPIRED);

            // act & assert
            assertThatThrownBy(() -> authService.refreshTokens("x"))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.AUTH_REFRESH_TOKEN_INVALID);
        }

        @DisplayName("DB의 해시와 다르면, AUTH_REFRESH_TOKEN_INVALID 예외가 발생한다.")
        @Test
        void refreshTokens_hashMismatch_throws() {
            // arrange
            String rawRefresh = "valid-refresh";
            UserModel user = UserModel.create(12345L, "닉", null);
            user.replaceRefreshTokenHash("hash-of:other-refresh");

            when(jwtTokenProvider.parseRefreshToken(rawRefresh))
                    .thenReturn(new JwtValidationResult.Valid(7L, Instant.now().plusSeconds(60)));
            when(userRepository.findById(7L)).thenReturn(Optional.of(user));

            // act & assert
            assertThatThrownBy(() -> authService.refreshTokens(rawRefresh))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.AUTH_REFRESH_TOKEN_INVALID);
        }

        @DisplayName("DB의 사용자가 비활성이면, AUTH_USER_DEACTIVATED 예외가 발생한다.")
        @Test
        void refreshTokens_deactivatedUser_throws() {
            // arrange
            String rawRefresh = "valid-refresh";
            UserModel user = UserModel.create(12345L, "닉", null);
            user.delete();

            when(jwtTokenProvider.parseRefreshToken(rawRefresh))
                    .thenReturn(new JwtValidationResult.Valid(7L, Instant.now().plusSeconds(60)));
            when(userRepository.findById(7L)).thenReturn(Optional.of(user));

            // act & assert
            assertThatThrownBy(() -> authService.refreshTokens(rawRefresh))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.AUTH_USER_DEACTIVATED);
        }

        @DisplayName("null/빈 토큰이면, AUTH_REFRESH_TOKEN_INVALID 예외가 발생한다.")
        @Test
        void refreshTokens_nullToken_throws() {
            // act & assert
            assertThatThrownBy(() -> authService.refreshTokens(null))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.AUTH_REFRESH_TOKEN_INVALID);
        }
    }

    @DisplayName("로그아웃을 처리할 때,")
    @Nested
    class Logout {
        @DisplayName("사용자가 존재하면, refresh token hash를 clear한다.")
        @Test
        void logout_existingUser_clearsHash() {
            // arrange
            UserModel user = spy(UserModel.create(12345L, "닉", null));
            when(userRepository.findById(7L)).thenReturn(Optional.of(user));

            // act
            authService.logout(7L);

            // assert
            verify(user).clearRefreshTokenHash();
        }

        @DisplayName("사용자가 존재하지 않으면, 아무 동작도 하지 않는다.")
        @Test
        void logout_nonExistingUser_noOp() {
            // arrange
            when(userRepository.findById(7L)).thenReturn(Optional.empty());

            // act
            authService.logout(7L);

            // assert
            verify(userRepository, never()).save(any(UserModel.class));
        }
    }
}
