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

@Component
@RequiredArgsConstructor
public class AuthService {

    private final KakaoOAuthClient kakaoClient;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenHasher refreshTokenHasher;
    private final KakaoLoginUrlGenerator kakaoLoginUrlGenerator;
    private final UserLoginPersistence userLoginPersistence;

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

        return userLoginPersistence.upsertAndIssueTokens(kakaoUserId, nickname, profileImageUrl);
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
