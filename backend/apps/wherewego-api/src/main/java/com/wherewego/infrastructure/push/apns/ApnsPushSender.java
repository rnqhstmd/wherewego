package com.wherewego.infrastructure.push.apns;

import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.PushNotificationResponse;
import com.eatthepath.pushy.apns.util.ApnsPayloadBuilder;
import com.eatthepath.pushy.apns.util.SimpleApnsPayloadBuilder;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.wherewego.config.env.ApnsProperties;
import com.wherewego.domain.device.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * P2 PR-2: 단일 디바이스 토큰으로 APNs 푸시를 전송한다(FR-17~19). 전체 best-effort.
 *
 * <p>{@link ApnsClient} 빈은 {@link ApnsClientFactory} 가 미구성 시 {@code null} 로 노출하므로
 * {@link ObjectProvider} 로 nullable 주입을 받는다. 클라이언트가 없으면(.p8 미주입) 전송을 스킵하는
 * graceful no-op 이다. 전송 과정의 어떤 예외도 호출자에게 전파하지 않는다(로그만 — 푸시 실패가
 * 핀 저장/채팅 흐름을 깨지 않도록).</p>
 *
 * <p>FR-19/AC-9: 거부 사유가 {@code BadDeviceToken} 또는 {@code Unregistered}(410) 이면 해당 토큰의
 * 활성 디바이스를 soft delete 하여 죽은 토큰을 정리한다. 이 클래스 자체는 {@code @Transactional} 이
 * 아니며(APNs 블로킹 호출을 트랜잭션 밖에 유지), 정리는 {@link com.wherewego.domain.device.DeviceService#removeByToken(String)}
 * 의 짧은 {@code REQUIRED} 트랜잭션 안에서 벌크 UPDATE 로 수행된다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApnsPushSender {

    /** FR-19: soft delete 대상 거부 사유(죽은 토큰). */
    private static final Set<String> DEAD_TOKEN_REASONS = Set.of("BadDeviceToken", "Unregistered");

    /** APNs 전송 응답 대기 한도(초). 무제한 블로킹으로 afterCommit/워커 스레드가 점유되는 것을 방지한다. */
    private static final long SEND_TIMEOUT_SECONDS = 10;

    /** SimpleApnsPushNotification topic 으로 미설정 시 ApnsClient 자체가 null 이라 도달하지 않는다. */
    private final ObjectProvider<ApnsClient> apnsClientProvider;
    private final ApnsProperties apnsProperties;
    private final DeviceService deviceService;

    /**
     * 디바이스 토큰으로 푸시 1건을 전송한다(best-effort).
     *
     * @param deviceToken APNs 디바이스 토큰
     * @param title       alert title
     * @param body        alert body
     * @param type        커스텀 페이로드 type(클라 라우팅용)
     * @param roomId      커스텀 페이로드 roomId. null 이면 페이로드에 포함하지 않는다.
     */
    public void send(String deviceToken, String title, String body, String type, Long roomId) {
        ApnsClient apnsClient = apnsClientProvider.getIfAvailable();
        if (apnsClient == null) {
            log.debug("APNs 미구성 — 푸시 스킵 (token={}, type={})", mask(deviceToken), type);
            return;
        }

        try {
            String payload = buildPayload(title, body, type, roomId);
            SimpleApnsPushNotification notification =
                    new SimpleApnsPushNotification(deviceToken, apnsProperties.bundleId(), payload);

            PushNotificationResponse<SimpleApnsPushNotification> response =
                    apnsClient.sendNotification(notification).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!response.isAccepted()) {
                handleRejection(deviceToken, response);
            }
        } catch (Exception e) {
            // best-effort: 전송 실패가 호출 흐름을 깨지 않도록 로그만 남긴다.
            log.warn("APNs 푸시 전송 실패 (token={}, type={}): {}", mask(deviceToken), type, e.getMessage());
        }
    }

    private String buildPayload(String title, String body, String type, Long roomId) {
        ApnsPayloadBuilder builder = new SimpleApnsPayloadBuilder()
                .setAlertTitle(title)
                .setAlertBody(body)
                .setSound("default")
                .setBadgeNumber(1)
                .addCustomProperty("type", type);
        if (roomId != null) {
            builder.addCustomProperty("roomId", roomId);
        }
        return builder.build();
    }

    /**
     * FR-19: 거부 사유가 죽은 토큰({@code BadDeviceToken}/{@code Unregistered}) 이면 토큰을 정리하고,
     * 그 외 거부는 경고만 남긴다.
     */
    private void handleRejection(String deviceToken, PushNotificationResponse<SimpleApnsPushNotification> response) {
        Optional<String> rejectionReason = response.getRejectionReason();
        String reason = rejectionReason.orElse("unknown");
        if (rejectionReason.isPresent() && DEAD_TOKEN_REASONS.contains(rejectionReason.get())) {
            log.info("APNs 죽은 토큰 정리 (token={}, reason={})", mask(deviceToken), reason);
            deviceService.removeByToken(deviceToken);
        } else {
            log.warn("APNs 푸시 거부 (token={}, reason={})", mask(deviceToken), reason);
        }
    }

    /**
     * 로그 노출용 디바이스 토큰 마스킹. 원문(64자 hex)을 로그 집계 시스템에 평문 저장하지 않도록
     * 앞 8자만 남기고 나머지는 가린다(예: {@code a1b2c3d4…}). 전송/DB 정리에는 원문을 그대로 사용하고
     * 로그 인자에만 적용한다. null/짧은 토큰은 방어적으로 그대로 반환한다.
     */
    private String mask(String token) {
        if (token == null || token.length() <= 8) {
            return token;
        }
        return token.substring(0, 8) + "…";
    }
}
