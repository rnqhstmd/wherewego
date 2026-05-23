package com.wherewego.domain.pin;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

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
        Long memoUpdatedBy,
        String memoUpdatedByNickname
) {

    public static PinSummary from(Pin pin, String createdByNickname, String memoUpdatedByNickname) {
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
                pin.getMemoUpdatedBy(),
                memoUpdatedByNickname
        );
    }
}
