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
