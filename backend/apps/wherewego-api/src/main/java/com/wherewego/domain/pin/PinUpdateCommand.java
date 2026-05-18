package com.wherewego.domain.pin;

import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;

import java.math.BigDecimal;

/**
 * 핀 부분 수정 입력 도메인 객체 (Phase 4 §B3, Phase 2.8 확장).
 *
 * <p>memo 의 빈 문자열은 잠금 해제 (BR-8) 트리거이므로 {@code memoProvided} 플래그로
 * "키 없음 vs 빈 문자열" 을 명시적으로 구분한다.</p>
 *
 * <p>Phase 2.8: placeName/address 부분 수정을 위해 필드 4→8 로 확장한다. placeName 은 비-blank 필수,
 * address 는 키 없음/null/빈 문자열 모두 미변경(DTO 레이어에서 정규화).</p>
 *
 * <p>Phase 2.10: 좌표 수정 확장 (단일 coordinateProvided 플래그 + lat/lng pair). 좌표는 의미상
 * 분리 불가능한 단위이므로 텍스트 4필드의 독립 패턴과 달리 lat/lng 두 값을 하나의 플래그로 묶는다.</p>
 */
public record PinUpdateCommand(
        boolean memoProvided,
        String memo,
        boolean tagProvided,
        PinTag tag,
        boolean placeNameProvided,
        String placeName,
        boolean addressProvided,
        String address,
        boolean coordinateProvided,
        BigDecimal latitude,
        BigDecimal longitude
) {

    public static PinUpdateCommand of(boolean memoProvided, String memo,
                                      boolean tagProvided, PinTag tag,
                                      boolean placeNameProvided, String placeName,
                                      boolean addressProvided, String address,
                                      boolean coordinateProvided, BigDecimal latitude, BigDecimal longitude) {
        // Q5 정책: 빈/null address 는 "미변경" 의미. DTO 레이어가 정규화하지만
        // 도메인 command 자체에서도 invariant 를 강제하여 changePlaceInfo 가 address 를
        // null 로 덮어쓰는 정책 위반을 차단한다.
        if (addressProvided && address == null) {
            addressProvided = false;
        }
        if (!memoProvided && !tagProvided && !placeNameProvided && !addressProvided && !coordinateProvided) {
            throw new CoreException(ErrorType.PIN_UPDATE_EMPTY);
        }
        if (tagProvided && tag == null) {
            throw new CoreException(ErrorType.PIN_TAG_INVALID);
        }
        if (memoProvided && memo != null && memo.length() > 500) {
            throw new CoreException(ErrorType.PIN_MEMO_TOO_LONG);
        }
        if (placeNameProvided) {
            if (placeName == null || placeName.isBlank() || placeName.length() > 200) {
                throw new CoreException(ErrorType.PIN_PLACE_NAME_INVALID);
            }
        }
        if (addressProvided && address.length() > 500) {
            throw new CoreException(ErrorType.PIN_ADDRESS_INVALID);
        }
        if (coordinateProvided) {
            if (latitude == null || longitude == null) {
                throw new CoreException(ErrorType.PIN_COORDINATE_INVALID);
            }
            if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
                throw new CoreException(ErrorType.PIN_COORDINATE_INVALID);
            }
            if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
                throw new CoreException(ErrorType.PIN_COORDINATE_INVALID);
            }
        }
        return new PinUpdateCommand(memoProvided, memo, tagProvided, tag,
                placeNameProvided, placeName, addressProvided, address,
                coordinateProvided, latitude, longitude);
    }
}
