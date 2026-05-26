package com.wherewego.domain.auth;

import com.wherewego.application.auth.AuthResultInfo;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.auth.jwt.RefreshTokenHasher;
import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카카오 로그인의 DB 쓰기 부분만 담당.
 * AuthService.loginWithKakao는 외부 HTTP 호출을 포함하므로 @Transactional을 붙이면
 * Kakao API 응답을 기다리는 동안 커넥션을 점유한다 → 풀 고갈 위험.
 * 이 클래스는 외부 I/O 없이 순수 DB 작업만 수행하므로 트랜잭션 범위가 최소화된다.
 *
 * 단일 세션 정책: 동시 로그인 시 마지막 커밋의 refresh token hash만 DB에 유지된다
 * (last-writer-wins). 다중 세션이 필요하면 refresh token 저장 모델을 분리해야 한다.
 */
@Component
@RequiredArgsConstructor
public class UserLoginPersistence {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenHasher refreshTokenHasher;

    /**
     * Backoff 정책 — 두 예외의 회복 시간 분포에 맞춰 exponential backoff 적용:
     *  - DataIntegrityViolationException (동시 첫 로그인 race): 수십~수백 ms 안에 다른 TX 가 커밋 → 짧은 첫 대기로 충분
     *  - CannotCreateTransactionException (Neon cold start): 3~8s 회복 → 두 번째/세 번째 대기가 길어야 잡힘
     * 시도 사이 대기 800ms, 2000ms(cap) → 총 윈도우 약 14.8s 안에서 두 케이스 모두 회복 가능.
     */
    @Retryable(
            retryFor = {CannotCreateTransactionException.class, DataIntegrityViolationException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 800, multiplier = 2.5, maxDelay = 3000)
    )
    @Transactional
    public AuthResultInfo upsertAndIssueTokens(Long kakaoUserId, String nickname, String profileImageUrl) {
        UserModel user = userRepository.findByKakaoUserId(kakaoUserId)
                .map(existing -> {
                    if (!existing.isActive()) {
                        throw new CoreException(ErrorType.AUTH_USER_DEACTIVATED);
                    }
                    existing.updateProfile(nickname, profileImageUrl);
                    return existing;
                })
                // saveAndFlush로 즉시 flush → 동시 최초 로그인 race 시 DataIntegrityViolationException 조기 감지.
                // 위반 시 트랜잭션이 rollback-only가 되므로 catch 후 복구가 불가능하다.
                // @Retryable이 예외를 잡아 새 트랜잭션으로 재시도 → findByKakaoUserId가 기존 사용자 반환.
                .orElseGet(() -> userRepository.saveAndFlush(
                        UserModel.create(kakaoUserId, nickname, profileImageUrl)));

        String accessRaw = jwtTokenProvider.issueAccessToken(user.getId());
        String refreshRaw = jwtTokenProvider.issueRefreshToken(user.getId());

        user.replaceRefreshTokenHash(refreshTokenHasher.sha256Hex(refreshRaw));
        userRepository.save(user);

        return AuthResultInfo.of(user, accessRaw, refreshRaw);
    }

    /**
     * @Retryable 소진 시 호출. 두 예외 모두 사용자에겐 동일하게
     * "잠시 후 다시 로그인" 안내로 변환 — raw DataIntegrityViolation/
     * CannotCreateTransaction 이 5xx 일반 오류로 노출되는 회귀를 막는다.
     */
    @Recover
    public AuthResultInfo recoverCannotCreateTransaction(
            CannotCreateTransactionException e,
            Long kakaoUserId, String nickname, String profileImageUrl) {
        throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED, "잠시 후 다시 로그인해 주세요.");
    }

    @Recover
    public AuthResultInfo recoverDataIntegrityViolation(
            DataIntegrityViolationException e,
            Long kakaoUserId, String nickname, String profileImageUrl) {
        throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED, "잠시 후 다시 로그인해 주세요.");
    }

    /**
     * retryFor 외 예외(CoreException 등) 는 원본 그대로 전파.
     * Spring Retry 는 retryable 하지 않은 예외도 RecoveryHandler 를 거치며,
     * 매칭되는 @Recover 가 없으면 ExhaustedRetryException 으로 wrap 해 원본을 묻어버린다.
     * fallback 으로 원본 RuntimeException/Error 를 그대로 다시 던져 글로벌 핸들러가 받게 한다.
     */
    @Recover
    public AuthResultInfo recoverNonRetryable(
            Throwable e,
            Long kakaoUserId, String nickname, String profileImageUrl) {
        if (e instanceof RuntimeException re) throw re;
        if (e instanceof Error err) throw err;
        throw new RuntimeException(e);
    }
}
