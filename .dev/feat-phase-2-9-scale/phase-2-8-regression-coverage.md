# Phase 2.8 회귀 커버리지 매핑 (Phase 2.9 검증용)

> cross-review(codex) Warning 후속 보강. PRD AC-10 ("Phase 2.8 AC 1~17 모두 충족")의 회귀 증적을 1:1 매핑으로 명시한다. Phase 2.8 PRD(`.dev/feat-phase-2-8-pin-ux/prd.md`)의 AC 1~17이 Phase 2.9 변경 후에도 무수정으로 통합 테스트에 의해 회귀 검증됨을 보인다.

## 회귀 가드 원칙

- Phase 2.9 는 `PinJpaRepository` paged 메서드명 변경(`OrderByCreatedAtDesc` 제거 + `Sort.by(createdAt DESC, id DESC)` 명시) 외 Phase 2.8 의 백엔드/도메인 API 시그니처를 변경하지 않는다.
- 따라서 Phase 2.8 통합 테스트(`PinServiceIT`, `PinV1ControllerIntegrationTest`) 의 placeName/address/instagramUrl/delete 흐름은 무수정으로 통과되어야 한다.
- 프론트 AC(③ 지도 팝업 삭제 UI, ② 편집 다이얼로그) 는 백엔드 API 계약을 통과하는 한 변경이 없다 (Phase 2.9 는 백엔드 페이지네이션 계약과 GL 분석 문서만 다룬다 — Phase 2.9 PRD §3.1, §3.2).

## 매핑

| Phase 2.8 AC | 영역 | 검증 테스트 | 검증 방식 |
|--------------|------|------------|----------|
| AC-1: 검색 선택 흐름에서 "Instagram URL (선택)" 입력 필드가 태그/메모 패널에 표시된다 (FR-1) | 프론트 UX | `MemoTagPanelContent` 컴포넌트 (Phase 2.8 PR #21 머지본) | UI 변경 없음 (Phase 2.9 는 프론트 무변경) |
| AC-2: 십자선 picker 흐름에서 완료 후 진입하는 입력 패널에 instagramUrl 입력 필드가 표시된다 (FR-2) | 프론트 UX | `AddPinPickerContent` → `MemoTagPanelContent` 공통 (Phase 2.8 PR #21) | UI 변경 없음 |
| AC-3: `https://` 미시작 URL 입력 시 등록 버튼이 비활성화되고 "올바른 URL 형식이 아닙니다" 메시지가 표시된다 (FR-3) | 백엔드 + 프론트 | `Pin.validateInstagramUrl` (도메인) — `PIN_INSTAGRAM_URL_INVALID` ErrorType | 백엔드 검증 로직 무변경; 프론트 검증 무변경 |
| AC-4: instagramUrl 미입력(빈 칸) 상태로 등록 시 null 로 저장되어 정상 등록된다 (BR-1) | 백엔드 | `Pin.validateInstagramUrl` (도메인 단위 정규화: trim 후 빈 문자열 → null) | 도메인 분기 무변경 — Phase 2.9 paged 메서드 추가만 |
| AC-5: 동일 그룹에 동일 instagramUrl 로 두 번 등록 시 "이미 등록된 장소예요" 오류가 표시된다 (BR-2) | 백엔드 | DB UNIQUE `uq_pins_group_instagram` + `DataIntegrityViolationException` 변환 (PR #5 ~ #21) | 제약 무변경 |
| AC-6: `PATCH /api/v1/groups/{groupId}/pins/{pinId}` 가 placeName 및 address 수정 요청을 처리하고 변경된 값을 응답한다 (FR-4) | 백엔드 API | `PinV1ControllerIntegrationTest.patchPin_placeNameOnly_returns200WithUpdatedPlaceName`, `patchPin_placeNameAndMemo_returns200WithBothApplied` + `PinServiceIT.updatePin_placeNameOnly_updatesPlaceNameAndKeepsAddress`, `updatePin_addressOnly_updatesAddressOnly`, `updatePin_placeNameAndAddress_updatesBoth` | 통합 테스트 (무수정 통과) |
| AC-7: `/pins` 편집 다이얼로그에서 장소명/주소 수정 후 저장 시 목록 카드에 즉시 반영된다 (FR-5, QE-2) | 프론트 UX | `PinEditDialog` + `PinListClient.applyPatch` (Phase 2.8 PR #21) | UI 무변경 (백엔드 API 계약 동일) |
| AC-8: placeName 을 공백만으로 저장 시도 시 저장 버튼이 비활성화된다 (BR-4) | 프론트 UX | `PinEditDialog` 클라이언트 trim 검증 (Phase 2.8 PR #21) | 프론트 무변경 + 백엔드 보강 (`PinV1ControllerIntegrationTest.patchPin_emptyPlaceName_returnsPinPlaceNameInvalid`) |
| AC-9: 활성 멤버라면 등록자가 아닌 핀도 장소명/주소 수정이 가능하다 (BR-3) | 백엔드 + 프론트 | `PinServiceIT.updatePin_placeNameByAnotherActiveMember_succeeds`, `updatePin_placeNameByNonMember_throwsGroupNotMember` | 통합 테스트 (무수정 통과) |
| AC-10: placeName 200자 초과 입력 시 저장 버튼이 비활성화된다 (BR-4) | 프론트 UX | `PinEditDialog` 클라이언트 검증 (Phase 2.8 PR #21) + 백엔드 보강 (`PinV1Dto.UpdatePinRequest` `@Size(max=200)`) | 프론트 무변경 |
| AC-11: 지도 팝업 ⋮ 펼침 시 세그먼트 탭("태그 / 메모") 외부에 "삭제" 버튼이 표시된다 (FR-6, BR-6) | 프론트 UX | `PinPopup` footer HLine + 삭제 버튼 (Phase 2.8 PR #21) | UI 무변경 |
| AC-12: "삭제" 버튼은 탭 영역과 시각적으로 구분된 레이아웃으로 배치된다 (BR-6) | 프론트 UX | `PinPopup` footer 레이아웃 (Phase 2.8 PR #21) | UI 무변경 |
| AC-13: "삭제" 버튼 클릭 시 확인 모달이 표시되며 장소명이 모달에 포함된다 (FR-7) | 프론트 UX | `PinDeleteConfirm` 재사용 (Phase 2.8 PR #21) | UI 무변경 |
| AC-14: 확인 모달에서 "취소" 또는 ESC 로 닫으면 삭제가 실행되지 않고 ⋮ 펼침 상태가 유지된다 (FR-7) | 프론트 UX | `PinDeleteConfirm` + `PinPopup` 상태 보존 (Phase 2.8 PR #21) | UI 무변경 |
| AC-15: 확인 모달에서 "삭제" 확인 시 마커가 즉시 지도에서 제거되고 팝업이 닫힌다 (FR-7, QE-1) | 프론트 UX | `useOptimistic` reducer `patch\|remove` (Phase 2.8 PR #21) + 백엔드 `PinV1ControllerIntegrationTest.deletePin_returns204AndRemovesFromListing` | 통합 테스트 (무수정 통과) |
| AC-16: 지도 팝업 삭제 중 서버 오류 시 마커가 복원되고 팝업 내 인라인 에러 메시지가 표시된다 (FR-7) | 프론트 UX | `useOptimistic` 롤백 흐름 (Phase 2.8 PR #21) | UI 무변경 (백엔드 ErrorType 응답 포맷 무변경) |
| AC-17: 활성 멤버가 아닌 사용자의 지도 삭제 시도 시 팝업 내 "권한이 없습니다" 오류가 표시된다 (BR-5) | 프론트 UX + 백엔드 | `PinV1ControllerIntegrationTest.deletePin_nonMember_returns403` (`GROUP_NOT_MEMBER`) + 프론트 inline 에러 렌더링 (Phase 2.8 PR #21) | 통합 테스트 (무수정 통과) |

## 백엔드 통합 테스트 회귀 가드 (전수 통과)

Phase 2.8 회귀 가드 핵심은 다음 두 테스트 클래스의 무수정 통과다 (Phase 2.9 변경은 paged 메서드명/Sort 명시뿐, 호출 시그니처는 동일):

- `backend/apps/wherewego-api/src/test/java/com/wherewego/interfaces/api/pin/PinV1ControllerIntegrationTest.java`
  - `patchPin_memoAndTag_returns200`, `patchPin_emptyBody_returns400`, `patchPin_memoTooLong_returns400`, `patchPin_invalidTag_returns400`, `patchPin_emptyMemo_clearsLockInDb`
  - **Phase 2.8 신규 (AC-6/-8 영역)**: `patchPin_placeNameOnly_returns200WithUpdatedPlaceName`, `patchPin_emptyPlaceName_returnsPinPlaceNameInvalid`, `patchPin_emptyAddress_returns200WithUnchangedAddress`, `patchPin_addressOnlyEmpty_returns400PinUpdateEmpty`, `patchPin_placeNameAndMemo_returns200WithBothApplied`, `patchPin_addressTooLong_returnsPinAddressInvalid`
  - `patchPin_deletedPin_returns404`, `deletePin_returns204AndRemovesFromListing`, `deletePin_doubleDelete_returns404`, `deletePin_nonMember_returns403`
- `backend/apps/wherewego-api/src/test/java/com/wherewego/domain/pin/PinServiceIT.java`
  - `updatePin_memoOnly_updatesMemoAndKeepsTag`, `updatePin_tagOnly_updatesTagAndKeepsMemo`, `updatePin_emptyMemo_clearsLockAndAllowsAuto`, `updatePin_manualMemo_blocksAutoUpdate`
  - **Phase 2.8 신규**: `updatePin_placeNameOnly_updatesPlaceNameAndKeepsAddress`, `updatePin_placeNameByNonMember_throwsGroupNotMember`, `updatePin_placeNameByAnotherActiveMember_succeeds`, `updatePin_addressOnly_updatesAddressOnly`, `updatePin_placeNameAndAddress_updatesBoth`
  - `softDeletePin_removesFromListing`, `softDeletePin_byAnotherActiveMember_succeeds`, `softDeletePin_alreadyDeleted_throwsPinNotFound`, `softDeletePin_nonMember_throwsGroupNotMember`

## Phase 2.9 변경에 의한 회귀 위험 평가

| Phase 2.9 변경 | 영향받는 Phase 2.8 AC | 회귀 가드 |
|----------------|---------------------|----------|
| `PinJpaRepository` paged 메서드명 (`OrderByCreatedAtDesc` 제거) | 없음 — paged 메서드는 Phase 2.9 신규. 기존 List 반환 메서드(`findByGroupIdAndDeletedAtIsNullOrderByCreatedAtDesc`) 무변경 → AC-6/AC-15/AC-17 회귀 가드 메서드들이 통과 | `PinV1ControllerIntegrationTest.listPins_legacyMode_returnsItemsOnly_AC0_AC2` 가 List 반환 경로 통과 확인 |
| `Sort.by(createdAt DESC, id DESC)` 명시 (tie-breaker) | 없음 — 정렬 보조 키만 추가, primary 정렬 동일 | `listPins_pagination_noOverlap_AC6` + `listPins_pagination_tieBreaker_disjoint_AC6` (신규) |
| `PinV1Dto.PinListResponse` 에 `totalCount?/hasNext?` 선택 필드 추가 | 없음 — 파라미터 미전달 시 직렬화 미포함 (`@JsonInclude(NON_NULL)`) | `listPins_legacyMode_returnsItemsOnly_AC0_AC2` 가 legacy 응답 키만 검증 |

## 검증

`./gradlew :apps:wherewego-api:test` 전 테스트 통과로 Phase 2.8 AC 1~17 회귀 없음 증명. cross-review Warning 후속 신규 테스트 `listPins_pagination_tieBreaker_disjoint_AC6` 포함.
