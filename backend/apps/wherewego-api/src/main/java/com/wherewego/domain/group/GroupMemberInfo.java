package com.wherewego.domain.group;

import java.time.Instant;

/**
 * 그룹원 목록 항목 (GM-2 D단계, 그룹관리).
 *
 * <p>활성 멤버(left_at IS NULL) 1행. {@code joinedAt} 은 GroupMember.joinedAt(Instant),
 * {@code memberId} 는 GroupMember.id 로 방장 판정 정렬(joined_at ASC, id ASC) 보조 키다.
 * 방장(owner) 여부는 서비스 레이어에서 정렬된 첫 항목에 마킹한다.</p>
 *
 * <p>GP-1 FR-9: {@code profileImageThumbKey}/{@code profileImageUrl} 은 유효 프사 URL 산출용 raw 값이다 —
 * JPQL projection 단계에서 프사 썸네일 키(thumb)와 카카오 URL 을 함께 채우고, 서비스
 * ({@code GroupMemberService.listMembers})가 유효 프사 URL 규칙(thumb 키 우선 → 카카오 URL 폴백 → null)을
 * 적용해 {@link GroupMemberService.GroupMemberResult#profileImageUrl()} 로 변환한다.</p>
 */
public record GroupMemberInfo(
        Long userId,
        String nickname,
        Instant joinedAt,
        Long memberId,
        String profileImageThumbKey,
        String profileImageUrl
) {
}
