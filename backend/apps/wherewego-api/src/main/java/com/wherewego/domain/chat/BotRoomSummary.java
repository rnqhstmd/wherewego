package com.wherewego.domain.chat;

/**
 * GM-2 (B단계): DM 목록 항목 — 사용자의 활성 그룹별 봇 방 요약(FR-2/FR-6, AC-2/AC-6/AC-7).
 *
 * <p>{@link BotChatService#getBotRooms}가 {@code listMyGroups} 순회로 그룹마다 1개씩 만든다. 봇 방이 아직
 * 없는 활성 그룹도 "가상 항목"으로 포함하여 활성 그룹 전부를 표시한다 — 이때 {@code roomId}/{@code lastPreview}/
 * {@code lastSenderType}/{@code lastAt} 는 {@code null}, {@code unread} 는 {@code false} 다(AC-7).</p>
 *
 * @param roomId         봇 방 PK. 아직 방이 없는 그룹이면 {@code null}.
 * @param groupId        그룹 PK(iOS 가 릴스 저장 그룹으로 사용 — FR-6).
 * @param groupName      그룹명.
 * @param lastPreview    마지막 메시지 미리보기(FR-7). 메시지 없음 → {@code null}.
 * @param lastSenderType 마지막 메시지 발신 주체. 메시지 없음 → {@code null}.
 * @param unread         마지막이 봇(BOT)이고 그 이후 미조회면 {@code true}(FR-5).
 * @param lastAt         마지막 메시지 생성 시각(ISO8601 offset). 메시지 없음 → {@code null}.
 */
public record BotRoomSummary(
        Long roomId,
        Long groupId,
        String groupName,
        String lastPreview,
        SenderType lastSenderType,
        boolean unread,
        String lastAt
) {
}
