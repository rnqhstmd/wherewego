package com.wherewego.interfaces.api.pin;

import com.fasterxml.jackson.databind.JsonNode;
import com.wherewego.domain.pin.MemoSource;
import com.wherewego.domain.pin.PinCreateCommand;
import com.wherewego.domain.pin.PinListResult;
import com.wherewego.domain.pin.PinSummary;
import com.wherewego.domain.pin.PinTag;
import com.wherewego.domain.pin.PinUpdateCommand;
import com.wherewego.domain.pin.PinVisitorResult;
import com.wherewego.domain.pin.VisitSource;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;

public final class PinV1Dto {

    /**
     * 정책 v2 FR-B4: 핀 방문자 1명 응답. 추가형 계약(구 클라이언트는 무시, iOS decodeIfPresent).
     */
    public record VisitorResponse(
            Long userId,
            String nickname,
            String profileImageUrl,
            VisitSource source
    ) {
        public static VisitorResponse from(PinVisitorResult v) {
            return new VisitorResponse(v.userId(), v.nickname(), v.profileImageUrl(), v.source());
        }
    }

    public record PinSummaryResponse(
            Long id,
            Long groupId,
            Long createdBy,
            String createdByNickname,
            String placeName,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            String instagramUrl,
            String memo,
            MemoSource memoSource,
            PinTag tag,
            ZonedDateTime createdAt,
            ZonedDateTime visitedAt,
            Long memoUpdatedBy,
            String memoUpdatedByNickname,
            String photoUrl,
            String photoThumbnailUrl,
            /** 정책 v2 FR-B4: 방문자 목록(0명이면 빈 배열). */
            List<VisitorResponse> visitors
    ) {
        public static PinSummaryResponse from(PinSummary s) {
            return new PinSummaryResponse(
                    s.id(),
                    s.groupId(),
                    s.createdBy(),
                    s.createdByNickname(),
                    s.placeName(),
                    s.address(),
                    s.latitude(),
                    s.longitude(),
                    s.instagramUrl(),
                    s.memo(),
                    s.memoSource(),
                    s.tag(),
                    s.createdAt(),
                    s.visitedAt(),
                    s.memoUpdatedBy(),
                    s.memoUpdatedByNickname(),
                    s.photoUrl(),
                    s.photoThumbnailUrl(),
                    s.visitors() == null ? List.of()
                            : s.visitors().stream().map(VisitorResponse::from).toList()
            );
        }
    }

    /**
     * Phase 10 보강 (2026-05-24): PATCH 응답 — 동시 수정 분기용.
     *
     * <p>{@code transitionedToMemoryNow} 는 본 PATCH 호출이 실제로 WISH/REEL → MEMORY 전환을
     * 발생시켰는지(=Service 가 {@code wasWishOrReelToMemory=true} 를 돌려줬는지)를 나타낸다.
     * 두 사용자가 동시에 같은 핀을 메모리로 전환하면 두 번째 PATCH 는 전환을 발생시키지 않으므로
     * {@code false} 가 된다. 클라이언트는 이 값을 보고 confetti/메모 시트 발사 여부를 분기한다.</p>
     */
    public record UpdatePinResponse(
            PinSummaryResponse summary,
            boolean transitionedToMemoryNow
    ) {
        public static UpdatePinResponse from(PinSummary summary, boolean transitioned) {
            return new UpdatePinResponse(PinSummaryResponse.from(summary), transitioned);
        }
    }

    public record PinListResponse(
            List<PinSummaryResponse> items,
            Long totalCount,
            Boolean hasNext
    ) {
        public static PinListResponse from(List<PinSummary> list) {
            return new PinListResponse(
                    list.stream().map(PinSummaryResponse::from).toList(),
                    null,
                    null);
        }

        public static PinListResponse fromPaged(PinListResult result) {
            return new PinListResponse(
                    result.items().stream().map(PinSummaryResponse::from).toList(),
                    result.totalCount(),
                    result.hasNext());
        }
    }

    /**
     * 핀 직접 등록 요청 (Phase 6 FR-API-1).
     * <p>{@link #toCommand()} 에서 빈 문자열 → null 정규화 + 길이/좌표 범위/필수값 검증을 수행한다.</p>
     */
    public record CreatePinRequest(
            String placeName,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            String instagramUrl,
            String memo,
            PinTag tag
    ) {

        public PinCreateCommand toCommand() {
            if (tag == null) {
                throw new CoreException(ErrorType.PIN_TAG_INVALID);
            }

            String normalizedPlaceName = blankToNull(placeName);
            if (normalizedPlaceName == null) {
                throw new CoreException(ErrorType.PIN_PLACE_NAME_INVALID);
            }
            if (normalizedPlaceName.length() > 200) {
                throw new CoreException(ErrorType.PIN_PLACE_NAME_INVALID);
            }

            String normalizedAddress = blankToNull(address);
            if (normalizedAddress != null && normalizedAddress.length() > 500) {
                throw new CoreException(ErrorType.PIN_ADDRESS_INVALID);
            }

            if (latitude == null || longitude == null) {
                throw new CoreException(ErrorType.PIN_COORDINATE_INVALID);
            }
            if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                    || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
                throw new CoreException(ErrorType.PIN_COORDINATE_INVALID);
            }
            if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                    || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
                throw new CoreException(ErrorType.PIN_COORDINATE_INVALID);
            }

            String normalizedInstagramUrl = blankToNull(instagramUrl);

            String normalizedMemo = blankToNull(memo);
            if (normalizedMemo != null && normalizedMemo.length() > 500) {
                throw new CoreException(ErrorType.PIN_MEMO_TOO_LONG);
            }

            return new PinCreateCommand(
                    normalizedPlaceName,
                    normalizedAddress,
                    latitude,
                    longitude,
                    normalizedInstagramUrl,
                    normalizedMemo,
                    tag
            );
        }

        private static String blankToNull(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    /**
     * 정책 v2 방문 선언 요청(FR-B2/B3). {@code companionUserIds} 는 본인 제외 동행 명단이며
     * null/빈 배열/생략 = 혼자(체크인 또는 1인 그룹 전환). 서버가 본인 자동 제거·그룹 멤버 검증을 한다.
     */
    public record DeclareVisitRequest(List<Long> companionUserIds) {
        public List<Long> normalized() {
            return companionUserIds == null ? List.of() : companionUserIds;
        }
    }

    /**
     * 정책 v2 방문 선언 응답(FR-B2/B3). converted/alreadyConverted 로 클라이언트가 confetti/합산 토스트를 분기한다.
     */
    public record DeclareVisitResponse(
            boolean converted,
            boolean alreadyConverted,
            List<VisitorResponse> visitors
    ) {
        public static DeclareVisitResponse from(com.wherewego.domain.pin.DeclareVisitResult r) {
            return new DeclareVisitResponse(
                    r.converted(),
                    r.alreadyConverted(),
                    r.visitors() == null ? List.of()
                            : r.visitors().stream().map(VisitorResponse::from).toList());
        }
    }

    /**
     * 부분 수정 요청. {@link JsonNode} 로 "키 없음 vs JSON null vs 빈 문자열" 을 구분한다 (Q2).
     *
     * <p>Phase 2.8: placeName/address 부분 수정 지원. address 의 빈 문자열은 "안전 무시"(미변경)으로
     * 정규화하여 클라이언트가 의도치 않은 입력을 보냈을 때도 안전하게 처리한다 (Q5).</p>
     *
     * <p>Phase 2.10: 좌표(latitude/longitude) 부분 수정 지원. JsonNode 대신 BigDecimal 직접 매핑
     * (CreatePinRequest 대칭). 한 쪽만 전달 시 PIN_COORDINATE_INVALID.</p>
     */
    public record UpdatePinRequest(JsonNode memo, JsonNode tag,
                                   JsonNode placeName, JsonNode address,
                                   BigDecimal latitude, BigDecimal longitude) {

        public PinUpdateCommand toCommand() {
            boolean memoProvided = memo != null && !memo.isNull();
            String memoValue = null;
            if (memoProvided) {
                if (!memo.isTextual()) {
                    throw new CoreException(ErrorType.PIN_MEMO_INVALID);
                }
                memoValue = memo.asText();
            }
            boolean tagProvided = tag != null && !tag.isNull();
            PinTag tagValue = null;
            if (tagProvided) {
                if (!tag.isTextual()) {
                    throw new CoreException(ErrorType.PIN_TAG_INVALID);
                }
                try {
                    tagValue = PinTag.valueOf(tag.asText());
                } catch (IllegalArgumentException e) {
                    throw new CoreException(ErrorType.PIN_TAG_INVALID);
                }
            }

            boolean placeNameProvided = placeName != null && !placeName.isNull();
            String placeNameValue = null;
            if (placeNameProvided) {
                if (!placeName.isTextual()) {
                    throw new CoreException(ErrorType.PIN_PLACE_NAME_INVALID);
                }
                placeNameValue = placeName.asText().trim();
            }

            boolean addressProvided = address != null && !address.isNull();
            String addressValue = null;
            if (addressProvided) {
                if (!address.isTextual()) {
                    throw new CoreException(ErrorType.PIN_ADDRESS_INVALID);
                }
                String trimmed = address.asText().trim();
                if (trimmed.isEmpty()) {
                    // Q5: 빈 문자열은 미변경으로 안전 무시
                    addressProvided = false;
                } else {
                    addressValue = trimmed;
                }
            }

            // Phase 2.10: 좌표 단일 플래그 처리
            boolean coordinateProvided;
            if (latitude == null && longitude == null) {
                coordinateProvided = false;
            } else if (latitude != null && longitude != null) {
                coordinateProvided = true;
            } else {
                throw new CoreException(ErrorType.PIN_COORDINATE_INVALID);
            }

            return PinUpdateCommand.of(memoProvided, memoValue, tagProvided, tagValue,
                    placeNameProvided, placeNameValue, addressProvided, addressValue,
                    coordinateProvided, latitude, longitude);
        }
    }

    private PinV1Dto() { }
}
