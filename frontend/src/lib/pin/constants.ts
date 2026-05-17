/**
 * 핀 메모 최대 길이.
 *
 * 백엔드 `ErrorType.PIN_MEMO_TOO_LONG`(500자) 한도와 동기화한다.
 * 변경 시 `backend/.../interfaces/api/pin/PinV1Dto.java`의 502자 검증도
 * 함께 변경해야 한다.
 */
export const MEMO_MAX_LENGTH = 500;
