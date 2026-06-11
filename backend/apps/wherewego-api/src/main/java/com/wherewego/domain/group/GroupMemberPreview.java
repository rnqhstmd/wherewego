package com.wherewego.domain.group;

/**
 * 그룹 목록의 멤버 프리뷰 항목 (GP-1). 그룹 카드에 멤버 아바타를 가입순으로 일렬 노출하기 위한 최소 정보.
 *
 * <p>{@code profileImageUrl} 은 <b>유효 프사 URL</b>이다 — 프로필 사진 썸네일 키(thumb)가 있으면 그 공개 URL,
 * 없으면 카카오 {@code profileImageUrl} 폴백, 둘 다 없으면 null. 유효 URL 조합은 서비스가 수행한다
 * (GroupMemberService, PinService.toPublicUrl 와 동일 패턴). 가입순(joined_at ASC, id ASC) 정렬은
 * 리포지토리 IN 쿼리가 보장한다.</p>
 */
public record GroupMemberPreview(
        Long userId,
        String nickname,
        String profileImageUrl
) {
}
