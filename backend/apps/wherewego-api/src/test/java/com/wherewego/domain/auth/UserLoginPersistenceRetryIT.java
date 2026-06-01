package com.wherewego.domain.auth;

import com.wherewego.application.auth.AuthResultInfo;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.auth.jwt.RefreshTokenHasher;
import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.infrastructure.auth.LoginRetryListener;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Retryable / @Recover AOP 가 실제로 발동하는지 검증한다.
 * 단위 테스트({@link UserLoginPersistenceTest}) 는 MockitoExtension 기반이라 AOP 가 동작하지 않아
 * 재시도/recover 경로가 검증되지 않는 한계를 보완한다.
 *
 * <p>가벼운 Spring 슬라이스 — DataSource/Flyway 없이 @EnableRetry + UserLoginPersistence 만 로드.
 * mock 빈을 @Configuration 에 직접 정의해 컨텍스트 캐싱이 가능하다.
 */
@SpringJUnitConfig
@Import(UserLoginPersistenceRetryIT.RetryTestConfig.class)
class UserLoginPersistenceRetryIT {

    @Configuration
    @EnableRetry(order = Ordered.LOWEST_PRECEDENCE - 1)
    static class RetryTestConfig {
        @Bean
        UserRepository userRepository() {
            return Mockito.mock(UserRepository.class);
        }

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return Mockito.mock(JwtTokenProvider.class);
        }

        @Bean
        RefreshTokenHasher refreshTokenHasher() {
            return Mockito.mock(RefreshTokenHasher.class);
        }

        @Bean
        UserLoginPersistence userLoginPersistence(UserRepository userRepository,
                                                  JwtTokenProvider jwtTokenProvider,
                                                  RefreshTokenHasher refreshTokenHasher) {
            return new UserLoginPersistence(userRepository, jwtTokenProvider, refreshTokenHasher);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        // listeners = "loginRetryListener" 빈 lookup 만족용. retry 발동 시 counter 발급 검증도 함께.
        @Bean("loginRetryListener")
        LoginRetryListener loginRetryListener(MeterRegistry meterRegistry) {
            return new LoginRetryListener(meterRegistry);
        }
    }

    @Autowired
    UserLoginPersistence persistence;
    @Autowired
    UserRepository userRepository;
    @Autowired
    JwtTokenProvider jwtTokenProvider;
    @Autowired
    RefreshTokenHasher refreshTokenHasher;
    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void resetMocks() {
        // 컨텍스트가 클래스 단위로 캐싱되므로 매 테스트 직전 mock/메트릭 상태를 초기화한다.
        Mockito.reset(userRepository, jwtTokenProvider, refreshTokenHasher);
        meterRegistry.clear();
        when(jwtTokenProvider.issueAccessToken(anyLong())).thenReturn("access-token");
        when(jwtTokenProvider.issueRefreshToken(anyLong())).thenReturn("refresh-token");
        when(refreshTokenHasher.sha256Hex(anyString())).thenReturn("hash");
    }

    @Test
    @DisplayName("1차 DataIntegrityViolation 후 2차에서 기존 사용자를 발견하면 정상 토큰을 발급한다.")
    void retry_recoversFromRace() {
        // arrange: 1차 호출은 신규로 보이고, 2차에서는 race winner 가 만들어둔 사용자가 보임.
        UserModel existing = UserModel.create(1L, "닉네임", "img.png");
        when(userRepository.findByKakaoUserId(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(userRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));
        when(userRepository.save(any())).thenReturn(existing);

        // act
        AuthResultInfo result = persistence.upsertAndIssueTokens(1L, "닉네임", "img.png");

        // assert
        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        verify(userRepository, times(2)).findByKakaoUserId(1L);  // 재시도 발동 증거
        assertThat(attempts("DataIntegrityViolationException")).isEqualTo(1.0);
        assertThat(meterRegistry.find("auth.login.retry.exhausted").counter()).isNull();
    }

    @Test
    @DisplayName("1차 CannotCreateTransactionException 발생 후 2차에서 정상 처리한다.")
    void retry_recoversFromColdStart() {
        // arrange: 1차에서 Neon cold start 시뮬레이션 (커넥션 못 만듦), 2차에서 정상 사용자 반환.
        UserModel existing = UserModel.create(1L, "닉네임", "img.png");
        when(userRepository.findByKakaoUserId(1L))
                .thenThrow(new CannotCreateTransactionException("neon cold start"))
                .thenReturn(Optional.of(existing));
        when(userRepository.save(any())).thenReturn(existing);

        // act
        AuthResultInfo result = persistence.upsertAndIssueTokens(1L, "닉네임", "img.png");

        // assert
        assertThat(result).isNotNull();
        assertThat(result.accessToken()).isEqualTo("access-token");
        verify(userRepository, times(2)).findByKakaoUserId(1L);
    }

    @Test
    @DisplayName("재시도(2회)가 모두 실패하면 @Recover 가 친화 메시지 CoreException 으로 변환한다.")
    void retryExhausted_recoverConvertsToFriendlyError() {
        // arrange: 모든 시도에서 DataIntegrityViolation.
        when(userRepository.findByKakaoUserId(1L)).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        // act & assert
        assertThatThrownBy(() -> persistence.upsertAndIssueTokens(1L, "닉네임", "img.png"))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("잠시 후 다시 로그인해 주세요.")
                .extracting("errorType").isEqualTo(ErrorType.AUTH_KAKAO_API_FAILED);
        verify(userRepository, times(2)).findByKakaoUserId(1L);  // maxAttempts=2 검증
        // onError 는 실패한 시도마다 호출(=2회), close(lastThrowable) 는 모든 시도 소진 후 1회 호출
        assertThat(attempts("DataIntegrityViolationException")).isEqualTo(2.0);
        assertThat(exhausted("DataIntegrityViolationException")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("retryFor 에 없는 예외(AUTH_USER_DEACTIVATED)는 재시도하지 않고 즉시 전파한다.")
    void nonRetryableException_propagatesImmediately() {
        // arrange: 탈퇴(비활성) 사용자 → CoreException(AUTH_USER_DEACTIVATED).
        UserModel deleted = UserModel.create(1L, "닉네임", "img.png");
        deleted.delete();
        when(userRepository.findByKakaoUserId(1L)).thenReturn(Optional.of(deleted));

        // act & assert
        assertThatThrownBy(() -> persistence.upsertAndIssueTokens(1L, "닉네임", "img.png"))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.AUTH_USER_DEACTIVATED);
        verify(userRepository, times(1)).findByKakaoUserId(1L);  // 재시도 없음
    }

    // ------------------------------------------------------------------
    // P1: upsertByOauthAndIssueTokens(NativeLoginCommand) — 신규 @Recover 시그니처 검증.
    // 기존 3인자 @Recover 와 인자 1개(NativeLoginCommand) 로 매칭 모호성 없이 동작함을 실증한다(설계 MUST#1).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("(a) Oauth 경로: 1차 DataIntegrityViolation 후 2차 재조회로 race 를 회복해 토큰을 발급한다.")
    void oauthRetry_recoversFromRace() {
        // arrange: 1차 신규로 보이나 saveAndFlush 가 DIV, 2차에서 race winner 가 만든 Apple 사용자 발견.
        NativeLoginCommand cmd = NativeLoginCommand.apple("apple-sub-1", "Apple 사용자", null);
        UserModel existing = UserModel.createOauth(
                com.wherewego.domain.user.OauthProvider.APPLE, "apple-sub-1", "Apple 사용자", null, null);
        when(userRepository.findByOauthProviderAndOauthId(
                com.wherewego.domain.user.OauthProvider.APPLE, "apple-sub-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(userRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));
        when(userRepository.save(any())).thenReturn(existing);

        // act
        AuthResultInfo result = persistence.upsertByOauthAndIssueTokens(cmd);

        // assert
        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        verify(userRepository, times(2)).findByOauthProviderAndOauthId(
                com.wherewego.domain.user.OauthProvider.APPLE, "apple-sub-1");  // 재시도 발동 증거
        assertThat(attempts("DataIntegrityViolationException")).isEqualTo(1.0);
        assertThat(meterRegistry.find("auth.login.retry.exhausted").counter()).isNull();
    }

    @Test
    @DisplayName("(b) Oauth 경로: 재시도(2회) 소진 시 신규 @Recover 가 provider 무관 503 으로 변환한다.")
    void oauthRetryExhausted_recoverConvertsToTemporarilyUnavailable() {
        // arrange: 모든 시도에서 DataIntegrityViolation.
        NativeLoginCommand cmd = NativeLoginCommand.apple("apple-sub-2", "Apple 사용자", null);
        when(userRepository.findByOauthProviderAndOauthId(
                com.wherewego.domain.user.OauthProvider.APPLE, "apple-sub-2"))
                .thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        // act & assert: APPLE 경로여도 JWKS 무관 — provider-agnostic AUTH_LOGIN_TEMPORARILY_UNAVAILABLE(503).
        assertThatThrownBy(() -> persistence.upsertByOauthAndIssueTokens(cmd))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.AUTH_LOGIN_TEMPORARILY_UNAVAILABLE);
        verify(userRepository, times(2)).findByOauthProviderAndOauthId(
                com.wherewego.domain.user.OauthProvider.APPLE, "apple-sub-2");  // maxAttempts=2
        assertThat(attempts("DataIntegrityViolationException")).isEqualTo(2.0);
        assertThat(exhausted("DataIntegrityViolationException")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("(b') Oauth 경로: CannotCreateTransactionException 소진도 provider 무관 503 으로 변환한다.")
    void oauthRetryExhausted_coldStart_recoverConvertsToTemporarilyUnavailable() {
        // arrange: 모든 시도에서 트랜잭션 생성 실패(Neon cold start).
        NativeLoginCommand cmd = NativeLoginCommand.kakao(777L, "닉네임", "img.png");
        when(userRepository.findByOauthProviderAndOauthId(
                com.wherewego.domain.user.OauthProvider.KAKAO, "777"))
                .thenThrow(new CannotCreateTransactionException("neon cold start"));

        // act & assert
        assertThatThrownBy(() -> persistence.upsertByOauthAndIssueTokens(cmd))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.AUTH_LOGIN_TEMPORARILY_UNAVAILABLE);
        verify(userRepository, times(2)).findByOauthProviderAndOauthId(
                com.wherewego.domain.user.OauthProvider.KAKAO, "777");
    }

    @Test
    @DisplayName("(c) Oauth 경로: 비즈니스 예외(AUTH_USER_DEACTIVATED)는 @Recover 가 삼키지 않고 즉시 전파한다.")
    void oauthNonRetryableException_propagatesImmediately() {
        // arrange: 탈퇴 Apple 계정 → CoreException(AUTH_USER_DEACTIVATED).
        NativeLoginCommand cmd = NativeLoginCommand.apple("apple-sub-3", "Apple 사용자", null);
        UserModel deleted = UserModel.createOauth(
                com.wherewego.domain.user.OauthProvider.APPLE, "apple-sub-3", "Apple 사용자", null, null);
        deleted.delete();
        when(userRepository.findByOauthProviderAndOauthId(
                com.wherewego.domain.user.OauthProvider.APPLE, "apple-sub-3"))
                .thenReturn(Optional.of(deleted));

        // act & assert: 원본 코드 보존(503 으로 변질되지 않음).
        assertThatThrownBy(() -> persistence.upsertByOauthAndIssueTokens(cmd))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.AUTH_USER_DEACTIVATED);
        verify(userRepository, times(1)).findByOauthProviderAndOauthId(
                com.wherewego.domain.user.OauthProvider.APPLE, "apple-sub-3");  // 재시도 없음
    }

    private double attempts(String exceptionName) {
        return meterRegistry.get("auth.login.retry.attempts")
                .tag("exception", exceptionName)
                .counter().count();
    }

    private double exhausted(String exceptionName) {
        return meterRegistry.get("auth.login.retry.exhausted")
                .tag("exception", exceptionName)
                .counter().count();
    }
}
