package com.wherewego.domain.push;

import com.wherewego.domain.device.Device;
import com.wherewego.domain.device.DeviceRepository;
import com.wherewego.domain.group.GroupMemberRepository;
import com.wherewego.infrastructure.push.apns.ApnsPushSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * P2 PR-2: APNs 푸시 트리거 진입점(FR-17 트리거 3종, FR-20 fan-out). 전체 best-effort.
 *
 * <p>자체 트랜잭션을 갖지 않는다. 호출자가 트랜잭션 밖(PinV1Controller try-catch /
 * CoupleChatService·BotChatProcessor afterCommit)에서 best-effort로 호출하므로, 이 서비스의
 * 어떤 메서드도 예외를 전파하지 않는다. {@link ApnsPushSender#send}는 이미 best-effort이나,
 * {@link DeviceRepository}/{@link GroupMemberRepository} 조회 실패도 내부 try-catch로 격리하여
 * 핀 저장/채팅 흐름을 깨지 않도록 한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushNotificationService {

    private final DeviceRepository deviceRepository;
    private final ApnsPushSender apnsPushSender;
    private final GroupMemberRepository groupMemberRepository;

    /**
     * FR-20: 사용자의 활성 디바이스 전체로 fan-out 전송한다. 토큰이 없으면 no-op.
     * 조회/전송 실패는 내부에서 격리하여 호출자에게 전파하지 않는다.
     */
    public void pushToUser(Long userId, PushPayload payload) {
        try {
            List<Device> devices = deviceRepository.findActiveByUserId(userId);
            for (Device device : devices) {
                apnsPushSender.send(
                        device.getDeviceToken(),
                        payload.title(),
                        payload.body(),
                        payload.type(),
                        payload.roomId());
            }
        } catch (RuntimeException e) {
            // best-effort: 디바이스 조회 실패가 호출 흐름을 깨지 않도록 로그만 남긴다.
            log.warn("푸시 fan-out 실패 (userId={}, type={}): {}",
                    userId, payload.type(), e.getMessage());
        }
    }

    /**
     * FR-17①: 핀 저장 트리거. 같은 그룹의 상대 멤버(등록자 본인 제외) 전원에게 푸시한다.
     * 상대가 없으면 no-op. {@code pinId}는 현재 페이로드에 미사용이나 향후 딥링크 대비 시그니처를 유지한다.
     *
     * @param registeredByUserId 핀을 등록한 본인(푸시 수신 제외 대상)
     */
    public void pushPinSaved(Long groupId, Long registeredByUserId, Long pinId) {
        try {
            List<Long> partnerIds =
                    groupMemberRepository.findOtherActiveMemberIds(groupId, registeredByUserId);
            for (Long partnerId : partnerIds) {
                pushToUser(partnerId, PushPayload.pinSaved());
            }
        } catch (RuntimeException e) {
            // best-effort: 파트너 조회 실패가 핀 저장 흐름을 깨지 않도록 로그만 남긴다.
            log.warn("핀 저장 푸시 실패 (groupId={}, registeredByUserId={}, pinId={}): {}",
                    groupId, registeredByUserId, pinId, e.getMessage());
        }
    }

    /**
     * FR-17②: 커플 채팅 새 메시지 트리거. 상대 사용자에게 푸시한다.
     */
    public void pushCoupleMessage(Long partnerUserId, Long roomId) {
        pushToUser(partnerUserId, PushPayload.coupleMessage(roomId));
    }

    /**
     * FR-17③: 봇 장소 추천 결과 트리거. 요청한 사용자에게 푸시한다.
     */
    public void pushBotResult(Long userId, Long roomId) {
        pushToUser(userId, PushPayload.botResult(roomId));
    }
}
