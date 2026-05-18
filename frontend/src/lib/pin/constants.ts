/**
 * 핀 메모 최대 길이.
 *
 * 백엔드 `ErrorType.PIN_MEMO_TOO_LONG`(500자) 한도와 동기화한다.
 * 변경 시 `backend/.../interfaces/api/pin/PinV1Dto.java`의 502자 검증도
 * 함께 변경해야 한다.
 */
export const MEMO_MAX_LENGTH = 500;

/**
 * 핀 장소명 최대 길이.
 *
 * 백엔드 `Pin.java`의 `placeName` 컬럼 length=200 및
 * `PinV1Dto.CreatePinRequest`의 `@Size(max = 200)` 검증과 동기화한다.
 */
export const PLACE_NAME_MAX_LENGTH = 200;

/**
 * 핀 주소 최대 길이.
 *
 * 백엔드 `PinV1Dto.CreatePinRequest`의 `@Size(max = 500)` 검증과 동기화한다.
 */
export const ADDRESS_MAX_LENGTH = 500;
