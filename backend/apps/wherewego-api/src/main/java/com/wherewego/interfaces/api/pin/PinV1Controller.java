package com.wherewego.interfaces.api.pin;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.pin.PinService;
import com.wherewego.domain.pin.PinTag;
import com.wherewego.interfaces.api.ApiResponse;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
public class PinV1Controller implements PinV1ApiSpec {

    private final PinService pinService;

    @GetMapping("/{groupId}/pins")
    @Override
    public ApiResponse<PinV1Dto.PinListResponse> listPins(
            @AuthUser Long userId,
            @PathVariable Long groupId,
            @RequestParam(required = false) String tag
    ) {
        PinTag tagFilter = null;
        if (tag != null && !tag.isBlank()) {
            try {
                tagFilter = PinTag.valueOf(tag);
            } catch (IllegalArgumentException e) {
                throw new CoreException(ErrorType.PIN_TAG_INVALID);
            }
        }
        return ApiResponse.success(
                PinV1Dto.PinListResponse.from(
                        pinService.listGroupPins(userId, groupId, tagFilter)));
    }

    @PatchMapping("/{groupId}/pins/{pinId}")
    @Override
    public ApiResponse<PinV1Dto.PinSummaryResponse> updatePin(
            @AuthUser Long userId,
            @PathVariable Long groupId,
            @PathVariable Long pinId,
            @RequestBody PinV1Dto.UpdatePinRequest request
    ) {
        return ApiResponse.success(
                PinV1Dto.PinSummaryResponse.from(
                        pinService.updatePin(userId, groupId, pinId, request.toCommand())));
    }

    @DeleteMapping("/{groupId}/pins/{pinId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Override
    public void deletePin(
            @AuthUser Long userId,
            @PathVariable Long groupId,
            @PathVariable Long pinId
    ) {
        pinService.softDeletePin(userId, groupId, pinId);
    }
}
