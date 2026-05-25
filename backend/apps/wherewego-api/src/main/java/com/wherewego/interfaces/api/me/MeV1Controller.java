package com.wherewego.interfaces.api.me;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.user.UserOnboardingService;
import com.wherewego.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeV1Controller implements MeV1ApiSpec {

    private final UserOnboardingService userOnboardingService;

    @GetMapping("/onboarding-status")
    @Override
    public ApiResponse<MeV1Dto.OnboardingStatusResponse> getOnboardingStatus(@AuthUser Long userId) {
        return ApiResponse.success(
                MeV1Dto.OnboardingStatusResponse.from(
                        userOnboardingService.getStatus(userId)));
    }
}
