package com.wherewego.interfaces.api.place;

import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.place.PlaceSearchOutcome;

import java.math.BigDecimal;
import java.util.List;

/**
 * 장소 검색 API 응답 DTO (Phase 6 FR-API-2).
 * <p>{@link PlaceSearchOutcome} 의 {@code Single}/{@code Multiple}/{@code Empty} 분기를 평탄화하여
 * 단일 {@code items} 배열로 노출한다. {@code PlaceSearchHit.latitude/longitude} 는 {@link Double} 이므로
 * 응답 단계에서 {@link BigDecimal} 로 승격해 정밀도를 보장한다.</p>
 */
public final class PlaceV1Dto {

    public record PlaceSearchResponse(List<PlaceItem> items) {

        public static PlaceSearchResponse from(PlaceSearchOutcome outcome) {
            List<PlaceItem> items = switch (outcome) {
                case PlaceSearchOutcome.Single s -> List.of(PlaceItem.from(s.hit()));
                case PlaceSearchOutcome.Multiple m -> m.hits().stream().map(PlaceItem::from).toList();
                case PlaceSearchOutcome.Empty ignored -> List.of();
            };
            return new PlaceSearchResponse(items);
        }
    }

    public record PlaceItem(
            String placeName,
            String address,
            BigDecimal latitude,
            BigDecimal longitude
    ) {

        public static PlaceItem from(PlaceSearchHit hit) {
            return new PlaceItem(
                    hit.placeName(),
                    hit.address(),
                    toBigDecimal(hit.latitude()),
                    toBigDecimal(hit.longitude())
            );
        }

        private static BigDecimal toBigDecimal(Double value) {
            return value == null ? null : BigDecimal.valueOf(value);
        }
    }

    private PlaceV1Dto() { }
}
