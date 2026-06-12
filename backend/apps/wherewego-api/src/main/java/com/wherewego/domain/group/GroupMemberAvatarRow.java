package com.wherewego.domain.group;

/**
 * 그룹 멤버 아바타 raw projection 행 (GP-1 내부용). 여러 그룹의 활성 멤버를 IN 쿼리 1회로 가져올 때
 * JPQL 이 채우는 중간 형태다 — 유효 프사 URL 규칙은 아직 적용 전(키/카카오 URL raw).
 *
 * <p>{@code profileImageThumbKey} 가 있으면 그 공개 URL 이 1순위, 없으면 {@code profileImageUrl}(카카오) 폴백,
 * 둘 다 없으면 null 이라는 규칙은 서비스({@code GroupMemberService})가 적용해 {@link GroupMemberPreview} 로 변환한다.
 * {@code groupId} 는 서비스 그룹핑 키다.</p>
 */
public record GroupMemberAvatarRow(
        Long groupId,
        Long userId,
        String nickname,
        String profileImageThumbKey,
        String profileImageUrl
) {
}
