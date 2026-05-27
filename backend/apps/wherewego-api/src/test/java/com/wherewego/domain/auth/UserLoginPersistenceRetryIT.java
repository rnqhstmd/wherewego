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
    }

    @Autowired
    UserLoginPersistence persistence;
    @Autowired
    UserRepository userRepository;
    @Autowired
    JwtTokenProvider jwtTokenProvider;
    @Autowired
    RefreshTokenHasher refreshTokenHasher;

    @BeforeEach
    void resetMocks() {
        // 컨텍스트가 클래스 단위로 캐싱되므로 매 테스트 직전 mock 상태를 초기화한다.
        Mockito.reset(userRepository, jwtTokenProvider, refreshTokenHasher);
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
    }

    @Test
    @DisplayName("Bulkhead: 5건 동시 점유 중 6번째 호출은 ~3s 내 친화 에러로 빠르게 실패한다.")
    void bulkhead_overflowReturnsFriendlyError() throws InterruptedException, ExecutionException {
        // arrange: 5건이 동시에 메서드 내부에서 블록되어 permit 5개를 점유하게 한다.
        CountDownLatch entered = new CountDownLatch(5);
        CountDownLatch release = new CountDownLatch(1);
        UserModel existing = UserModel.create(1L, "닉네임", "img.png");

        when(userRepository.findByKakaoUserId(1L)).thenAnswer(inv -> {
            entered.countDown();
            release.await();          // 5건은 여기서 대기 → permit 점유 유지
            return Optional.of(existing);
        });
        when(userRepository.save(any())).thenReturn(existing);

        ExecutorService es = Executors.newFixedThreadPool(6);
        try {
            for (int i = 0; i < 5; i++) {
                es.submit(() -> persistence.upsertAndIssueTokens(1L, "닉네임", "img.png"));
            }
            // 5건 모두 mock 내부에 진입(=permit 점유 확정)할 때까지 대기.
            assertThat(entered.await(5, TimeUnit.SECONDS))
                    .as("5 holders must enter before testing overflow").isTrue();

            // act: 6번째 호출은 tryAcquire(3s) 가 timeout → 친화 에러.
            long startNs = System.nanoTime();
            Future<AuthResultInfo> sixth = es.submit(() ->
                    persistence.upsertAndIssueTokens(1L, "닉네임", "img.png"));
            Throwable cause;
            try {
                sixth.get(6, TimeUnit.SECONDS);
                throw new AssertionError("6th call should have failed");
            } catch (ExecutionException ee) {
                cause = ee.getCause();
            } catch (java.util.concurrent.TimeoutException te) {
                throw new AssertionError("6th call did not return within 6s — Bulkhead timeout 미동작 의심", te);
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

            // assert: 친화 에러 + tryAcquire(3s) 시간 범위.
            assertThat(cause).isInstanceOf(CoreException.class)
                    .extracting("errorType").isEqualTo(ErrorType.AUTH_KAKAO_API_FAILED);
            assertThat(elapsedMs)
                    .as("Bulkhead tryAcquire timeout ≈ 3s (느슨한 상한으로 CI 흔들림 흡수)")
                    .isBetween(2500L, 5000L);
        } finally {
            release.countDown();   // 점유 5건 풀어 ExecutorService 정리 가능하게.
            es.shutdown();
            //noinspection ResultOfMethodCallIgnored
            es.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Bulkhead: 예외 경로에서도 permit 이 항상 release 된다 (누수 없음).")
    void bulkhead_releasesOnException() {
        // arrange: 매 호출이 비활성 사용자 → CoreException(AUTH_USER_DEACTIVATED) 던짐.
        UserModel deleted = UserModel.create(1L, "닉네임", "img.png");
        deleted.delete();
        when(userRepository.findByKakaoUserId(1L)).thenReturn(Optional.of(deleted));

        // act: 순차 호출 10회. release 가 정상이라면 매번 permit 0→5 가 회수되어 모두 동일 예외.
        // release 누수 시 6번째 호출부터 tryAcquire(3s) timeout → AUTH_KAKAO_API_FAILED 가 섞여 나옴.
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> persistence.upsertAndIssueTokens(1L, "닉네임", "img.png"))
                    .as("iteration %d", i)
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType").isEqualTo(ErrorType.AUTH_USER_DEACTIVATED);
        }
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
}
