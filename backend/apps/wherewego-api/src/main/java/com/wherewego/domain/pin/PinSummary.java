package com.wherewego.domain.pin;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 핀 응답용 도메인 record. JPA 엔티티 직접 노출을 방지한다 (Phase 4 §B2).
 */
public record PinSummary(
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
        /** 정책 v2 FR-B4: 이 핀의 방문자 목록(IN 배치 + GP-1 프사 resolver). 0명이면 빈 리스트. 추가형 계약. */
        List<PinVisitorResult> visitors
) {

    /**
     * 기본 팩토리. 작성자/메모 작성자 닉네임 + 사진 조합 URL 을 주입한다 (Phase 13).
     * <p>photoUrl/photoThumbnailUrl 은 {@code PinService.toSummary} 가 키 → public URL 로 조합한 값이다.
     * visitors 는 빈 리스트로 초기화하며, 방문자 합성은 {@link #withVisitors(List)} 로 덧붙인다(정책 v2 FR-B4).</p>
     */
    public static PinSummary from(Pin pin, String createdByNickname, String memoUpdatedByNickname,
                                  String photoUrl, String photoThumbnailUrl) {
        return new PinSummary(
                pin.getId(),
                pin.getGroupId(),
                pin.getCreatedBy(),
                createdByNickname,
                pin.getPlaceName(),
                pin.getAddress(),
                pin.getLatitude(),
                pin.getLongitude(),
                pin.getInstagramUrl(),
                pin.getMemo(),
                pin.getMemoSource(),
                pin.getTag(),
                pin.getCreatedAt(),
                pin.getVisitedAt(),
                pin.getMemoUpdatedBy(),
                memoUpdatedByNickname,
                photoUrl,
                photoThumbnailUrl,
                List.of()
        );
    }

    /**
     * 방문자 목록을 덧붙인 복제본 반환(정책 v2 FR-B4). 핀 목록/단건 조립 시 IN 배치로 합성한 visitors 를 주입한다.
     */
    public PinSummary withVisitors(List<PinVisitorResult> visitors) {
        return new PinSummary(
                id, groupId, createdBy, createdByNickname, placeName, address, latitude, longitude,
                instagramUrl, memo, memoSource, tag, createdAt, visitedAt, memoUpdatedBy,
                memoUpdatedByNickname, photoUrl, photoThumbnailUrl,
                visitors == null ? List.of() : visitors);
    }
}
