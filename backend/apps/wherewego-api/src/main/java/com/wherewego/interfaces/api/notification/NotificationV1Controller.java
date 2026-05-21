package com.wherewego.interfaces.api.notification;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.notification.NotificationService;
import com.wherewego.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationV1Controller implements NotificationV1ApiSpec {

    private static final int RECENT_LIMIT = 50;

    private final NotificationService notificationService;

    @GetMapping
    @Override
    public ApiResponse<NotificationV1Dto.NotificationListResponse> list(@AuthUser Long userId) {
        return ApiResponse.success(
                NotificationV1Dto.NotificationListResponse.from(
                        notificationService.listRecent(userId, RECENT_LIMIT)));
    }

    @PostMapping("/read-all")
    @Override
    public ApiResponse<NotificationV1Dto.ReadAllResponse> readAll(@AuthUser Long userId) {
        int updated = notificationService.markAllRead(userId);
        return ApiResponse.success(new NotificationV1Dto.ReadAllResponse(updated));
    }

    @GetMapping("/{notificationId}")
    @Override
    public ApiResponse<NotificationV1Dto.NotificationDetailResponse> detail(
            @AuthUser Long userId,
            @PathVariable Long notificationId
    ) {
        return ApiResponse.success(
                NotificationV1Dto.NotificationDetailResponse.from(
                        notificationService.getDetail(notificationId, userId)));
    }
}
