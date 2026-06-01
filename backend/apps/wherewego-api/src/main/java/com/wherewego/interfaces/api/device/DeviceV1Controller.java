package com.wherewego.interfaces.api.device;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.device.Device;
import com.wherewego.domain.device.DeviceService;
import com.wherewego.interfaces.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P2 PR-2: 디바이스 푸시 토큰 등록/해제 REST 컨트롤러(FR-15/16).
 *
 * <p>로그인 직후 APNs 디바이스 토큰을 등록(upsert)하고 로그아웃 시 해제한다. 신규 {@code /api/v1/**}
 * 엔드포인트로 SecurityConfig 의 {@code anyRequest().authenticated()} 에 의해 자동 보호된다(BR-8).</p>
 */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
@Validated
public class DeviceV1Controller implements DeviceV1ApiSpec {

    private final DeviceService deviceService;

    @PostMapping
    @Override
    public ApiResponse<DeviceV1Dto.RegisterDeviceResponse> register(
            @AuthUser Long userId,
            @Valid @RequestBody DeviceV1Dto.RegisterDeviceRequest request
    ) {
        Device device = deviceService.register(userId, request.platform(), request.deviceToken());
        return ApiResponse.success(DeviceV1Dto.RegisterDeviceResponse.from(device));
    }

    @DeleteMapping("/{deviceToken}")
    @Override
    public ApiResponse<Object> unregister(
            @AuthUser Long userId,
            @PathVariable @NotBlank @Size(max = 500) String deviceToken
    ) {
        deviceService.unregister(userId, deviceToken);
        return ApiResponse.success();
    }
}
