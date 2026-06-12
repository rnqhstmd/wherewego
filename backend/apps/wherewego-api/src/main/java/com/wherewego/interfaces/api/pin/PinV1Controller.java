package com.wherewego.interfaces.api.pin;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.notification.NotificationService;
import com.wherewego.domain.pin.DeclareVisitResult;
import com.wherewego.domain.pin.PinListResult;
import com.wherewego.domain.pin.PinService;
import com.wherewego.domain.pin.PinSummary;
import com.wherewego.domain.pin.PinTag;
import com.wherewego.domain.pin.PinUpdateResult;
import com.wherewego.domain.pin.PinVisitService;
import com.wherewego.domain.push.PushNotificationService;
import com.wherewego.interfaces.api.ApiResponse;
import com.wherewego.interfaces.api.support.ImageUploadGuard;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;

import java.util.List;
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

    private final PinService pinService;
    private final PinVisitService pinVisitService;
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
        // 정책 v2: 수동 PATCH 전환은 알림 fan-out 을 하지 않는다(VISIT_DETECTED 완전 폐기 — FR-B6).
        //   그룹 공유는 방문 선언 API 의 채팅 카드가 담당하고, 수동 태그 편집은 visits 미적재(정책 규칙 9).
        // Phase 10 보강 (2026-05-24): 동시 수정 분기를 위해 응답을 UpdatePinResponse 로 감싸 반환.
        //   transitionedToMemoryNow 는 두 번째 PATCH 에서 false 로 내려가 클라이언트가 confetti/메모 시트 발사를
        //   건너뛴다 — iOS 수동 전환 confetti 분기에서 계속 사용하므로 시그널은 유지한다.
        return ApiResponse.success(
                PinV1Dto.UpdatePinResponse.from(result.summary(), result.wasWishOrReelToMemory()));
    }

    @PostMapping("/{groupId}/pins/{pinId}/visits")
    @Override
    public ApiResponse<PinV1Dto.DeclareVisitResponse> declareVisit(
            @AuthUser Long userId,
            @PathVariable Long groupId,
            @PathVariable Long pinId,
            @RequestBody(required = false) PinV1Dto.DeclareVisitRequest request
    ) {
        // 정책 v2: 단일 방문 선언 API. companions 빈=체크인(다인 그룹)/1인 그룹 혼자 또는 동행=추억 전환.
        //   채팅 카드 적재는 PinVisitService 가 핀 트랜잭션과 동일 트랜잭션 내에서 처리한다.
        List<Long> companions = request == null ? List.of() : request.normalized();
        DeclareVisitResult result = pinVisitService.declareVisit(userId, groupId, pinId, companions);
        return ApiResponse.success(PinV1Dto.DeclareVisitResponse.from(result));
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
        // GP-1: 3중 검증(타입/크기/매직바이트)을 ImageUploadGuard 로 위임. 기존 PIN_PHOTO_* 에러타입을
        // 그대로 전달해 클라이언트 응답 코드 계약을 보존한다(동작 무변경).
        byte[] imageBytes = ImageUploadGuard.readValidatedImage(
                file,
                ErrorType.PIN_PHOTO_FILE_REQUIRED,
                ErrorType.PIN_PHOTO_TYPE_INVALID,
                ErrorType.PIN_PHOTO_SIZE_EXCEEDED,
                ErrorType.PIN_PHOTO_STORAGE_FAILED);
        PinSummary summary = pinService.uploadPhoto(userId, groupId, pinId, imageBytes, file.getContentType());
        return ApiResponse.success(PinV1Dto.PinSummaryResponse.from(summary));
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
