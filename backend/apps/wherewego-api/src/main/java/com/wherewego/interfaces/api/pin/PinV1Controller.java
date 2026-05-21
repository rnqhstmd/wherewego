package com.wherewego.interfaces.api.pin;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.notification.NotificationService;
import com.wherewego.domain.pin.PinListResult;
import com.wherewego.domain.pin.PinService;
import com.wherewego.domain.pin.PinSummary;
import com.wherewego.domain.pin.PinTag;
import com.wherewego.interfaces.api.ApiResponse;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
public class PinV1Controller implements PinV1ApiSpec {

    private static final Logger log = LoggerFactory.getLogger(PinV1Controller.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final PinService pinService;
    private final NotificationService notificationService;

    @PostMapping("/{groupId}/pins")
    @ResponseStatus(HttpStatus.CREATED)
    @Override
    public ApiResponse<PinV1Dto.PinSummaryResponse> createPin(
            @AuthUser Long userId,
            @PathVariable Long groupId,
            @RequestBody PinV1Dto.CreatePinRequest request
    ) {
        PinSummary saved = pinService.addPin(userId, groupId, request.toCommand());
        try {
            notificationService.createForManualPin(groupId, userId, saved.id());
        } catch (RuntimeException e) {
            log.warn("notification (manual) failed groupId={} pinId={}", groupId, saved.id(), e);
        }
        return ApiResponse.success(PinV1Dto.PinSummaryResponse.from(saved));
    }

    @GetMapping("/{groupId}/pins")
    @Override
    public ApiResponse<PinV1Dto.PinListResponse> listPins(
            @AuthUser Long userId,
            @PathVariable Long groupId,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size
    ) {
        PinTag tagFilter = null;
        if (tag != null && !tag.isBlank()) {
            try {
                tagFilter = PinTag.valueOf(tag);
            } catch (IllegalArgumentException e) {
                throw new CoreException(ErrorType.PIN_TAG_INVALID);
            }
        }

        // 비숫자 파라미터는 PIN_PAGE_PARAM_INVALID 로 매핑 (전역 ApiControllerAdvice 변경 회피)
        Integer pageNum = parsePageParam(page);
        Integer sizeNum = parsePageParam(size);

        // 1. 부분 전달 검증 (Q3 결정: 둘 다 와야 페이지 모드)
        if ((pageNum == null) != (sizeNum == null)) {
            throw new CoreException(ErrorType.PIN_PAGE_PARAM_INVALID);
        }

        // 2. 둘 다 null → legacy 모드
        if (pageNum == null && sizeNum == null) {
            List<PinSummary> list = pinService.listGroupPins(userId, groupId, tagFilter);
            return ApiResponse.success(PinV1Dto.PinListResponse.from(list));
        }

        // 3. 둘 다 전달 → 검증 후 페이지 모드
        if (pageNum < 0) {
            throw new CoreException(ErrorType.PIN_PAGE_PARAM_INVALID);
        }
        if (sizeNum <= 0) {
            throw new CoreException(ErrorType.PIN_PAGE_PARAM_INVALID);
        }
        if (sizeNum > MAX_PAGE_SIZE) {
            throw new CoreException(ErrorType.PIN_PAGE_SIZE_EXCEEDED);
        }

        PinListResult result = pinService.listGroupPinsPaged(userId, groupId, tagFilter, pageNum, sizeNum);
        return ApiResponse.success(PinV1Dto.PinListResponse.fromPaged(result));
    }

    private static Integer parsePageParam(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new CoreException(ErrorType.PIN_PAGE_PARAM_INVALID);
        }
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
