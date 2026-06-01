package com.wherewego.config.env;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * P2 PR-2: APNs(.p8 토큰 기반 푸시) 설정. {@code apns.*} 바인딩.
 *
 * <p>{@code WherewegoApiApplication} 의 {@code @ConfigurationPropertiesScan} 으로 자동 등록된다.
 * SlackProperties 와 동일하게 빈 값을 허용한다(검증 어노테이션 없음) — 로컬/CI 에는 .p8 키가
 * 주입되지 않으므로 {@link #isConfigured()} 가 false 가 되어 푸시 전송이 graceful no-op 된다
 * (ApnsClientFactory 가 ApnsClient 빈 생성을 건너뛴다).</p>
 *
 * @param keyId      APNs 인증 키 ID(Apple Developer Keys). 미설정 시 빈 문자열.
 * @param teamId     Apple Developer Team ID. 미설정 시 빈 문자열.
 * @param bundleId   푸시 topic(앱 번들 ID). SimpleApnsPushNotification topic 으로 사용. 미설정 시 빈 문자열.
 * @param p8Key      .p8 개인 키 PEM 문자열(env/Secret 주입). 미설정 시 빈 문자열.
 * @param production true 면 운영 APNs 호스트, false 면 개발(샌드박스) 호스트.
 */
@Validated
@ConfigurationProperties(prefix = "apns")
public record ApnsProperties(
        String keyId,
        String teamId,
        String bundleId,
        String p8Key,
        boolean production
) {

    /**
     * APNs 전송에 필요한 모든 값이 주입되었는지 판정한다(graceful no-op 게이트).
     * keyId/teamId/bundleId/p8Key 가 모두 non-blank 여야 구성된 것으로 본다.
     */
    public boolean isConfigured() {
        return isNotBlank(keyId)
                && isNotBlank(teamId)
                && isNotBlank(bundleId)
                && isNotBlank(p8Key);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
