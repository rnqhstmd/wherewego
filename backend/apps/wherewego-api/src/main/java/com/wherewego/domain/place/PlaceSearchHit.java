package com.wherewego.domain.place;

/**
 * 카카오 Local 검색 결과 1건. 카카오 응답의 좌표는 {@code y}가 위도, {@code x}가 경도.
 */
public record PlaceSearchHit(
        String kakaoPlaceId,
        String placeName,
        String address,
        Double latitude,
        Double longitude
) { }
