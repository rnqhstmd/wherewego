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
     * Backoff 정책 — connection-timeout(10s) 에 맞춘 retry 예산:
     *  - DataIntegrityViolationException (동시 첫 로그인 race): 수십~수백 ms 안에 회복 → 500ms 1차 대기 충분
     *  - CannotCreateTransactionException (Neon cold start): 1차 10s timeout 동안 cold start 흡수 시도
     * 응답 시간 추정:
     *  - 일반(Neon active): 수백 ms 안에 1차 성공.
     *  - race 회복: ~0.5초 (1차 fail + 500ms + 2차 즉시 성공).
     *  - cold start 회복(worst): 약 20.5s (10s + 500ms + 10s). 카카오 OAuth 콜백 SLA 와 정합.
     *
     * 풀 점유 보호(Bulkhead)는 AuthService.loginWithKakao 호출부에 둔다.
     * 이 메서드에 두면 @Transactional 인터셉터가 Semaphore 보다 먼저 DataSource.getConnection() 을
     * 호출하므로 풀 고갈 상황에서 Semaphore 가 무효화된다.
     */
    @Retryable(
            retryFor = {CannotCreateTransactionException.class, DataIntegrityViolationException.class},
            maxAttempts = 2,
            backoff = @Backoff(delay = 500, multiplier = 2.0),
            listeners = "loginRetryListener"
    )
    @Transactional
    public AuthResultInfo upsertAndIssueTokens(Long kakaoUserId, String nickname, String profileImageUrl) {
        UserModel user = userRepository.findByKakaoUserIdAndDeletedAtIsNull(kakaoUserId)
                .map(existing -> {
                    // P2 FR-24: 활성 조회로 soft-delete 행은 미스되므로 사실상 도달 불가하나,
                    // 동시성(조회-삭제 race) 대비 방어적으로 유지한다.
                    if (!existing.isActive()) {
                        throw new CoreException(ErrorType.AUTH_USER_DEACTIVATED);
                    }
                    // GP-1 FR-7: 가입 시 1회만 수집 — 재로그인 시 카카오 프로필 동기화를 하지 않아 사용자 지정 프로필을 보존한다.
                    return existing;
                })
                // saveAndFlush로 즉시 flush → 동시 최초 로그인 race 시 DataIntegrityViolationException 조기 감지.
                // 위반 시 트랜잭션이 rollback-only가 되므로 catch 후 복구가 불가능하다.
                // @Retryable이 예외를 잡아 새 트랜잭션으로 재시도 → findByKakaoUserIdAndDeletedAtIsNull가 기존 사용자 반환.
                // P2 FR-24: 삭제 계정 재로그인 시 활성 조회 미스 → 여기서 신규 생성(재가입). partial unique index(V017)가
                //           활성 행 1개만 강제하므로 soft-delete 행과 충돌하지 않는다.
                .orElseGet(() -> userRepository.saveAndFlush(
                        UserModel.create(kakaoUserId, nickname, profileImageUrl)));

        return issueTokensFor(user);
    }

    /**
     * P1: 네이티브(Kakao access token / Apple identityToken) 로그인의 DB 쓰기.
     * (provider, oauthId) find-or-create 후 토큰 발급. 기존 {@link #upsertAndIssueTokens} 와
     * 동일한 트랜잭션/재시도 정책을 공유한다(공통 헬퍼 {@link #issueTokensFor}).
     *
     * <p>P2 FR-24(재가입 허용): 활성(deleted_at IS NULL) 조회 미스 시 신규 생성(재가입). 비활성 분기는
     *    동시성(조회-삭제 race) 대비 방어용으로, 활성 조회상 사실상 도달 불가.
     * <p>GP-1 FR-7: 기존 계정(Kakao/Apple 무관)은 재로그인 시 프로필을 갱신하지 않는다 — 가입 시 1회만 수집.
     * <p>AC-7: 동시 최초 로그인 race → DataIntegrityViolation → @Retryable 재시도.
     */
    @Retryable(
            retryFor = {CannotCreateTransactionException.class, DataIntegrityViolationException.class},
            maxAttempts = 2,
            backoff = @Backoff(delay = 500, multiplier = 2.0),
            listeners = "loginRetryListener"
    )
    @Transactional
    public AuthResultInfo upsertByOauthAndIssueTokens(NativeLoginCommand cmd) {
        UserModel user = userRepository.findByOauthProviderAndOauthIdAndDeletedAtIsNull(cmd.provider(), cmd.oauthId())
                .map(existing -> {
                    // P2 FR-24: 활성 조회로 soft-delete 행은 미스되므로 사실상 도달 불가하나,
                    // 동시성(조회-삭제 race) 대비 방어적으로 유지한다.
                    if (!existing.isActive()) {
                        throw new CoreException(ErrorType.AUTH_USER_DEACTIVATED); // 동시성 방어(FR-24상 도달 불가)
                    }
                    // GP-1 FR-7: 가입 시 1회만 수집 — 재로그인 시 카카오/애플 프로필 동기화를 하지 않아 사용자 지정 프로필을 보존한다.
                    return existing;
                })
                // P2 FR-24: 삭제 계정 재로그인 시 활성 조회 미스 → 신규 생성(재가입). partial unique index(V017)가
                //           활성 행 1개만 강제하므로 soft-delete 행과 충돌하지 않는다(빈 계정, AC-13/QE-2).
                .orElseGet(() -> userRepository.saveAndFlush(cmd.toNewUser()));

        return issueTokensFor(user);
    }

    /**
     * 공통 토큰 발급: access/refresh 발급 + refreshTokenHash 저장 + AuthResultInfo 조립.
     * 기존 Kakao 콜백 경로와 신규 네이티브 경로가 공유한다.
     */
    private AuthResultInfo issueTokensFor(UserModel user) {
        String accessRaw = jwtTokenProvider.issueAccessToken(user.getId());
        String refreshRaw = jwtTokenProvider.issueRefreshToken(user.getId());

        user.replaceRefreshTokenHash(refreshTokenHasher.sha256Hex(refreshRaw));
        userRepository.save(user);

        return AuthResultInfo.of(user, accessRaw, refreshRaw);
    }

    /**
     * @Recover 매칭 전략 (Spring Retry 는 most-specific 시그니처 우선 매칭):
     *  - retryFor 에 명시된 두 예외(CCT, DataIntegrityViolation) 가 재시도 소진 시 →
     *    아래 두 specific @Recover 가 잡아 사용자 친화 메시지(AUTH_KAKAO_API_FAILED) 로 변환.
     *  - 그 외 예외(CoreException 같은 비즈니스 예외) → fallback recoverNonRetryable 가 원본 그대로 전파,
     *    글로벌 ControllerAdvice 가 본래 ErrorType 으로 응답하게 한다.
     * 이 분리는 "회복 가능 인프라 장애는 친화 안내, 비즈니스 예외는 원본 보존" 정책을 표현한다.
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
     * retryFor 외 예외 fallback. Spring Retry 가 retryable 하지 않은 예외도 RecoveryHandler 를 거치며,
     * 매칭되는 @Recover 가 없으면 ExhaustedRetryException 으로 wrap 해 원본을 묻어버리는 동작을 회피한다.
     */
    @Recover
    public AuthResultInfo recoverNonRetryable(
            Throwable e,
            Long kakaoUserId, String nickname, String profileImageUrl) {
        if (e instanceof RuntimeException re) throw re;
        if (e instanceof Error err) throw err;
        // upsertAndIssueTokens 는 checked exception 을 던지지 않으므로 도달 불가.
        // 시그니처가 바뀌어 checked 가 가능해지면 즉시 fail-fast.
        throw new AssertionError("Unreachable: unexpected checked throwable from @Retryable target", e);
    }

    /**
     * P1: {@link #upsertByOauthAndIssueTokens} 전용 @Recover (인자 1개 = NativeLoginCommand).
     * 기존 3인자 @Recover 와 인자 개수·타입 모두 달라 Spring Retry 매칭 모호성이 없다.
     *
     * <p>retryFor(CCT/DataIntegrityViolation) 소진 시 provider 무관 일시적 서버 에러
     * (AUTH_LOGIN_TEMPORARILY_UNAVAILABLE, 503) 로 변환한다. CCT(트랜잭션 생성 실패)·
     * DIV(DB UNIQUE 동시성) 모두 provider 와 무관한 인프라/동시성 문제이므로, Apple 경로에서
     * JWKS 무관한 AUTH_APPLE_JWKS_UNAVAILABLE 오탐을 내지 않는다.
     * 그 외 예외(CoreException 등 비즈니스 예외) → 원본 그대로 전파(글로벌 advice 가 본래 코드로 응답).
     */
    @Recover
    public AuthResultInfo recoverOauthRetryable(
            CannotCreateTransactionException e, NativeLoginCommand cmd) {
        throw temporarilyUnavailable();
    }

    @Recover
    public AuthResultInfo recoverOauthRetryable(
            DataIntegrityViolationException e, NativeLoginCommand cmd) {
        throw temporarilyUnavailable();
    }

    @Recover
    public AuthResultInfo recoverOauthNonRetryable(Throwable e, NativeLoginCommand cmd) {
        if (e instanceof RuntimeException re) throw re;
        if (e instanceof Error err) throw err;
        throw new AssertionError("Unreachable: unexpected checked throwable from @Retryable target", e);
    }

    private CoreException temporarilyUnavailable() {
        return new CoreException(ErrorType.AUTH_LOGIN_TEMPORARILY_UNAVAILABLE);
    }
}
