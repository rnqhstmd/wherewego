package com.wherewego.domain.pin;

import java.math.BigDecimal;

/**
 * 핀 직접 등록 입력 도메인 객체 (Phase 6 §B1 — FR-API-1).
 *
 * <p>웹/모바일에서 검색→3클릭 또는 좌표 picker 로 직접 등록하는 흐름의 정규화된 입력값.
 * {@link com.wherewego.interfaces.api.pin.PinV1Dto.CreatePinRequest#toCommand()} 에서
 * 빈 문자열 → null 정규화 + 길이/좌표 범위 검증을 마친 뒤 생성된다.</p>
 */
public record PinCreateCommand(
        String placeName,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        String instagramUrl,
        String memo,
        PinTag tag
) { }
