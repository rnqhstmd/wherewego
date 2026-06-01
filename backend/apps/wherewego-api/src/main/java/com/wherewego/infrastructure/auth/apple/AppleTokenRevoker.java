package com.wherewego.infrastructure.auth.apple;

import com.wherewego.domain.user.OauthProvider;
import com.wherewego.domain.user.UserModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * P2: 계정 삭제 시 Apple token revoke (FR-23, AC-12) — best-effort 스킵.
 *
 * <p>P2 범위에서 Apple revoke 는 best-effort 스킵으로 확정(Q5)되었다. 실제 revoke 호출에 필요한
 * <ul>
 *     <li>{@code .p8} Sign In 키로 서명한 client_secret JWT</li>
 *     <li>Apple 로그인 시 발급되는 refresh token 저장</li>
 * </ul>
 * 인프라가 P2 범위 밖이라 미구축이므로, 대상 토큰/서명 수단이 없어 실제 revoke 를 수행할 수 없다.
 * 따라서 본 컴포넌트는 APPLE 계정에 대해 revoke 를 "시도"하되 스킵 + 로그만 남기는 no-op logger 다.
 * 계정 삭제(soft delete) 자체로 App Store 5.1.1(v) 요건을 충족하며, revoke 미수행은 best-effort
 * 정책상 허용된다(FR-23).
 *
 * <p>실제 revoke 구현 시 (미래 확장 지점):
 * <ol>
 *     <li>Apple 네이티브 로그인 시 authorization code → Apple token endpoint 교환으로 refresh token 을
 *         발급받아 저장한다.</li>
 *     <li>{@code .p8} Sign In 키(team id / key id / 서비스 id)로 ES256 client_secret JWT 를 서명한다.</li>
 *     <li>저장한 refresh token + client_secret 으로 Apple {@code /auth/revoke} 를 호출한다.</li>
 * </ol>
 */
@Slf4j
@Component
public class AppleTokenRevoker {

    /**
     * 사용자의 Apple token 을 revoke 한다 — best-effort.
     *
     * <p>provider 가 APPLE 이 아니면 no-op. APPLE 이면 client_secret({@code .p8}) / refresh token 저장
     * 인프라가 미구축이라 실제 revoke 를 수행하지 않고 스킵 로그만 남긴 뒤 정상 반환한다(예외 없음).
     */
    public void revoke(UserModel user) {
        if (user.getOauthProvider() != OauthProvider.APPLE) {
            return;
        }
        log.info("Apple token revoke 스킵 — client_secret(.p8)/refresh token 인프라 미구축 (userId={})", user.getId());
    }
}
