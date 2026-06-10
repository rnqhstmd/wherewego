package com.wherewego.domain.device;

import java.util.List;
import java.util.Optional;

/**
 * P2: 디바이스 토큰 도메인 port. {@code devices} 테이블 접근을 노출한다.
 * JPA 어댑터({@code DeviceRepositoryAdapter})가 Spring Data 리포지토리를 위임하여 구현한다.
 *
 * <p>활성(active)은 {@code deleted_at IS NULL}을 의미한다. 활성 (user_id, device_token) 1개 강제는
 * V016 부분 UNIQUE 인덱스(uq_devices_user_token)와 결합한다. soft delete 계열 메서드는
 * 대상 활성 행의 {@code deleted_at}을 갱신하여 비활성화하며 멱등하다.</p>
 */
public interface DeviceRepository {

    Device save(Device device);

    /**
     * 활성 (user_id, device_token) 디바이스를 조회한다(FR-15 upsert 판정).
     */
    Optional<Device> findActiveByUserIdAndToken(Long userId, String deviceToken);

    /**
     * 활성 디바이스를 race-safe 로 생성한다(없을 때만 — ON CONFLICT DO NOTHING).
     * 동시 등록 충돌에도 예외가 발생하지 않으므로 호출자 트랜잭션이 rollback-only 로 마킹되지 않는다
     * (PR #118 리뷰 반영). 호출 후 {@link #findActiveByUserIdAndToken}으로 재조회한다.
     *
     * @return 삽입 행 수(0 = 이미 존재)
     */
    int insertIfAbsent(Long userId, DevicePlatform platform, String deviceToken);

    /**
     * 동일 token의 활성 디바이스를 모두 조회한다(BR-9 reassign용 — 다른 userId 소유 행 탐지).
     */
    List<Device> findActiveByDeviceToken(String deviceToken);

    /**
     * 사용자의 활성 디바이스를 모두 조회한다(FR-20 푸시 fan-out용).
     */
    List<Device> findActiveByUserId(Long userId);

    /**
     * 활성 (user_id, device_token) 디바이스를 soft delete 한다(FR-16 unregister, BR-9 reassign).
     */
    void softDeleteByUserIdAndToken(Long userId, String deviceToken);

    /**
     * 동일 token의 활성 디바이스를 모두 soft delete 한다(FR-19 APNs BadDeviceToken 정리).
     */
    void softDeleteByToken(String deviceToken);

    /**
     * 사용자의 활성 디바이스를 모두 soft delete 한다(FR-21 계정 삭제 정리, PR-3에서 사용).
     */
    void softDeleteByUserId(Long userId);

    /**
     * 활성 디바이스의 {@code updated_at}을 현재 시각으로 갱신한다(FR-15 upsert touch, AC-7).
     */
    void touch(Long deviceId);
}
