package com.wherewego.infrastructure.place.kakao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 카카오 Local 키워드 검색 API 응답 매핑.
 * <p>좌표는 응답 필드명 {@code y}=위도, {@code x}=경도.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoLocalSearchResponse(
        List<Document> documents
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(
            String id,
            @JsonProperty("place_name") String placeName,
            @JsonProperty("address_name") String addressName,
            @JsonProperty("road_address_name") String roadAddressName,
            String x,
            String y
    ) { }
}
