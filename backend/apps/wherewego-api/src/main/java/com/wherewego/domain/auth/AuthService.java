package com.wherewego.domain.auth;

import com.wherewego.application.auth.AuthResultInfo;
import com.wherewego.application.auth.KakaoLoginUrlInfo;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.auth.jwt.JwtValidationResult;
import com.wherewego.domain.auth.jwt.RefreshTokenHasher;
import com.wherewego.domain.auth.kakao.KakaoLoginUrlGenerator;
import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.config.env.KakaoApiProperties;
import com.wherewego.infrastructure.auth.apple.AppleIdentityTokenVerifier;
import com.wherewego.infrastructure.auth.apple.AppleTokenClaims;
import com.wherewego.infrastructure.auth.kakao.KakaoAccessTokenInfoResponse;
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
    private final AppleIdentityTokenVerifier appleVerifier;
    private final KakaoApiProperties kakaoApiProperties;

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

    /**
     * P1: Kakao 네이티브 로그인 (FR-2, BR-3, AC-6/7/8).
     * 앱이 Kakao SDK access token 을 전달 → {@code /v2/user/me} 직접 검증(콜백과 동일 client 재사용).
     * 위변조 토큰 → 카카오 4xx → AUTH_KAKAO_API_FAILED(502, AC-8).
     * loginWithKakao 와 동일하게 외부 HTTP 는 트랜잭션 밖, DB 작업만 Bulkhead 점유.
     *
     * <p>앱 귀속 검증: fetchUserInfo 전에 {@code /v1/user/access_token_info} 의 app_id 가 우리 앱
     * 설정값과 일치하는지 확인한다 — 다른 카카오 앱에서 발급된 토큰으로 우리 서비스에 로그인하는 오용을
     * 차단한다. 불일치 시 우리 앱 토큰이 아니므로 401(AUTH_KAKAO_APP_MISMATCH)로 거부한다.
     */
    public AuthResultInfo loginWithKakaoNative(String kakaoAccessToken) {
        verifyKakaoTokenBelongsToOurApp(kakaoAccessToken);

        KakaoUserInfoResponse userInfo = kakaoClient.fetchUserInfo(kakaoAccessToken);

        Long kakaoUserId = userInfo.id();
        String nickname = userInfo.resolvedNickname();
        String profileImageUrl = userInfo.resolvedProfileImageUrl();

        if (nickname == null || nickname.isBlank()) {
            throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED, "카카오 닉네임을 가져올 수 없습니다.");
        }

        return withBulkhead(() -> userLoginPersistence.upsertByOauthAndIssueTokens(
                NativeLoginCommand.kakao(kakaoUserId, nickname, profileImageUrl)));
    }

    /**
     * 네이티브 Kakao access token 이 우리 앱 발급분인지 검증한다.
     * access_token_info 의 app_id 가 설정값과 다르면 다른 앱 토큰이므로 401 로 거부한다.
     */
    private void verifyKakaoTokenBelongsToOurApp(String kakaoAccessToken) {
        KakaoAccessTokenInfoResponse info = kakaoClient.fetchAccessTokenInfo(kakaoAccessToken);
        if (info == null) {
            throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED, "카카오 토큰 정보를 가져올 수 없습니다.");
        }
        Long expectedAppId = kakaoApiProperties.oauth().appId();
        if (info.appId() == null || !info.appId().equals(expectedAppId)) {
            throw new CoreException(ErrorType.AUTH_KAKAO_APP_MISMATCH);
        }
    }

    /**
     * P1: Apple 네이티브 로그인 (FR-3, BR-4/5/9/12, AC-9~15).
     * identityToken 서명·iss·aud·exp·nonce 검증(verifier) 후 (APPLE, sub) find-or-create.
     * 닉네임은 fullName 있으면 given+family, 없으면 임시("Apple 사용자") — P3 온보딩에서 변경(BR-12).
     * email 은 토큰 클레임 우선, 없으면 요청 email — 신규 계정 최초 1회만 저장(BR-9).
     */
    public AuthResultInfo loginWithApple(AppleLoginCommand cmd) {
        AppleTokenClaims claims = appleVerifier.verify(cmd.identityToken(), cmd.nonce());

        String nickname = resolveAppleNickname(cmd.givenName(), cmd.familyName());
        String email = claims.email() != null ? claims.email() : cmd.email();

        return withBulkhead(() -> userLoginPersistence.upsertByOauthAndIssueTokens(
                NativeLoginCommand.apple(claims.sub(), nickname, email)));
    }

    /**
     * BR-12: Apple 최초 로그인 닉네임. givenName/familyName 결합, 둘 다 없으면 임시 닉네임.
     */
    private String resolveAppleNickname(String givenName, String familyName) {
        String given = givenName != null ? givenName.trim() : "";
        String family = familyName != null ? familyName.trim() : "";
        String combined = (given + " " + family).trim();
        return combined.isBlank() ? "Apple 사용자" : combined;
    }

    /**
     * 일반 API 풀 보호용 Bulkhead 점유 후 DB 작업 위임 (loginWithKakao 와 동일 정책).
     * permit 미획득(한도 5 초과) 시 즉시 친화 에러로 빠르게 실패한다.
     *
     * <p>Kakao/Apple 네이티브가 공유하므로 provider 무관 일시적 서버 과부하 에러
     * (AUTH_LOGIN_TEMPORARILY_UNAVAILABLE, 503) 로 응답한다 — Apple 호출에 카카오 에러를
     * 오인 노출하지 않는다.
     */
    private AuthResultInfo withBulkhead(java.util.function.Supplier<AuthResultInfo> action) {
        boolean acquired;
        try {
            acquired = authBulkhead.tryAcquire(AUTH_BULKHEAD_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CoreException(ErrorType.AUTH_LOGIN_TEMPORARILY_UNAVAILABLE);
        }
        if (!acquired) {
            throw new CoreException(ErrorType.AUTH_LOGIN_TEMPORARILY_UNAVAILABLE);
        }
        try {
            return action.get();
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
