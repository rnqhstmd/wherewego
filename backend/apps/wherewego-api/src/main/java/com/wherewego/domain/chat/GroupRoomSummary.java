package com.wherewego.domain.chat;

/**
 * GC-1: 그룹 채팅방 목록 항목 — 사용자의 활성 그룹별 방 요약(FR-GC1-7).
 *
 * <p>{@link GroupChatService#getRooms}가 {@code listMyGroups} 순회로 그룹마다 1개씩 만든다.
 * 방이 아직 없는 활성 그룹도 "가상 항목"으로 포함한다(BotRoomSummary AC-7 선례 — V021 백필 + 그룹 생성 훅으로
 * 통상은 방이 존재하며, 가상 항목은 안전망) — 이때 {@code roomId}/{@code lastPreview}/{@code lastSenderUserId}/
 * {@code lastAt} 는 {@code null}, {@code hasUnread} 는 {@code false} 다.</p>
 *
 * @param roomId           그룹 방 PK. 아직 방이 없는 그룹이면 {@code null}.
 * @param groupId          그룹 PK.
 * @param groupName        그룹명.
 * @param lastPreview      마지막 메시지 미리보기(TEXT=앞 40자, REEL_LINK=「릴스 링크」). 메시지 없음 → {@code null}.
 * @param lastSenderUserId   마지막 메시지 발신자. 탈퇴 발신자/메시지 없음 → {@code null}.
 * @param lastSenderNickname 마지막 발신자 닉네임(목록 미리보기 "이름: …" 병기용). 탈퇴/없음 → {@code null}.
 * @param hasUnread          마지막 메시지가 타인 발신이고 내 읽음 포인터 이후면 {@code true}(인스타식 boolean).
 * @param unreadCount        읽음 포인터 이후의 타인 메시지 수(숫자 배지·미읽음 위치 진입 앵커용). hasUnread=false 면 0.
 * @param lastAt             마지막 메시지 생성 시각(ISO8601 offset). 메시지 없음 → {@code null}.
 */
public record GroupRoomSummary(
        Long roomId,
        Long groupId,
        String groupName,
        String lastPreview,
        Long lastSenderUserId,
        String lastSenderNickname,
        boolean hasUnread,
        int unreadCount,
        String lastAt
) {
}
