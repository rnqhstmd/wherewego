package com.wherewego.interfaces.api.device;

import com.wherewego.domain.device.Device;
import com.wherewego.domain.device.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * P2 PR-2: 디바이스 토큰 등록 REST 요청/응답 DTO(FR-15/16).
 */
public final class DeviceV1Dto {

    private DeviceV1Dto() {
    }

    /**
     * 디바이스 토큰 등록 요청(FR-15). {@code platform}은 enum 문자열("IOS")로 바인딩되며,
     * {@code deviceToken}은 V016 스키마 device_token 컬럼(length 500)에 맞춰 최대 500자다.
     */
    public record RegisterDeviceRequest(
            @NotNull DevicePlatform platform,
            @NotBlank @Size(max = 500) String deviceToken
    ) {
    }

    /** 디바이스 토큰 등록 응답(FR-15). 등록/갱신된 활성 디바이스의 식별자를 반환한다(AC-7). */
    public record RegisterDeviceResponse(Long deviceId) {
        public static RegisterDeviceResponse from(Device device) {
            return new RegisterDeviceResponse(device.getId());
        }
    }
}
