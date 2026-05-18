# 자기점검 결과 (Phase 2.8)

## CERTAIN — Critical
**없음.**

## CERTAIN — Warning (phase-review 이월)

### W1. [MemoTagPanelContent.tsx:77] instagramUrl 중복 오류 메시지 불일치
- 코드: `PLC_DUPLICATE_PIN → "이미 등록된 장소예요"`
- PRD AC-5 명시: "이미 등록된 Instagram URL입니다"
- **참고**: 사용자 Q3 결정에서 "이미 등록된 장소예요 단일 유지" 채택. 코드는 사용자 결정 따랐고, PRD AC-5 문구가 사용자 결정 이전의 PRD 초안에 남아 있음.
- 권고: PRD AC-5의 검증 문구를 "이미 등록된 장소예요"로 갱신하여 정합성 확보. 또는 phase-review에서 사용자 재확인.

### W2. [PinEditDialog.tsx:107-113] address 빈 문자열 저장 시 UX 불일치
- addressChanged=true이지만 빈 문자열이면 patch.address 키 생략 → 사용자가 "지움" 의도로 저장해도 변경 없음
- 사용자 Q5 결정: address 빈 문자열은 미변경
- 권고: 저장 버튼 클릭 시 "주소는 변경되지 않습니다" 안내 또는 addressChanged 비교 시 양쪽 trim 빈 문자열은 changed=false로 간주

### W3. [MemoTagPanelContent.tsx 필드 순서] Q4 결정과의 적용 범위 확인 필요
- 현재 순서: 장소명(picker만) → 태그 → 메모 → instagramUrl
- Q4는 **PinEditDialog**(/pins 편집) 한정 결정 → MemoTagPanelContent와 무관 (qa-manager 오해 가능성)
- 권고: 정상 적용. Q4는 /pins 편집 다이얼로그 순서이며 MemoTagPanelContent는 별개. phase-review에서 명시 검증.

## CERTAIN — Info (phase-review 이월)

### I1. [PinEditDialog.tsx:59] placeName trim 중복
`pin.placeName.trim()`은 백엔드에서 이미 trim된 값이라 불필요한 중복. 실 버그 없음.

### I2. [map/actions.ts:99-101] DeletePinActionResult 타입 중복
map/actions.ts와 pins/actions.ts 양쪽에 동일 구조 정의. 향후 lib/api/types.ts로 추출 가능.

### I3. [lib/pin/constants.ts:14] MEMO_MAX_LENGTH 주석의 "502자" 오기
"501자"가 맞음 (500자 초과 = 501자부터 거부). 문서 오류, 코드 동작 영향 없음.

## QUESTION (phase-review 이월)

### Q1. MemoTagPanelContent.tsx:44 — 검색 진입 시 placeName 빈 가능성 방어 필요?
검색 API 응답이 항상 non-empty placeName을 보장하는가? 클라이언트 방어 코드 부재.

### Q2. MapClient.tsx:874-884 — deleteErrorByPinId 초기화 순서
삭제 버튼 클릭 시 에러를 먼저 제거하는데, 재시도 UX상 의도된 것인가?

### Q3. PinV1Dto.java:158 — placeName 빈 문자열 검증 책임 레이어
현재 DTO trim 후 빈 문자열은 placeNameProvided=true로 Command에 전달 → Command가 PIN_PLACE_NAME_INVALID. DTO에서 미리 placeNameProvided=false로 정규화하지 않는 이유?

## AC 매핑 결과

| # | 상태 | 비고 |
|---|------|------|
| AC-1~4 | ✅ | 충족 |
| **AC-5** | ⚠️ | 코드 "이미 등록된 장소예요"는 Q3 결정 반영. PRD 문구 잔재. |
| AC-6~16 | ✅ | 충족 |
| **AC-17** | ⚠️ | 코드 "권한이 없어요"는 다른 친근체 메시지와 일관. PRD BR-5 "권한이 없습니다" 문구 잔재. |

**충족: 15/17 (실질 충족 17/17, 문구만 PRD-코드 불일치)**

## 종합

- 변경 파일: 16개
- Critical: 0
- Warning: 3
- Info: 3
- QUESTION: 3
- 자동 수정 불필요. phase-review에서 사용자 확인 후 PRD 문구 갱신 또는 코드 수정 결정 필요.
