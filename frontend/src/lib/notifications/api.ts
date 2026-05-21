import { apiFetch } from "../api/http-client";

import type {
  NotificationDetail,
  NotificationListResponse,
  ReadAllResponse,
} from "./types";

/**
 * 알림 도메인 API 클라이언트.
 *
 * <p>Client Component에서 호출되며, BFF 프록시(`app/api/[...path]/route.ts`)를 통해
 * 백엔드 `/api/v1/notifications/*` 로 전달된다. {@link apiFetch} 가
 * ApiResponse envelope(`{ meta, data }`) 언래핑과 인증 쿠키 부착을 처리한다.</p>
 */

const BASE = "/notifications";

export async function fetchNotifications(
  signal?: AbortSignal,
): Promise<NotificationListResponse> {
  return apiFetch<NotificationListResponse>(BASE, { signal });
}

export async function markAllNotificationsRead(): Promise<ReadAllResponse> {
  return apiFetch<ReadAllResponse>(`${BASE}/read-all`, { method: "POST" });
}

export async function fetchNotificationDetail(
  id: number,
  signal?: AbortSignal,
): Promise<NotificationDetail> {
  return apiFetch<NotificationDetail>(`${BASE}/${id}`, { signal });
}

/**
 * SSE 구독 URL. EventSource 생성 시 same-origin이라 별도 인증 헤더가 필요 없으며
 * BFF 프록시가 백엔드 `/api/v1/notifications/stream` 으로 전달한다.
 */
export const NOTIFICATION_SSE_URL = "/api/v1/notifications/stream";
