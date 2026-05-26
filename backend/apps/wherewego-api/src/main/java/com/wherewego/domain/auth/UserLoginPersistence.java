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

    @Retryable(
            retryFor = {CannotCreateTransactionException.class, DataIntegrityViolationException.class},
            maxAttempts = 2,
            backoff = @Backoff(delay = 500)
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
}
