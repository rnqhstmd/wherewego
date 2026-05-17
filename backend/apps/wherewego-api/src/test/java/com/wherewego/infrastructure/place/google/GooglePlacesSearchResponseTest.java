package com.wherewego.infrastructure.place.google;

import com.wherewego.domain.place.PlaceSearchHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GooglePlacesSearchResponse.toHits 를 호출할 때,")
class GooglePlacesSearchResponseTest {

    private static GooglePlacesSearchResponse.Place place(
            String id, String name, String address, Double lat, Double lng) {
        GooglePlacesSearchResponse.DisplayName displayName =
                name == null ? null : new GooglePlacesSearchResponse.DisplayName(name, "ko");
        GooglePlacesSearchResponse.Location location =
                (lat == null && lng == null) ? null
                        : new GooglePlacesSearchResponse.Location(lat, lng);
        return new GooglePlacesSearchResponse.Place(id, displayName, address, location);
    }

    @Nested
    @DisplayName("places 가 비어있는 경우,")
    class WhenPlacesEmpty {

        @DisplayName("places 가 null 이면 빈 리스트를 반환한다.")
        @Test
        void nullPlaces_returnsEmpty() {
            // arrange
            GooglePlacesSearchResponse response = new GooglePlacesSearchResponse(null);

            // act
            List<PlaceSearchHit> hits = response.toHits(5);

            // assert
            assertThat(hits).isEmpty();
        }

        @DisplayName("places 가 빈 리스트이면 빈 리스트를 반환한다.")
        @Test
        void emptyPlaces_returnsEmpty() {
            // arrange
            GooglePlacesSearchResponse response = new GooglePlacesSearchResponse(List.of());

            // act
            List<PlaceSearchHit> hits = response.toHits(5);

            // assert
            assertThat(hits).isEmpty();
        }
    }

    @Nested
    @DisplayName("항목 검증을 수행할 때,")
    class ItemValidation {

        @DisplayName("location 이 null 인 항목은 제외된다.")
        @Test
        void locationNull_excluded() {
            // arrange
            GooglePlacesSearchResponse response = new GooglePlacesSearchResponse(List.of(
                    place("id-1", "이름1", "주소1", null, null),
                    place("id-2", "이름2", "주소2", 37.5, 127.0)
            ));

            // act
            List<PlaceSearchHit> hits = response.toHits(5);

            // assert
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).kakaoPlaceId()).isEqualTo("id-2");
        }

        @DisplayName("displayName 이 null 이거나 text 가 blank 인 항목은 제외된다.")
        @Test
        void displayNameBlank_excluded() {
            // arrange
            GooglePlacesSearchResponse response = new GooglePlacesSearchResponse(List.of(
                    place("id-1", null, "주소1", 37.5, 127.0),
                    place("id-2", "   ", "주소2", 37.5, 127.0),
                    place("id-3", "이름3", "주소3", 37.5, 127.0)
            ));

            // act
            List<PlaceSearchHit> hits = response.toHits(5);

            // assert
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).kakaoPlaceId()).isEqualTo("id-3");
        }

        @DisplayName("id 가 null 이거나 blank 인 항목은 제외된다.")
        @Test
        void idBlank_excluded() {
            // arrange
            GooglePlacesSearchResponse response = new GooglePlacesSearchResponse(List.of(
                    place(null, "이름1", "주소1", 37.5, 127.0),
                    place("   ", "이름2", "주소2", 37.5, 127.0),
                    place("id-3", "이름3", "주소3", 37.5, 127.0)
            ));

            // act
            List<PlaceSearchHit> hits = response.toHits(5);

            // assert
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).kakaoPlaceId()).isEqualTo("id-3");
        }
    }

    @Nested
    @DisplayName("maxSize 제한을 적용할 때,")
    class MaxSizeLimit {

        @DisplayName("결과는 maxSize 건으로 잘린다.")
        @Test
        void truncatedToMaxSize() {
            // arrange
            GooglePlacesSearchResponse response = new GooglePlacesSearchResponse(List.of(
                    place("id-1", "이름1", "주소1", 37.5, 127.0),
                    place("id-2", "이름2", "주소2", 37.5, 127.0),
                    place("id-3", "이름3", "주소3", 37.5, 127.0),
                    place("id-4", "이름4", "주소4", 37.5, 127.0)
            ));

            // act
            List<PlaceSearchHit> hits = response.toHits(2);

            // assert
            assertThat(hits).hasSize(2);
            assertThat(hits).extracting(PlaceSearchHit::kakaoPlaceId)
                    .containsExactly("id-1", "id-2");
        }
    }
}
