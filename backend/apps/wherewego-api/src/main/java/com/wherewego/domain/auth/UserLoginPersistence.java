package com.wherewego.domain.auth;

import com.wherewego.application.auth.AuthResultInfo;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.auth.jwt.RefreshTokenHasher;
import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카카오 로그인의 DB 쓰기 부분만 담당.
 * AuthService.loginWithKakao는 외부 HTTP 호출을 포함하므로 @Transactional을 붙이면
 * Kakao API 응답을 기다리는 동안 커넥션을 점유한다 → 풀 고갈 위험.
 * 이 클래스는 외부 I/O 없이 순수 DB 작업만 수행하므로 트랜잭션 범위가 최소화된다.
 */
@Component
@RequiredArgsConstructor
class UserLoginPersistence {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenHasher refreshTokenHasher;

    @Transactional
    AuthResultInfo upsertAndIssueTokens(Long kakaoUserId, String nickname, String profileImageUrl) {
        UserModel user = userRepository.findByKakaoUserId(kakaoUserId)
                .map(existing -> {
                    if (!existing.isActive()) {
                        throw new CoreException(ErrorType.AUTH_USER_DEACTIVATED);
                    }
                    existing.updateProfile(nickname, profileImageUrl);
                    return existing;
                })
                .orElseGet(() -> userRepository.save(UserModel.create(kakaoUserId, nickname, profileImageUrl)));

        String accessRaw = jwtTokenProvider.issueAccessToken(user.getId());
        String refreshRaw = jwtTokenProvider.issueRefreshToken(user.getId());

        user.replaceRefreshTokenHash(refreshTokenHasher.sha256Hex(refreshRaw));
        userRepository.save(user);

        return AuthResultInfo.of(user, accessRaw, refreshRaw);
    }
}
