package com.wherewego.interfaces.api.user;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.pin.cleanup.CleanupService;
import com.wherewego.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;

/**
 * Phase 12: 오래된 핀 정리 배너 snooze REST 컨트롤러 (FR-PIN-12-25).
 *
 * <p>그룹 컨텍스트가 없는 자기 자신 대상 API 이므로 {@code /users/me} 하위에 배치한다.</p>
 */
@RestController
@RequestMapping("/api/v1/users/me/cleanup-snooze")
@RequiredArgsConstructor
public class UserCleanupSnoozeV1Controller implements UserCleanupSnoozeV1ApiSpec {

    private final CleanupService cleanupService;

    @PostMapping
    @Override
    public ApiResponse<UserCleanupSnoozeV1Dto.CleanupSnoozeResponse> snooze(
            @AuthUser Long userId
    ) {
        ZonedDateTime snoozedUntil = cleanupService.snooze7Days(userId);
        return ApiResponse.success(UserCleanupSnoozeV1Dto.CleanupSnoozeResponse.of(snoozedUntil));
    }
}
