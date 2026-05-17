package com.wherewego.domain.chatbot;

/**
 * Google Places 비동기 폴백 작업에 필요한 불변 컨텍스트.
 *
 * <p>{@link ChatbotContext}는 서블릿 요청 스레드 객체이므로 비동기 워커 스레드로
 * 직접 넘기지 않고, 필요한 값만 immutable record로 스냅샷하여 전달한다.</p>
 *
 * <p>{@code callbackUrl}은 메모리 한정 — DB에 저장하지 않으며 작업 완료 후 GC된다.</p>
 */
public record FallbackJobContext(
        String botUserKey,
        Long userId,
        Long groupId,
        String callbackUrl,
        String instagramUrl,
        String keyword
) { }
