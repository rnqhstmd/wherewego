package com.wherewego.interfaces.api.device;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Tag(name = "Device V1 API", description = "P2 디바이스 푸시 토큰 등록/해제 REST API. "
        + "로그인 시 APNs 디바이스 토큰을 등록(upsert)하고 로그아웃 시 해제합니다 (FR-15/16).")
public interface DeviceV1ApiSpec {

    @Operation(
            summary = "디바이스 토큰 등록",
            description = "APNs 디바이스 토큰을 등록합니다 (FR-15, AC-7, BR-9). "
                    + "활성 (user, token) 이 있으면 갱신만 하고 없으면 신규 생성하는 upsert 이며, "
                    + "동일 token 이 다른 사용자에게 활성이면 해당 행을 먼저 해제합니다. "
                    + "등록/갱신된 활성 디바이스의 {deviceId} 를 반환합니다."
    )
    ApiResponse<DeviceV1Dto.RegisterDeviceResponse> register(
            @Parameter(hidden = true) Long userId,
            DeviceV1Dto.RegisterDeviceRequest request
    );

    @Operation(
            summary = "디바이스 토큰 해제",
            description = "APNs 디바이스 토큰을 해제합니다 (FR-16). 로그아웃 시 호출하며 "
                    + "활성 (user, token) 행을 soft delete 합니다. 행이 없어도 성공하는 멱등 연산입니다."
    )
    ApiResponse<Object> unregister(
            @Parameter(hidden = true) Long userId,
            @NotBlank @Size(max = 500) String deviceToken
    );
}
