package com.wherewego.domain.chatbot;

import java.time.Instant;

/**
 * {@link RecentlyAutoSavedSession} 값 객체.
 *
 * <p>{@code responseBody}는 prefix-free 본문이다. 자동 저장 경로의
 * {@code PendingNotificationSession.put} 시점에만 prefix가 결합되므로,
 * RESEND-1 안내 응답에서는 자체 prefix를 새로 붙여 사용한다.</p>
 */
public record RecentlyAutoSaved(String url, String responseBody, Instant savedAt) {
}
