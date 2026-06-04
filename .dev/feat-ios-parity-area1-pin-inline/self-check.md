# 자기점검 결과 — P8 영역1

> qa-manager 자기점검 1회 패스(2026-06-04). **AC-1~16 + 보강 AC-17~20 전부 코드레벨 충족.** AC-B1~10은 Mac DoD-B 이연.

## Critical (✅ 해소)
- [Critical/✅해소] `MapViewModel.swift:562-575` `requestOneShotWithTimeout` — `withCheckedContinuation`+공유 `var resumed`+두 `@MainActor` Task `resume` 경쟁 → **`withTaskGroup(of: LocationSample?.self)` 전환 완료**(위치요청 child + 타임아웃 child race → `group.next()` 후 `cancelAll()`). `LocationSample`은 Double/Double? 저장 프로퍼티라 암시적 Sendable. Swift6 Sendable 경계/data race 해소.

## Warning (phase-review 이월)
- [Warning] `MapView.swift:136` `onChange(of: viewModel.addPlaceVM?.didCreate)` 옵셔널 체인 수명 경계 — 실제 버그는 없음(`exitAddPin` guard가 중복 호출 차단). 단순화 권장: `performCreate`에서 `didCreate=true` 직후 직접 종료 처리하여 `onChange` 의존 제거. review 판단.
- [Warning] `MapViewModel.swift` `handle(.cameraIdle)` 2차 안전망 `inputMode != .search || selectedPlace == nil` 이중부정 가독성 — 기능 정상(드모르간상 `!(search && selected)`), `if ... { return }` 형태로 가독 개선 권장.

## Info (참고)
- [Info] `MapView` `onSelectResult`: `addVM` 로컬 unwrap 후 `viewModel.addPlaceVM?.selectResult` 재옵셔널 체인 → `addVM.selectResult`로 통일 권장.
- [Info] `InlineAddPlaceCard` `PinTag.allCases` 순서(REEL 먼저) vs 기본 `.WISH` — 표시/기본 불일치, 기능 무관(3개 모두 표시).
- [Info] `AddPlaceViewModel` `resolveAddress` guard(`inputMode==.pinpoint, pinpointCenter==center`)가 성공/실패 분기에 반복 → 진입 1회 통합 가능.

## QUESTION (해소됨 — 이월 불필요)
- [Q1] Sendable 적합성 → Critical 수정(withTaskGroup)으로 해소.
- [Q2] `isAddingPin==true`인데 `addPlaceVM==nil` 엣지 → `enterAddPin`이 `@MainActor` 동기 설정 + SwiftUI MainActor 렌더라 항상 일관. 방어적 unwrap 허용(qa 권장 a). **안전.**
- [Q3] `FloatingTabBar` ＋가 selection 건드리는지 → 확인 완료: `plusButton`은 `onPlusTap()`만 호출, selection 미변경(`FloatingTabBar.swift:82-84`). `tabButton`만 `selection=tab`. → `onChange(selection)` 오발화 없음. **안전 ✓.**

## coder 보고 특이사항 판정
1. `flyTo(pinId:)` → `flyTo(lat:lng:zoom:)` 통합: cameraCommand 결과 동일, `test_flyToPinId_...` 회귀 없음 — qa 확인됨.
2. `requestOneShotWithTimeout` race: Critical로 withTaskGroup 전환.
3. `mapZoom` private(set) nil 직접 주입 불가: `cameraIdle(zoom:3)` 차선 검증 + 코드상 `?? addPinAssumeZoomWhenUnknown(3)` 보장 — 허용.
4. 타 탭 ＋ 진입 시 십자선 map 탭만 렌더: FR-1 selection 불변 의도. 타 탭 ＋ 보정은 설계 범위 밖. (DoD-B/후속 판단)
