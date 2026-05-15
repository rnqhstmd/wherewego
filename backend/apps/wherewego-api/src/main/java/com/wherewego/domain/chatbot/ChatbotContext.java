package com.wherewego.domain.chatbot;

/**
 * 카카오 i 오픈빌더 Skill 요청 처리의 데드라인 + 세션 컨텍스트.
 *
 * <p>{@code t0} 진입 시각과 {@code deadlineMs} 전체 데드라인을 묶어
 * 각 외부 호출 단계에서 남은 시간을 계산한다.
 * 또한 webhook 진입 시점에 1회 조회한 {@code userId}를 핸들러로 전달하여
 * 중복 매핑 조회를 막는다 — 미연동 사용자는 {@code null}.</p>
 */
public final class ChatbotContext {

    private final long t0;
    private final long deadlineMs;
    private Long userId;

    private ChatbotContext(long t0, long deadlineMs) {
        this.t0 = t0;
        this.deadlineMs = deadlineMs;
    }

    public static ChatbotContext start(long deadlineMs) {
        return new ChatbotContext(System.currentTimeMillis(), deadlineMs);
    }

    public long t0() {
        return t0;
    }

    public long deadlineMs() {
        return deadlineMs;
    }

    public long remaining() {
        return deadlineMs - (System.currentTimeMillis() - t0);
    }

    public boolean expired() {
        return remaining() <= 0;
    }

    public Long userId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
