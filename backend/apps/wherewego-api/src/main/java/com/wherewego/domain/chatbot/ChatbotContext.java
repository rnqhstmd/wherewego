package com.wherewego.domain.chatbot;

/**
 * 카카오 i 오픈빌더 Skill 요청 처리의 데드라인 컨텍스트.
 *
 * <p>{@code t0} 진입 시각과 {@code deadlineMs} 전체 데드라인을 묶어
 * 각 외부 호출 단계에서 남은 시간을 계산한다.</p>
 */
public record ChatbotContext(long t0, long deadlineMs) {

    public static ChatbotContext start(long deadlineMs) {
        return new ChatbotContext(System.currentTimeMillis(), deadlineMs);
    }

    public long remaining() {
        return deadlineMs - (System.currentTimeMillis() - t0);
    }

    public boolean expired() {
        return remaining() <= 0;
    }
}
