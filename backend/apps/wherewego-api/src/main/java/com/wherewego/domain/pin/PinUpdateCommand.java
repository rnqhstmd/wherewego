package com.wherewego.domain.pin;

import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;

/**
 * 핀 부분 수정 입력 도메인 객체 (Phase 4 §B3).
 *
 * <p>memo 의 빈 문자열은 잠금 해제 (BR-8) 트리거이므로 {@code memoProvided} 플래그로
 * "키 없음 vs 빈 문자열" 을 명시적으로 구분한다.</p>
 */
public record PinUpdateCommand(
        boolean memoProvided,
        String memo,
        boolean tagProvided,
        PinTag tag
) {

    public static PinUpdateCommand of(boolean memoProvided, String memo, boolean tagProvided, PinTag tag) {
        if (!memoProvided && !tagProvided) {
            throw new CoreException(ErrorType.PIN_UPDATE_EMPTY);
        }
        if (tagProvided && tag == null) {
            throw new CoreException(ErrorType.PIN_TAG_INVALID);
        }
        if (memoProvided && memo != null && memo.length() > 500) {
            throw new CoreException(ErrorType.PIN_MEMO_TOO_LONG);
        }
        return new PinUpdateCommand(memoProvided, memo, tagProvided, tag);
    }
}
