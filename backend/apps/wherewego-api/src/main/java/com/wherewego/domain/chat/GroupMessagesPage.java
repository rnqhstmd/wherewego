package com.wherewego.domain.chat;

import java.util.List;

/**
 * GC-1: 그룹 메시지 cursor 페이지 결과 — registered/발신자 정보가 합성된 프레임 목록.
 * {@link ChatMessagePageResult}(엔티티 페이지)와 달리 응답 표현({@link GroupChatMessageFrame})까지 조립된 형태다.
 *
 * @param frames     최신순(id DESC) 프레임 목록
 * @param hasMore    과거 메시지가 더 있는지
 * @param nextCursor 다음 페이지 cursor(가장 오래된 프레임의 id). 더 없으면 {@code null}.
 */
public record GroupMessagesPage(
        List<GroupChatMessageFrame> frames,
        boolean hasMore,
        Long nextCursor
) {

    public static GroupMessagesPage empty() {
        return new GroupMessagesPage(List.of(), false, null);
    }
}
