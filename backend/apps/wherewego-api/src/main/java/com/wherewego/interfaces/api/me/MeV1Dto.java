package com.wherewego.interfaces.api.me;

import com.wherewego.domain.user.OnboardingStatus;

public class MeV1Dto {

    /**
     * 온보딩 진입 상태 응답.
     * @param hasActiveGroup           활성 그룹 보유 여부
     * @param activeGroupMemberCount   활성 그룹의 멤버 수 (그룹 없으면 0).
     * @param hasBotMapping            카카오톡 챗봇 연동 여부
     */
    public record OnboardingStatusResponse(
            boolean hasActiveGroup,
            long activeGroupMemberCount,
            boolean hasBotMapping
    ) {
        public static OnboardingStatusResponse from(OnboardingStatus status) {
            return new OnboardingStatusResponse(
                    status.hasActiveGroup(),
                    status.activeGroupMemberCount(),
                    status.hasBotMapping()
            );
        }
    }

    private MeV1Dto() { }
}
