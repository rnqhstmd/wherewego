package com.wherewego.interfaces.api.pin;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.notification.NotificationService;
import com.wherewego.domain.pin.PinListResult;
import com.wherewego.domain.pin.PinService;
import com.wherewego.domain.pin.PinSummary;
import com.wherewego.domain.pin.PinTag;
import com.wherewego.domain.pin.PinUpdateResult;
import com.wherewego.domain.push.PushNotificationService;
import com.wherewego.interfaces.api.ApiResponse;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
public class PinV1Controller implements PinV1ApiSpec {

    private static final Logger log = LoggerFactory.getLogger(PinV1Controller.class);

    private static final int MAX_PAGE_SIZE = 100;

    /** 추억핀 사진 허용 contentType (AC-5). */
    private static final Set<String> ALLOWED_PHOTO_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    /** 추억핀 사진 최대 크기 2MB (AC-4). */
    private static final long MAX_PHOTO_SIZE = 2L * 1024 * 1024;

    private final PinService pinService;
    private final NotificationService notificationService;
    private final PushNotificationService pushNotificationService;

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
        // FR-17①: 핀 저장 APNs 푸시(상대 멤버 대상). best-effort — 푸시 실패가 핀 저장 응답에 영향 없도록 격리.
        try {
            pushNotificationService.pushPinSaved(groupId, userId, saved.id());
        } catch (RuntimeException e) {
            log.warn("push (pin saved) failed groupId={} pinId={}", groupId, saved.id(), e);
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

        // 2. 둘 다 null → legacy 모드 (전체 목록).
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

        PinListResult result = pinService.listGroupPinsPaged(
                userId, groupId, tagFilter, pageNum, sizeNum);
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
    public ApiResponse<PinV1Dto.UpdatePinResponse> updatePin(
            @AuthUser Long userId,
            @PathVariable Long groupId,
            @PathVariable Long pinId,
            @RequestBody PinV1Dto.UpdatePinRequest request
    ) {
        PinUpdateResult result = pinService.updatePin(userId, groupId, pinId, request.toCommand());
        // Phase 10: WISH/REEL → MEMORY 전환 1회에 한해 VISIT_DETECTED 알림 fan-out.
        // 알림 실패는 PATCH 응답을 막지 않도록 호출자 격리 (BR-VD-6).
        if (result.wasWishOrReelToMemory()) {
            try {
                notificationService.createForVisitDetected(groupId, userId, pinId);
            } catch (RuntimeException e) {
                log.warn("notification (visit) failed groupId={} pinId={}", groupId, pinId, e);
            }
        }
        // Phase 10 보강 (2026-05-24): 동시 수정 분기를 위해 응답을 UpdatePinResponse 로 감싸 반환.
        // transitionedToMemoryNow 는 두 번째 PATCH 에서 false 로 내려가 클라이언트가 confetti/메모 시트 발사를 건너뛴다.
        return ApiResponse.success(
                PinV1Dto.UpdatePinResponse.from(result.summary(), result.wasWishOrReelToMemory()));
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

    @PostMapping(value = "/{groupId}/pins/{pinId}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Override
    public ApiResponse<PinV1Dto.PinSummaryResponse> uploadPinPhoto(
            @AuthUser Long userId,
            @PathVariable Long groupId,
            @PathVariable Long pinId,
            @RequestParam("file") MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            throw new CoreException(ErrorType.PIN_PHOTO_FILE_REQUIRED);
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_PHOTO_TYPES.contains(contentType)) {
            throw new CoreException(ErrorType.PIN_PHOTO_TYPE_INVALID);
        }
        if (file.getSize() > MAX_PHOTO_SIZE) {
            throw new CoreException(ErrorType.PIN_PHOTO_SIZE_EXCEEDED);
        }
        byte[] imageBytes;
        try {
            imageBytes = file.getBytes();
        } catch (IOException e) {
            log.warn("multipart read failed groupId={} pinId={}", groupId, pinId, e);
            throw new CoreException(ErrorType.PIN_PHOTO_STORAGE_FAILED);
        }
        // 2차 게이트: Content-Type 헤더는 위조 가능하므로 실제 매직바이트로 이미지 여부 검증.
        if (!isAllowedImageMagic(imageBytes)) {
            throw new CoreException(ErrorType.PIN_PHOTO_TYPE_INVALID);
        }
        PinSummary summary = pinService.uploadPhoto(userId, groupId, pinId, imageBytes, contentType);
        return ApiResponse.success(PinV1Dto.PinSummaryResponse.from(summary));
    }

    /**
     * 업로드 파일의 시작 매직바이트가 실제 허용 이미지(JPEG/PNG/WebP)인지 검증한다 (Content-Type 헤더 보완).
     * 길이가 부족한(짧은) 파일은 거부한다.
     */
    private static boolean isAllowedImageMagic(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        // JPEG: FF D8 FF
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && (bytes[1] & 0xFF) == 0x50
                && (bytes[2] & 0xFF) == 0x4E
                && (bytes[3] & 0xFF) == 0x47
                && (bytes[4] & 0xFF) == 0x0D
                && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A
                && (bytes[7] & 0xFF) == 0x0A) {
            return true;
        }
        // WebP: 바이트 0~3 = "RIFF", 바이트 8~11 = "WEBP"
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return true;
        }
        return false;
    }

    @DeleteMapping("/{groupId}/pins/{pinId}/photo")
    @Override
    public ApiResponse<PinV1Dto.PinSummaryResponse> deletePinPhoto(
            @AuthUser Long userId,
            @PathVariable Long groupId,
            @PathVariable Long pinId
    ) {
        PinSummary summary = pinService.deletePhoto(userId, groupId, pinId);
        return ApiResponse.success(PinV1Dto.PinSummaryResponse.from(summary));
    }
}
