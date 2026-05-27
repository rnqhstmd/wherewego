package com.wherewego.domain.auth;

import com.wherewego.application.auth.AuthResultInfo;
import com.wherewego.application.auth.KakaoLoginUrlInfo;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class AuthService {

    private final KakaoOAuthClient kakaoClient;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenHasher refreshTokenHasher;
    private final KakaoLoginUrlGenerator kakaoLoginUrlGenerator;
    private final UserLoginPersistence userLoginPersistence;

    /**
     * Bulkhead — 카카오 로그인 DB 작업이 HikariCP main pool(10) 을 전부 점유하지 못하도록
     * 동시 진입을 5건으로 제한해 일반 API 가 인증 retry 로 마비되는 것을 차단한다.
     *
     * UserLoginPersistence 내부가 아닌 호출부에 둔다: @Transactional/@Retryable 인터셉터가
     * DataSource.getConnection() 을 먼저 호출하므로, 메서드 본문 안에서 Semaphore 를 잡아도
     * 풀 고갈 상황에서는 acquire 전에 CannotCreateTransactionException 이 던져져 무효화된다.
     *
     * kakaoClient HTTP 호출(최대 4.5s) 범위는 의도적으로 제외 — permit 점유 시간을 최소화한다.
     */
    private static final int AUTH_BULKHEAD_PERMITS = 5;
    private static final long AUTH_BULKHEAD_WAIT_MS = 3_000L;
    private final Semaphore authBulkhead = new Semaphore(AUTH_BULKHEAD_PERMITS, true);

    public KakaoLoginUrlInfo getKakaoLoginUrl() {
        return new KakaoLoginUrlInfo(kakaoLoginUrlGenerator.generate());
    }

    // @Transactional 없음 — 카카오 외부 HTTP 호출(최대 3초)이 포함되므로 트랜잭션을 걸면
    // 그 시간 동안 커넥션을 점유해 풀이 고갈된다. DB 작업은 UserLoginPersistence에 위임.
    public AuthResultInfo loginWithKakao(String code) {
        KakaoTokenResponse tokenRes = kakaoClient.exchangeCodeForToken(code);
        KakaoUserInfoResponse userInfo = kakaoClient.fetchUserInfo(tokenRes.accessToken());

        Long kakaoUserId = userInfo.id();
        String nickname = userInfo.resolvedNickname();
        String profileImageUrl = userInfo.resolvedProfileImageUrl();

        if (nickname == null || nickname.isBlank()) {
            throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED, "카카오 닉네임을 가져올 수 없습니다.");
        }

        boolean acquired;
        try {
            acquired = authBulkhead.tryAcquire(AUTH_BULKHEAD_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED, "잠시 후 다시 로그인해 주세요.");
        }
        if (!acquired) {
            // 인증 동시 처리 한도(5) 초과. 일반 API 풀 보호를 위해 즉시 친화 에러.
            throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED, "잠시 후 다시 로그인해 주세요.");
        }
        try {
            return userLoginPersistence.upsertAndIssueTokens(kakaoUserId, nickname, profileImageUrl);
        } finally {
            authBulkhead.release();
        }
    }

    @Transactional
    public AuthResultInfo refreshTokens(String refreshTokenRaw) {
        if (refreshTokenRaw == null || refreshTokenRaw.isBlank()) {
            throw new CoreException(ErrorType.AUTH_REFRESH_TOKEN_INVALID);
        }

        JwtValidationResult result = jwtTokenProvider.parseRefreshToken(refreshTokenRaw);
        Long userId;
        if (result instanceof JwtValidationResult.Valid valid) {
            userId = valid.userId();
        } else {
            throw new CoreException(ErrorType.AUTH_REFRESH_TOKEN_INVALID);
        }

        UserModel user = userRepository.findById(userId)
                .orElseThrow(() -> new CoreException(ErrorType.AUTH_USER_NOT_FOUND));

        if (!user.isActive()) {
            throw new CoreException(ErrorType.AUTH_USER_DEACTIVATED);
        }

        if (!user.matchesRefreshTokenHash(refreshTokenHasher.sha256Hex(refreshTokenRaw))) {
            throw new CoreException(ErrorType.AUTH_REFRESH_TOKEN_INVALID);
        }

        String newAccess = jwtTokenProvider.issueAccessToken(userId);
        String newRefresh = jwtTokenProvider.issueRefreshToken(userId);

        user.replaceRefreshTokenHash(refreshTokenHasher.sha256Hex(newRefresh));
        userRepository.save(user);

        return AuthResultInfo.of(user, newAccess, newRefresh);
    }

    @Transactional
    public void logout(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.clearRefreshTokenHash();
            userRepository.save(user);
        });
    }
}
