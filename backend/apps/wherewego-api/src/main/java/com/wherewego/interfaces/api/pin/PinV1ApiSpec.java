package com.wherewego.interfaces.api.pin;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Pin V1 API", description = "그룹 핀 목록/수정/삭제/등록 API 입니다 (Phase 4 + Phase 6).")
public interface PinV1ApiSpec {

    @Operation(
            summary = "그룹 핀 목록 조회",
            description = "활성 그룹원이 자신의 그룹에 속한 핀 목록을 created_at 내림차순으로 반환합니다 (FR-1, BR-10). " +
                    "tag 쿼리 파라미터로 REEL/WISH/MEMORY 필터링이 가능합니다 (FR-5). " +
                    "잘못된 tag 값은 PIN_TAG_INVALID (400) 으로 거부됩니다 (AC-2 일관성). " +
                    "deleted_at IS NULL 인 행만 반환합니다 (BR-2). " +
                    "page/size 둘 다 미전달 시 전체 목록(legacy 모드, items 만 반환). " +
                    "둘 다 전달 시 페이지 모드(items + totalCount + hasNext). " +
                    "부분 전달은 400 PIN_PAGE_PARAM_INVALID. size > 100 은 400 PIN_PAGE_SIZE_EXCEEDED."
    )
    ApiResponse<PinV1Dto.PinListResponse> listPins(
            @Parameter(hidden = true) Long userId,
            Long groupId,
            String tag,
            String page,
            String size
    );

    @Operation(
            summary = "핀 직접 등록",
            description = "활성 그룹원이 그룹에 핀을 직접 등록합니다 (Phase 6 FR-API-1, BR-1). " +
                    "검색 결과 선택 또는 좌표 picker 흐름 모두에서 사용됩니다. " +
                    "tag 허용 값은 REEL/WISH/MEMORY 이며, 그 외 값은 PIN_TAG_INVALID (400) 으로 거부됩니다. " +
                    "memo 가 비어있지 않으면 memoSource=MANUAL 로 마킹됩니다 (BR-3). " +
                    "instagramUrl 이 있을 때만 그룹 내 UNIQUE 제약(uq_pins_group_instagram) 에 따라 " +
                    "중복은 PLC_DUPLICATE_PIN (409) 으로 거부됩니다."
    )
    ApiResponse<PinV1Dto.PinSummaryResponse> createPin(
            @Parameter(hidden = true) Long userId,
            Long groupId,
            PinV1Dto.CreatePinRequest request
    );

    @Operation(
            summary = "핀 부분 수정",
            description = "활성 그룹원이 핀의 memo/tag 를 부분 수정합니다 (FR-2, BR-7). " +
                    "tag 허용 값은 REEL/WISH/MEMORY 이며, 그 외 값은 PIN_TAG_INVALID (400) 으로 거부됩니다. " +
                    "memo 가 빈 문자열이면 잠금 해제(BR-8), 비어있지 않으면 MANUAL 마킹(BR-3, FR-4). " +
                    "키 없음 vs JSON null vs 빈 문자열을 구분하기 위해 본문은 JsonNode 로 받습니다. " +
                    "Phase 2.10: 좌표 수정 지원 — latitude/longitude 는 함께 전달해야 하며 한 쪽만 전달 시 " +
                    "PIN_COORDINATE_INVALID (400). 범위: latitude -90~90, longitude -180~180, 소수점 7자리 이하 (DB DECIMAL(10,7) 정합)."
    )
    ApiResponse<PinV1Dto.UpdatePinResponse> updatePin(
            @Parameter(hidden = true) Long userId,
            Long groupId,
            Long pinId,
            PinV1Dto.UpdatePinRequest request
    );

    @Operation(
            summary = "방문 선언 (체크인 / 추억 전환)",
            description = "정책 v2: 도착 감지 후 방문을 선언합니다 (FR-B2/B3). " +
                    "companionUserIds 가 비어 있으면 혼자(다인 그룹=체크인·태그 불변, 1인 그룹=추억 전환), " +
                    "비어 있지 않으면 동행 선언으로 WISH/REEL → MEMORY 전환(1회·멱등)합니다. " +
                    "본인은 SELF, 동행은 TAGGED 로 pin_visits 에 union upsert 되며, 이미 MEMORY 면 태그 불변 + visits union " +
                    "+ alreadyConverted=true (카드 미적재). 체크인 카드는 무푸시, 추억 카드는 푸시됩니다. " +
                    "비활성/타그룹 핀은 PIN_NOT_FOUND(404), 동행 명단에 비멤버가 있으면 PIN_VISIT_COMPANION_INVALID(400), " +
                    "비멤버 호출은 GROUP_NOT_MEMBER(403)."
    )
    ApiResponse<PinV1Dto.DeclareVisitResponse> declareVisit(
            @Parameter(hidden = true) Long userId,
            Long groupId,
            Long pinId,
            PinV1Dto.DeclareVisitRequest request
    );

    @Operation(
            summary = "핀 소프트 삭제",
            description = "활성 그룹원이 핀을 소프트 삭제합니다 (FR-3, BR-2). " +
                    "이미 삭제된 핀은 PIN_NOT_FOUND 로 거부됩니다 (BR-6). " +
                    "성공 시 204 No Content 를 반환합니다."
    )
    void deletePin(
            @Parameter(hidden = true) Long userId,
            Long groupId,
            Long pinId
    );

    @Operation(
            summary = "추억핀 사진 업로드/교체",
            description = "활성 그룹원이 MEMORY(추억) 핀에 사진 1장을 업로드합니다 (Phase 13 FR-PIN-9b~f, BR-1/BR-4). " +
                    "MEMORY 가 아닌 핀은 PIN_PHOTO_NOT_MEMORY (400). " +
                    "허용 타입은 image/jpeg|png|webp 이며 그 외는 PIN_PHOTO_TYPE_INVALID (400). " +
                    "2MB 초과는 PIN_PHOTO_SIZE_EXCEEDED (400), 장변 4096px 초과는 PIN_PHOTO_DIMENSION_EXCEEDED (400). " +
                    "서버에서 썸네일(장변 256px WebP)을 생성하여 원본·썸네일 2객체를 S3 에 저장하고, " +
                    "기존 사진이 있으면 best-effort 로 교체합니다. 갱신된 PinSummaryResponse 를 반환합니다."
    )
    ApiResponse<PinV1Dto.PinSummaryResponse> uploadPinPhoto(
            @Parameter(hidden = true) Long userId,
            Long groupId,
            Long pinId,
            MultipartFile file
    );

    @Operation(
            summary = "추억핀 사진 삭제",
            description = "활성 그룹원이 핀의 사진을 삭제합니다 (Phase 13 FR-PIN-10a/b). " +
                    "S3 원본·썸네일 2객체를 best-effort 삭제하고 사진 관련 4필드를 초기화합니다 (AC-9). " +
                    "204 가 아닌, 갱신된 PinSummaryResponse 를 반환합니다."
    )
    ApiResponse<PinV1Dto.PinSummaryResponse> deletePinPhoto(
            @Parameter(hidden = true) Long userId,
            Long groupId,
            Long pinId
    );
}
