package com.wherewego.domain.auth;

import com.wherewego.application.auth.AuthResultInfo;
import com.wherewego.config.env.KakaoApiProperties;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.auth.jwt.JwtValidationResult;
import com.wherewego.domain.auth.jwt.RefreshTokenHasher;
import com.wherewego.domain.auth.kakao.KakaoLoginUrlGenerator;
import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.infrastructure.auth.apple.AppleIdentityTokenVerifier;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    @Mock
    private UserLoginPersistence userLoginPersistence;

    @Mock
    private AppleIdentityTokenVerifier appleVerifier;

    @Mock
    private KakaoApiProperties kakaoApiProperties;

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
        @DisplayName("카카오 API 응답을 파싱해 UserLoginPersistence에 위임하고 결과를 반환한다.")
        @Test
        void loginWithKakao_validResponse_delegatesAndReturns() {
            // arrange
            AuthResultInfo expected = new AuthResultInfo(1L, "닉네임", "http://img.example/p.png", "new-access", "new-refresh");
            when(userLoginPersistence.upsertAndIssueTokens(12345L, "닉네임", "http://img.example/p.png"))
                    .thenReturn(expected);

            // act
            AuthResultInfo result = authService.loginWithKakao("code-123");

            // assert
            verify(kakaoClient).exchangeCodeForToken("code-123");
            verify(kakaoClient).fetchUserInfo("kakao-access");
            verify(userLoginPersistence).upsertAndIssueTokens(12345L, "닉네임", "http://img.example/p.png");
            assertThat(result).isEqualTo(expected);
        }

        @DisplayName("카카오 닉네임이 없으면, AUTH_KAKAO_API_FAILED 예외가 발생하고 persistence에 위임하지 않는다.")
        @Test
        void loginWithKakao_noNickname_throwsWithoutDelegating() {
            // arrange
            when(kakaoClient.fetchUserInfo(anyString())).thenReturn(
                    new KakaoUserInfoResponse(12345L,
                            new KakaoUserInfoResponse.Properties(null, null, null), null));

            // act & assert
            assertThatThrownBy(() -> authService.loginWithKakao("code-123"))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.AUTH_KAKAO_API_FAILED);

            verify(userLoginPersistence, never()).upsertAndIssueTokens(any(), any(), any());
        }

        @DisplayName("UserLoginPersistence에서 예외가 발생하면 그대로 전파한다.")
        @Test
        void loginWithKakao_persistenceThrows_propagates() {
            // arrange
            when(userLoginPersistence.upsertAndIssueTokens(anyLong(), anyString(), anyString()))
                    .thenThrow(new CoreException(ErrorType.AUTH_USER_DEACTIVATED));

            // act & assert
            assertThatThrownBy(() -> authService.loginWithKakao("code-123"))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.AUTH_USER_DEACTIVATED);
        }

        @DisplayName("Bulkhead: 5건 동시 점유 중 6번째 호출은 ~3s 내 친화 에러로 빠르게 실패한다.")
        @Test
        void loginWithKakao_bulkheadOverflow_returnsFriendlyError() throws InterruptedException, ExecutionException {
            // arrange: 5건이 upsertAndIssueTokens 내부에서 블록되어 permit 5개를 점유.
            CountDownLatch entered = new CountDownLatch(5);
            CountDownLatch release = new CountDownLatch(1);
            AuthResultInfo expected = new AuthResultInfo(1L, "닉네임", "http://img.example/p.png", "new-access", "new-refresh");
            when(userLoginPersistence.upsertAndIssueTokens(anyLong(), anyString(), anyString()))
                    .thenAnswer(inv -> {
                        entered.countDown();
                        release.await();
                        return expected;
                    });

            ExecutorService es = Executors.newFixedThreadPool(6);
            try {
                for (int i = 0; i < 5; i++) {
                    es.submit(() -> authService.loginWithKakao("code-123"));
                }
                assertThat(entered.await(5, TimeUnit.SECONDS))
                        .as("5 holders must enter before testing overflow").isTrue();

                // act: 6번째는 tryAcquire(3s) timeout → 친화 에러.
                long startNs = System.nanoTime();
                Future<AuthResultInfo> sixth = es.submit(() -> authService.loginWithKakao("code-123"));
                Throwable cause;
                try {
                    sixth.get(6, TimeUnit.SECONDS);
                    throw new AssertionError("6번째 호출은 실패해야 한다");
                } catch (ExecutionException ee) {
                    cause = ee.getCause();
                } catch (java.util.concurrent.TimeoutException te) {
                    throw new AssertionError("6번째 호출이 6s 내 반환되지 않음 — Bulkhead timeout 미동작", te);
                }
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

                assertThat(cause).isInstanceOf(CoreException.class)
                        .extracting("errorType").isEqualTo(ErrorType.AUTH_KAKAO_API_FAILED);
                assertThat(elapsedMs)
                        .as("Bulkhead tryAcquire timeout ≈ 3s (CI 흔들림 흡수 상한)")
                        .isBetween(2500L, 5000L);
            } finally {
                release.countDown();
                es.shutdown();
                //noinspection ResultOfMethodCallIgnored
                es.awaitTermination(5, TimeUnit.SECONDS);
            }
        }

        @DisplayName("Bulkhead: 예외 경로에서도 permit 이 항상 release 된다 (누수 없음).")
        @Test
        void loginWithKakao_bulkheadReleasesOnException() {
            // arrange: 매 호출이 persistence 에서 예외 → release 가 정상 동작해야 모두 동일 예외.
            when(userLoginPersistence.upsertAndIssueTokens(anyLong(), anyString(), anyString()))
                    .thenThrow(new CoreException(ErrorType.AUTH_USER_DEACTIVATED));

            // act: 순차 10회. release 누수 시 6번째부터 AUTH_KAKAO_API_FAILED 가 섞여 나옴.
            for (int i = 0; i < 10; i++) {
                assertThatThrownBy(() -> authService.loginWithKakao("code-123"))
                        .as("iteration %d", i)
                        .isInstanceOf(CoreException.class)
                        .extracting("errorType").isEqualTo(ErrorType.AUTH_USER_DEACTIVATED);
            }
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
