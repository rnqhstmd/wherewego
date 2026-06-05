package com.wherewego.domain.user;

/**
 * 사용자 온보딩 상태 (Phase 11 PR-B).
 *
 * @param hasActiveGroup           활성 그룹 보유 여부
 * @param activeGroupMemberCount   최신 활성 그룹의 멤버 수 (GM-1: 다중 시 최근 가입한 그룹 기준). 그룹 없으면 0.
 * @param hasBotMapping            카카오톡 챗봇 연동 여부 (botUserMappings 에 매핑 존재)
 */
public record OnboardingStatus(
        boolean hasActiveGroup,
        long activeGroupMemberCount,
        boolean hasBotMapping
) { }
