package com.wherewego.domain.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * P2 PR-1: 채팅 방 메시지 cursor 페이지 조회 결과(FR-5/9, AC-3).
 *
 * <p>최신순(id DESC) 페이지를 표현한다. {@code nextCursor}는 다음 페이지 요청 시 그대로
 * {@code ?cursor=} 로 넘기면 되는 "마지막으로 반환된 메시지의 id"이다.</p>
 *
 * @param messages   이번 페이지 메시지(최신순, 최대 limit개)
 * @param hasMore    더 과거 메시지가 남아있는지 여부
 * @param nextCursor 다음 페이지 cursor(= 마지막 메시지 id). 빈 페이지면 {@code null}.
 */
public record ChatMessagePageResult(
        List<ChatMessage> messages,
        boolean hasMore,
        Long nextCursor
) {

    /**
     * {@code limit + 1}개로 조회한 결과를 페이지 결과로 정규화한다.
     *
     * <p>{@code fetched}가 {@code limit + 1}개면 더 남은 메시지가 있다고 판정하여
     * 마지막 1개를 제거하고 {@code hasMore=true}로 설정한다. 그 이하이면 {@code hasMore=false}이다.
     * {@code nextCursor}는 정규화 후 남은 마지막 메시지의 id이며, 빈 페이지면 {@code null}이다.</p>
     *
     * @param fetched 리포지토리가 {@code limit + 1}로 조회한 메시지(최신순)
     * @param limit   요청 페이지 크기(1~50 클램프된 값)
     */
    public static ChatMessagePageResult of(List<ChatMessage> fetched, int limit) {
        if (fetched == null || fetched.isEmpty()) {
            return new ChatMessagePageResult(List.of(), false, null);
        }
        boolean hasMore = fetched.size() > limit;
        List<ChatMessage> page = hasMore
                ? new ArrayList<>(fetched.subList(0, limit))
                : fetched;
        Long nextCursor = page.get(page.size() - 1).getId();
        return new ChatMessagePageResult(page, hasMore, nextCursor);
    }
}
