package com.wherewego.interfaces.api.notification;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Notification V1 API", description = "Phase 8 알림 목록/상세/읽음 처리 API. " +
        "클라이언트는 mount / visibilitychange / focus 시점에 목록을 재조회한다 (옵션 B 다운그레이드, 2026-05-21).")
public interface NotificationV1ApiSpec {

    @Operation(
            summary = "알림 목록 조회",
            description = "현재 사용자의 최근 알림 목록(최대 50건) 을 created_at 내림차순으로 반환합니다. " +
                    "응답에는 미읽음 알림 개수(unreadCount) 가 포함됩니다."
    )
    ApiResponse<NotificationV1Dto.NotificationListResponse> list(
            @Parameter(hidden = true) Long userId
    );

    @Operation(
            summary = "알림 전체 읽음 처리",
            description = "현재 사용자의 미읽음 알림을 모두 읽음 처리합니다. 멱등이며 갱신된 행 수를 반환합니다."
    )
    ApiResponse<NotificationV1Dto.ReadAllResponse> readAll(
            @Parameter(hidden = true) Long userId
    );

    @Operation(
            summary = "알림 상세 조회",
            description = "알림 단건의 핀 목록 상세를 sort_order 순으로 반환합니다. " +
                    "수신자 본인의 알림이 아니면 NOT_FOUND (404) 로 거부됩니다. " +
                    "소프트 삭제된 핀은 좌표/주소가 마스킹되며 deleted=true 로 표시됩니다."
    )
    ApiResponse<NotificationV1Dto.NotificationDetailResponse> detail(
            @Parameter(hidden = true) Long userId,
            Long notificationId
    );
}
