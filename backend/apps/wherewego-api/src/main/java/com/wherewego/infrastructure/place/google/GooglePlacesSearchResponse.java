package com.wherewego.infrastructure.place.google;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wherewego.domain.place.PlaceSearchHit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Google Places API (New) {@code POST /v1/places:searchText} 응답 매핑.
 *
 * <p>FieldMask: {@code places.id,places.displayName,places.formattedAddress,places.location}.
 * 좌표 또는 placeName이 비면 해당 항목은 결과에서 제외한다.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GooglePlacesSearchResponse(List<Place> places) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Place(String id, DisplayName displayName, String formattedAddress, Location location) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisplayName(String text, String languageCode) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(Double latitude, Double longitude) { }

    public List<PlaceSearchHit> toHits(int maxSize) {
        if (places == null || places.isEmpty()) {
            return Collections.emptyList();
        }
        List<PlaceSearchHit> hits = new ArrayList<>(Math.min(places.size(), maxSize));
        for (Place place : places) {
            if (hits.size() >= maxSize) break;
            if (place.location() == null
                    || place.location().latitude() == null
                    || place.location().longitude() == null) {
                continue;
            }
            String placeName = place.displayName() != null ? place.displayName().text() : null;
            if (placeName == null || placeName.isBlank()) {
                continue;
            }
            String placeId = place.id();
            if (placeId == null || placeId.isBlank()) {
                continue;
            }
            hits.add(new PlaceSearchHit(
                    placeId,
                    placeName,
                    place.formattedAddress(),
                    place.location().latitude(),
                    place.location().longitude()
            ));
        }
        return hits;
    }
}
