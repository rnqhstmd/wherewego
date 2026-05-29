package com.wherewego.interfaces.api.pin;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.pin.cleanup.CleanupCandidatesResult;
import com.wherewego.domain.pin.cleanup.CleanupService;
import com.wherewego.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 12: 오래된 핀 정리 REST 컨트롤러 (FR-PIN-12-23, 24).
 */
@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
public class PinCleanupV1Controller implements PinCleanupV1ApiSpec {

    private final CleanupService cleanupService;

    @GetMapping("/{groupId}/cleanup/candidates")
    @Override
    public ApiResponse<PinCleanupV1Dto.CleanupCandidatesResponse> listCandidates(
            @AuthUser Long userId,
            @PathVariable Long groupId
    ) {
        CleanupCandidatesResult result = cleanupService.listCandidates(userId, groupId);
        return ApiResponse.success(PinCleanupV1Dto.CleanupCandidatesResponse.from(result));
    }

    @PostMapping("/{groupId}/cleanup/execute")
    @Override
    public ApiResponse<PinCleanupV1Dto.CleanupExecuteResponse> executeBulk(
            @AuthUser Long userId,
            @PathVariable Long groupId
    ) {
        int deletedCount = cleanupService.executeBulk(userId, groupId);
        return ApiResponse.success(PinCleanupV1Dto.CleanupExecuteResponse.of(deletedCount));
    }
}
