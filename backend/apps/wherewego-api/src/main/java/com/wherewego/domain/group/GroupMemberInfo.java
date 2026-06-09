package com.wherewego.domain.group;

import java.time.Instant;

/**
 * 그룹원 목록 항목 (GM-2 D단계, 그룹관리).
 *
 * <p>활성 멤버(left_at IS NULL) 1행. {@code joinedAt} 은 GroupMember.joinedAt(Instant),
 * {@code memberId} 는 GroupMember.id 로 방장 판정 정렬(joined_at ASC, id ASC) 보조 키다.
 * 방장(owner) 여부는 서비스 레이어에서 정렬된 첫 항목에 마킹한다.</p>
 */
public record GroupMemberInfo(
        Long userId,
        String nickname,
        Instant joinedAt,
        Long memberId
) {
}
