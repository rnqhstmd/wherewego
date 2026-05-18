<task>
oh-my-gx 파이프라인 산출물(PRD/설계/Trust Ledger)과 변경 코드를 교차 검증한다.
변경된 코드가 산출물의 약속을 충족하는지, 산출물에 정의되지 않은 신규 위험이 있는지 보고한다.

PROJECT_ROOT: D:\SQ\wherewego
diff 파일: D:\SQ\wherewego\.dev\feat-phase-2-8-pin-ux\diff.txt
이 파일은 21개 파일의 stat 요약(816 insertions / 56 deletions)이다. 변경된 파일을 Read 도구로 직접 확인하라.
</task>

<grounding_rules>
- 모든 지적은 PRD 또는 설계서의 정확한 인용으로 근거를 제시한다.
- trust-ledger.md에 이미 보고된 항목은 보고하지 않는다 (중복 금지).
- self-check.md의 Warning/Info는 중복 보고하지 않는다.
- 코드를 직접 확인하지 못한 추정은 ASSUMPTION으로 분리한다.
- PRD 자체가 코드와 일치하지 않을 가능성이 의심되면 ASSUMPTION으로 분류한다.
</grounding_rules>

<structured_output_contract>
다음 4개 섹션을 정확히 이 순서로 출력한다:

## AC 충족 매트릭스
| AC | 충족 | 근거 (파일:라인 또는 PRD 인용) |
|----|------|--------|
| AC-1 | O/X/부분 | ... |

## 설계 범위 이탈
설계서의 "변경 범위"에 명시되지 않은 파일 수정 목록.
항목별로: 파일 경로 / 변경 요약 / 이탈 사유 추정.
없으면 "이탈 없음".

## 신규 위험
trust-ledger.md에 없는 신규 risk/policy/gap/assumption만.
- [Critical/Warning/Info] [RISK/POLICY/GAP/ASSUMPTION] 항목 설명
  - 위치: 파일:라인
  - 근거: ...
  - 권고: ...

## 총평
- 강점 1-2개
- Critical/Warning 합산
- 권고 사항 1줄
</structured_output_contract>

<language>
모든 출력은 한국어로 작성한다. 영어 단어는 고유명사·기술 용어에 한해 허용한다.
</language>

<artifacts>

### PRD 수용 기준 (AC 17건)

| # | 수용 기준 | 연결 |
|---|----------|------|
| AC-1 | 검색 선택 흐름에서 "Instagram URL (선택)" 입력 필드가 태그/메모 패널에 표시된다 | FR-1 |
| AC-2 | 십자선 picker 흐름에서 완료 후 진입하는 입력 패널에 instagramUrl 입력 필드가 표시된다 | FR-2 |
| AC-3 | `https://` 미시작 URL 입력 시 등록 버튼이 비활성화되고 "올바른 URL 형식이 아닙니다" 메시지가 표시된다 | FR-3 |
| AC-4 | instagramUrl 미입력 상태로 등록 시 null로 저장되어 정상 등록된다 | BR-1 |
| AC-5 | 동일 그룹에 동일 instagramUrl로 두 번 등록 시 "이미 등록된 장소예요" 오류가 표시된다 (Q3: 단일 메시지) | BR-2 |
| AC-6 | PATCH /api/v1/groups/{groupId}/pins/{pinId}가 placeName 및 address 수정 요청을 처리하고 변경된 값을 응답한다 | FR-4 |
| AC-7 | /pins 편집 다이얼로그에서 장소명/주소 수정 후 저장 시 목록 카드에 즉시 반영된다 | FR-5, QE-2 |
| AC-8 | placeName을 공백만으로 저장 시도 시 저장 버튼이 비활성화된다 | BR-4 |
| AC-9 | 활성 멤버라면 등록자가 아닌 핀도 장소명/주소 수정이 가능하다 | BR-3 |
| AC-10 | placeName 200자 초과 입력 시 저장 버튼이 비활성화된다 | BR-4 |
| AC-11 | 지도 팝업 ⋮ 펼침 시 세그먼트 탭("태그/메모") 외부에 "삭제" 버튼이 표시된다 | FR-6, BR-6 |
| AC-12 | "삭제" 버튼은 탭 영역과 시각적으로 구분된 레이아웃으로 배치된다 | BR-6 |
| AC-13 | "삭제" 버튼 클릭 시 확인 모달이 표시되며 장소명이 모달에 포함된다 | FR-7 |
| AC-14 | 확인 모달에서 "취소" 또는 ESC로 닫으면 삭제가 실행되지 않고 ⋮ 펼침 상태가 유지된다 | FR-7 |
| AC-15 | 확인 모달에서 "삭제" 확인 시 마커가 즉시 지도에서 제거되고 팝업이 닫힌다 | FR-7, QE-1 |
| AC-16 | 지도 팝업 삭제 중 서버 오류 시 마커가 복원되고 팝업 내 인라인 에러 메시지가 표시된다 | FR-7 |
| AC-17 | 활성 멤버가 아닌 사용자의 지도 삭제 시도 시 팝업 내 "권한이 없습니다" 오류가 표시된다 | BR-5 |

비즈니스 규칙 상세 (PRD):
- placeName 공백만 → 클라이언트에서 저장 버튼 비활성
- placeName > 200자 → 비활성
- address 빈 문자열 또는 미입력 → null 허용 (PRD), 실제 코드는 Q5 결정에 따라 "미변경" 정규화로 안전 무시
- 지도 팝업 비-멤버 삭제 → "권한이 없습니다" (PRD), 코드는 "권한이 없어요" 친근체 사용 (Q3 단일 메시지 패턴과 일관)

### 설계서 변경 범위 (수정 12개 파일)

Backend (5):
1. Pin.java — changePlaceInfo(String, boolean, String) 메서드 신규
2. PinUpdateCommand.java — 필드 4→8 (memoProvided/memo/tagProvided/tag + placeNameProvided/placeName/addressProvided/address). of() 검증 확장
3. PinService.java — updatePin에 placeName/address 분기 추가
4. PinV1Dto.java — UpdatePinRequest 필드 2→4. address 빈 문자열은 addressProvided=false로 정규화 (Q5)
5. ErrorType.java — PIN_UPDATE_EMPTY 메시지 일반화

Frontend (7):
6. lib/api/pin.ts — PinPatch에 placeName/address: string 추가
7. lib/pin/constants.ts — PLACE_NAME_MAX_LENGTH=200, ADDRESS_MAX_LENGTH=500 신규
8. map/_components/MemoTagPanelContent.tsx — instagramUrl 입력 + https:// 검증 (검색·picker 양 경로 자동 커버)
9. map/_components/PinPopup.tsx — footer 삭제 버튼 + onRequestDelete + deleteError props (HLine + 우측 정렬, colors.pinNew)
10. map/actions.ts — deletePinAction 신규 (try/catch + revalidatePath("/pins")만, /map은 미호출)
11. map/MapClient.tsx — useOptimistic reducer 일반화({kind:"patch"|"remove"}), deleteCandidate/deleteErrorByPinId state, PinDeleteConfirm 재사용 렌더
12. pins/_components/PinEditDialog.tsx — placeName/address 편집 필드 (순서: 장소명→주소→태그→메모)
13. pins/PinListClient.tsx — applyPatch reducer에 placeName/address 케이스 추가

추가로 phase-review 결과 반영하여 다음이 추가됨:
- Pin.java + ErrorType.java — validateInstagramUrl 헬퍼 + PIN_INSTAGRAM_URL_INVALID (HIGH H1 해소)
- PinCard.tsx — 조건부 href 렌더 (XSS 방어 이중화)
- PinEditDialog.tsx — placeNameChanged 단방향 trim (W1) + address 빈 값 안내 (W4)
- PinService.java — memo null 방어 (W2)
- PinServiceIT, PinV1ControllerIntegrationTest — address-only / 빈 address 단독 테스트 추가 (M2/M3)
- MemoTagPanelContent.tsx — effectivePlaceName 폴백 (SQ1)

설계상 신규 파일: 없음. 모달은 PinDeleteConfirm 재사용 (사용자 Q1 결정).

구현 순서:
1. B1 Backend 일괄 (의존 없음)
2. B2 Frontend API 클라이언트 (의존: B1)
3-5. B3 /pins / B4 /map 등록 / B5 /map 삭제 (의존: B2, 병렬 가능)

### 기존 Trust Ledger (이미 보고된 항목, 중복 금지)

Mechanical Gate:
- Frontend npm build: 차단 (node_modules 미설치)
- Backend gradlew test: 차단 (JDK 21 미설치)
- 사용자 별도 검증 예정

QA 통합 리뷰 (이미 처리됨):
- Warning 4건 (W1~W4): W1 placeName trim 비대칭, W2 PinService memo null 방어, W3 AC-5 문구 불일치, W4 address 빈 문자열 UX
- Info 3건 (I1~I3): I1 constants.ts 주석 "502자" 오기 (미해소, 다음 Phase), I2 DeletePinActionResult 타입 중복, I3 PinV1ControllerIntegrationTest 주석
- QUESTION 3건 (SQ1~SQ3): SQ1 검색 placeName 폴백, SQ2 deleteErrorByPinId 초기화, SQ3 placeName 검증 책임 레이어

ZeroTrust 감사 (이미 처리됨):
- HIGH H1: PinCard.tsx instagramUrl 비-HTTPS URL XSS/오픈 리다이렉션 → 백엔드 validateInstagramUrl + 프론트 조건부 href로 해소
- MEDIUM M1~M7: M1 address 빈 문자열 UX, M2 PinServiceIT address-only 누락, M3 PinV1ControllerIntegrationTest 빈 address 단독 누락, M4 https:// 검증 강도, M5 PinDeleteConfirm <dialog> z-index 미지정 (수동 검증 권고), M6 PinListClient handleSave 정합성, M7 PinUpdateCommand.of 호출처 빌드 미검증
- LOW L1~L3: L1 map/actions.ts 주석 통일, L2 PinPopup deleteError 표시 팝업 높이 증가, L3 PinService memo null 방어

사용자 결정 (Q1~Q6) 모두 반영:
- Q1 PinDeleteConfirm 재사용
- Q2 HLine + 우측 정렬 텍스트
- Q3 "이미 등록된 장소예요" 단일 메시지
- Q4 PinEditDialog 순서 장소명→주소→태그→메모
- Q5 address 빈 문자열 미변경
- Q6 /map deletePinAction try/catch + revalidatePath("/pins")

확인 리뷰 (qa-manager 단발성) 통과: Critical 0 / 회귀 0 / 8건 모두 ✅.

미해소:
- I1 constants.ts 주석 "502자" 오기 (사소, 다음 Phase)
- 빌드 환경 검증 (사용자 별도 수행)
- M5 Safari + mapbox-gl `<dialog>` backdrop 수동 검증

### 자기점검 발견 사항 (중복 금지)

phase-implement 자기점검: Critical 0건. Warning 3건 / Info 3건 / QUESTION 3건. 모두 phase-review에서 처리되어 Trust Ledger에 합산됨.

### 코드 맵 (탐색 가이드)

핵심 파일:
- backend/.../Pin.java — Phase 2.8: changePlaceInfo + validateInstagramUrl
- backend/.../PinUpdateCommand.java — 8필드 + of() 검증
- backend/.../PinService.java — updatePin 분기
- backend/.../PinV1Controller.java — CRUD 라우트 (변경 없음)
- backend/.../PinV1Dto.java — UpdatePinRequest 확장
- backend/.../ErrorType.java — PIN_INSTAGRAM_URL_INVALID 신규
- frontend/src/app/map/MapClient.tsx — useOptimistic reducer 일반화 + 삭제 흐름
- frontend/src/app/map/_components/PinPopup.tsx — footer 삭제 버튼
- frontend/src/app/map/_components/MemoTagPanelContent.tsx — instagramUrl 필드
- frontend/src/app/map/actions.ts — deletePinAction
- frontend/src/app/pins/_components/PinEditDialog.tsx — placeName/address 편집
- frontend/src/app/pins/PinListClient.tsx — applyPatch reducer 확장
- frontend/src/app/pins/_components/PinCard.tsx — instagramUrl 조건부 href
- frontend/src/lib/api/pin.ts — PinPatch 확장
- frontend/src/lib/pin/constants.ts — 길이 상수

PROJECT_ROOT: D:\SQ\wherewego

### references
없음 (디렉토리 미존재).

</artifacts>
