package com.wherewego.domain.group;

import java.util.List;

/**
 * 내 그룹 목록 컨트롤러 응답용 조립 항목 (GP-1, FR-4).
 *
 * <p>{@link GroupSummary}(이미지 공개 URL 포함)에 활성 멤버 프리뷰({@link GroupMemberPreview}, 가입순 아바타 일렬)를
 * 더한 조립 형태다. {@code GroupMemberService.listMyGroupsWithMembers} 가 그룹 목록 + 멤버 IN 쿼리 1회를
 * 조합해 만든다. 채팅 방 목록({@code GroupChatService.getRooms})은 멤버 프리뷰가 불필요하므로 기존
 * {@code listMyGroups}({@link GroupSummary} 목록)를 그대로 소비한다(채팅 응답 무변경, 설계 §1.5).</p>
 */
public record GroupListItem(
        GroupSummary summary,
        List<GroupMemberPreview> members
) {
}
