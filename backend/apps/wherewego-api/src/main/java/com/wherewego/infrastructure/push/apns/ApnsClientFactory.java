package com.wherewego.infrastructure.push.apns;

import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.ApnsClientBuilder;
import com.eatthepath.pushy.apns.auth.ApnsSigningKey;
import com.wherewego.config.env.ApnsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * P2 PR-2: pushy {@link ApnsClient} 싱글톤 빈 팩토리(FR-17~19).
 *
 * <p>{@link ApnsProperties#isConfigured()} 가 true 일 때만 .p8 서명 키로 ApnsClient 를 생성한다.
 * 미구성(로컬/CI — .p8 미주입) 이면 {@code @Bean} 메서드가 {@code null} 을 반환하여 빈을 노출하지
 * 않는다. ApnsPushSender 가 {@code @Autowired(required = false)} 로 nullable 주입을 받아 null 가드로
 * graceful no-op 한다.</p>
 *
 * <p>운영/개발 호스트는 {@code production} 플래그로 분기한다. S3Config 와 동일하게 외부 클라이언트
 * 빈을 한 곳에서 구성한다.</p>
 */
@Slf4j
@Configuration
public class ApnsClientFactory {

    /**
     * 구성되어 있으면 ApnsClient 를, 아니면 {@code null}(빈 미노출)을 반환한다.
     *
     * <p>{@code @Bean} 에 {@code destroyMethod} 를 지정하지 않는다. pushy {@link ApnsClient} 는
     * {@link java.io.Closeable} 을 구현하므로 Spring 이 non-null 빈일 때만 close 를 자동 감지해 종료 시
     * 호출한다. 미구성으로 {@code null} 을 반환하면 빈이 노출되지 않아 destroy 콜백도 호출되지 않는다
     * (명시적 {@code destroyMethod = "close"} 였다면 null 에 대한 close 시도로 NPE 위험).</p>
     *
     * @return 구성 시 {@link ApnsClient}, 미구성 시 {@code null}
     */
    @Bean
    public ApnsClient apnsClient(ApnsProperties properties) {
        if (!properties.isConfigured()) {
            log.info("APNs 미구성(.p8/keyId/teamId/bundleId 누락) — 푸시 전송 비활성(graceful no-op).");
            return null;
        }
        try {
            InputStream signingKeyStream = new ByteArrayInputStream(
                    properties.p8Key().getBytes(StandardCharsets.UTF_8));
            ApnsSigningKey signingKey = ApnsSigningKey.loadFromInputStream(
                    signingKeyStream, properties.teamId(), properties.keyId());
            String host = properties.production()
                    ? ApnsClientBuilder.PRODUCTION_APNS_HOST
                    : ApnsClientBuilder.DEVELOPMENT_APNS_HOST;
            ApnsClient client = new ApnsClientBuilder()
                    .setApnsServer(host)
                    .setSigningKey(signingKey)
                    .build();
            log.info("APNs 구성 완료 — host={}, topic={}", host, properties.bundleId());
            return client;
        } catch (Exception e) {
            // 키 파싱/클라이언트 빌드 실패 — 빈 노출하지 않고 no-op 로 폴백(앱 부팅 차단 방지).
            log.error("APNs ApnsClient 생성 실패 — 푸시 전송 비활성(no-op). 키/설정을 확인하세요.", e);
            return null;
        }
    }
}
