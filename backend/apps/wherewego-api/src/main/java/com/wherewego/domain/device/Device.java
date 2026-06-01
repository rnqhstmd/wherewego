package com.wherewego.domain.device;

import com.wherewego.domain.BaseEntity;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * P2: 디바이스 푸시 토큰. V016 스키마 {@code devices} 테이블 매핑.
 *
 * <p>사용자({@code userId})별 플랫폼({@code platform}) 디바이스 토큰({@code deviceToken}) 을 보유한다.
 * 활성 (user_id, device_token) 1개 강제는 V016 부분 UNIQUE 인덱스(uq_devices_user_token)와 결합한다.
 * 불변식은 {@link #guard()}에서 검증한다.</p>
 *
 * <p>BR-9 reassign(동일 token 다른 userId 재등록)·토큰 정리는 DeviceService 가 새 행 생성/기존 행 삭제로 처리한다.</p>
 */
@Entity
@Getter
@Table(name = "devices")
public class Device extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 20)
    private DevicePlatform platform;

    @Column(name = "device_token", nullable = false, length = 500)
    private String deviceToken;

    protected Device() { }

    private Device(Long userId, DevicePlatform platform, String deviceToken) {
        this.userId = userId;
        this.platform = platform;
        this.deviceToken = deviceToken;
        guard();
    }

    @Override
    protected void guard() {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "userId는 비어있을 수 없습니다.");
        }
        if (platform == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "platform은 비어있을 수 없습니다.");
        }
        if (deviceToken == null || deviceToken.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "deviceToken은 비어있을 수 없습니다.");
        }
    }

    public static Device create(Long userId, DevicePlatform platform, String deviceToken) {
        return new Device(userId, platform, deviceToken);
    }

    public boolean isActive() {
        return getDeletedAt() == null;
    }
}
