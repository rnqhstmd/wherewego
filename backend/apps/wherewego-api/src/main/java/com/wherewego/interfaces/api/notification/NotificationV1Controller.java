package com.wherewego.interfaces.api.notification;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.notification.NotificationService;
import com.wherewego.domain.notification.NotificationSseRegistry;
import com.wherewego.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationV1Controller implements NotificationV1ApiSpec {

    private static final int RECENT_LIMIT = 50;

    private final NotificationService notificationService;
    private final NotificationSseRegistry sseRegistry;

    /**
     * SSE 스트림. 프록시 환경에서 응답 버퍼링 방지를 위해
     * X-Accel-Buffering: no + Cache-Control: no-cache 헤더 추가.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Override
    public SseEmitter stream(@AuthUser Long userId, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return sseRegistry.register(userId);
    }

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
