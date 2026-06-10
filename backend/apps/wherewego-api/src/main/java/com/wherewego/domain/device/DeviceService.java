package com.wherewego.domain.device;

import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * P2: 디바이스 토큰 등록/해제 서비스(FR-15/16, BR-9).
 *
 * <p>등록(register)은 (user_id, device_token) 활성 1개를 보장하는 upsert다 — 존재하면 {@code updated_at}만
 * 갱신(AC-7), 없으면 신규 생성한다. BR-9에 따라 동일 token이 다른 userId로 활성 상태이면 그 행을 먼저
 * 해제하여 "토큰은 한 사용자 소유" 불변식을 유지한다.</p>
 *
 * <p>동시 등록 race는 {@code ON CONFLICT DO NOTHING} insert 가 예외 없이 흡수한 뒤 활성 행을 재조회하여
 * 반환한다(GroupChatService의 race-safe insert 패턴과 동일 — PR #118 리뷰 반영).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;

    /**
     * 디바이스 토큰을 등록한다(FR-15 upsert, BR-9 reassign).
     *
     * @param userId      등록 사용자 ID
     * @param platform    디바이스 플랫폼
     * @param deviceToken APNs 디바이스 토큰
     * @return 등록/갱신된 활성 {@link Device}
     */
    @Transactional
    public Device register(Long userId, DevicePlatform platform, String deviceToken) {
        reassignTokenFromOtherUsers(userId, deviceToken);
        return upsert(userId, platform, deviceToken);
    }

    /**
     * 디바이스 토큰을 해제한다(FR-16 unregister). 활성 (user_id, device_token) 행을 soft delete 하며 멱등하다.
     */
    @Transactional
    public void unregister(Long userId, String deviceToken) {
        deviceRepository.softDeleteByUserIdAndToken(userId, deviceToken);
    }

    /**
     * FR-19: 죽은 토큰을 정리한다 — 동일 token의 활성 행을 userId 무관하게 soft delete 한다.
     *
     * <p>APNs가 {@code BadDeviceToken}/{@code Unregistered}(410)로 거부한 토큰 정리용이다.
     * 등록 주체와 무관하게 token만으로 정리하므로 {@link #unregister(Long, String)}(user_id+token)과 별개다.
     * ApnsPushSender의 best-effort 거부 처리에서 호출되며, 짧은 {@code REQUIRED} 트랜잭션 경계
     * 안에서 벌크 UPDATE를 수행해 APNs 블로킹 호출은 트랜잭션 밖에 유지한다.</p>
     *
     * @param deviceToken 정리할 APNs 디바이스 토큰
     */
    @Transactional
    public void removeByToken(String deviceToken) {
        deviceRepository.softDeleteByToken(deviceToken);
    }

    /**
     * BR-9: 동일 token을 다른 userId가 활성 보유 중이면 그 행을 해제한다(토큰은 한 사용자 소유).
     */
    private void reassignTokenFromOtherUsers(Long userId, String deviceToken) {
        List<Device> sameToken = deviceRepository.findActiveByDeviceToken(deviceToken);
        for (Device device : sameToken) {
            if (!device.getUserId().equals(userId)) {
                deviceRepository.softDeleteByUserIdAndToken(device.getUserId(), deviceToken);
            }
        }
    }

    /**
     * FR-15: 활성 (user_id, device_token)이 있으면 {@code updated_at}만 갱신(AC-7), 없으면 신규 생성한다.
     * 동시 생성 충돌은 {@code ON CONFLICT DO NOTHING} insert 가 예외 없이 흡수한다(PR #118 리뷰 반영
     * — 기존 save+catch 폴백은 참여 트랜잭션이 rollback-only 로 마킹되어 커밋 시
     * UnexpectedRollbackException 으로 전체 실패하는 결함이 있었다).
     */
    private Device upsert(Long userId, DevicePlatform platform, String deviceToken) {
        return deviceRepository.findActiveByUserIdAndToken(userId, deviceToken)
                .map(existing -> {
                    deviceRepository.touch(existing.getId());
                    return existing;
                })
                .orElseGet(() -> createIfAbsent(userId, platform, deviceToken));
    }

    private Device createIfAbsent(Long userId, DevicePlatform platform, String deviceToken) {
        deviceRepository.insertIfAbsent(userId, platform, deviceToken);
        return deviceRepository.findActiveByUserIdAndToken(userId, deviceToken)
                .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "디바이스 등록 충돌"));
    }
}
