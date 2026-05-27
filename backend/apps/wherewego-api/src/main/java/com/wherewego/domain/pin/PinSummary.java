package com.wherewego.domain.pin;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

/**
 * 핀 응답용 도메인 record. JPA 엔티티 직접 노출을 방지한다 (Phase 4 §B2).
 *
 * <p>Phase 12: {@code wantCount}, {@code myWant} 추가. {@code myWant} 는 호출 사용자의 WANT
 * 누름 여부이며, {@link PinService#toSummaries} 에서 {@code PinEventRepository.findMyWantPinIds}
 * 배치 조회로 N+1 없이 채워진다.</p>
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
        int wantCount,
        boolean myWant
) {

    /**
     * 기본 팩토리. {@code wantCount}, {@code myWant} 를 함께 주입한다.
     */
    public static PinSummary from(Pin pin, String createdByNickname, String memoUpdatedByNickname,
                                  int wantCount, boolean myWant) {
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
                wantCount,
                myWant
        );
    }

    /**
     * 하위 호환 팩토리. WANT 정보 없이 단건 변환할 때 사용한다 (예: 직접 등록 직후 단건 응답).
     * {@code wantCount} 는 {@link Pin#getWantCount()} 로 채우고 {@code myWant=false} 로 기본화한다.
     */
    public static PinSummary from(Pin pin, String createdByNickname, String memoUpdatedByNickname) {
        return from(pin, createdByNickname, memoUpdatedByNickname, pin.getWantCount(), false);
    }
}
