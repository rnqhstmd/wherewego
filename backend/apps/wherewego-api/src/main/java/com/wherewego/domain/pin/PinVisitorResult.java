package com.wherewego.domain.pin;

/**
 * 핀 방문자 1명 표현(정책 v2, FR-B4). 핀 응답 visitors[] 와 방문 선언 응답에 공용으로 쓴다.
 *
 * @param userId          방문자 user id
 * @param nickname        방문자 닉네임. 탈퇴/없음이면 {@code null}.
 * @param profileImageUrl 유효 프사 URL(GP-1 resolver — 키 우선 → 카카오 폴백 → null).
 * @param source          SELF / TAGGED.
 */
public record PinVisitorResult(
        Long userId,
        String nickname,
        String profileImageUrl,
        VisitSource source
) {
}
