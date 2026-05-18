package com.wherewego.domain.pin;

import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;

/**
 * 핀 부분 수정 입력 도메인 객체 (Phase 4 §B3, Phase 2.8 확장).
 *
 * <p>memo 의 빈 문자열은 잠금 해제 (BR-8) 트리거이므로 {@code memoProvided} 플래그로
 * "키 없음 vs 빈 문자열" 을 명시적으로 구분한다.</p>
 *
 * <p>Phase 2.8: placeName/address 부분 수정을 위해 필드 4→8 로 확장한다. placeName 은 비-blank 필수,
 * address 는 키 없음/null/빈 문자열 모두 미변경(DTO 레이어에서 정규화).</p>
 */
public record PinUpdateCommand(
        boolean memoProvided,
        String memo,
        boolean tagProvided,
        PinTag tag,
        boolean placeNameProvided,
        String placeName,
        boolean addressProvided,
        String address
) {

    public static PinUpdateCommand of(boolean memoProvided, String memo,
                                      boolean tagProvided, PinTag tag,
                                      boolean placeNameProvided, String placeName,
                                      boolean addressProvided, String address) {
        if (!memoProvided && !tagProvided && !placeNameProvided && !addressProvided) {
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
        if (addressProvided && address != null && address.length() > 500) {
            throw new CoreException(ErrorType.PIN_ADDRESS_INVALID);
        }
        return new PinUpdateCommand(memoProvided, memo, tagProvided, tag,
                placeNameProvided, placeName, addressProvided, address);
    }
}
