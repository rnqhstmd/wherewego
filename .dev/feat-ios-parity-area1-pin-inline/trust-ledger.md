# Trust Ledger — P8 영역1 핀 추가 인라인화

> phase-review 통합 감사(qa-manager + security-auditor), 2026-06-04.
> 합산: CRITICAL 0 / HIGH 2 / MEDIUM 3 / LOW 2 / Warning 1(qa 신규). **보안 취약점(OWASP Top 10) 없음** — validatePinInput 보존, 좌표/주소 로깅 없음, 하드코딩 시크릿 없음, 새 외부 의존성 없음.

## HIGH
- [GAP/HIGH] **clusterTapped 인라인 모드 차단 미구현 (BR-2 파생)** — `MapViewModel.handle(.clusterTapped)`에 `guard !isAddingPin` 없음. `markerTapped`(AC-13)는 가드 있음. 클러스터 탭 → `fitBoundsCommand`/`cameraCommand` 발행 → 지도 강제이동 → `cameraIdle`(pendingProgrammaticIdle 미계상) → `onMapMoved` → 콕찍기 중심 오염.
  - 권고: `case .clusterTapped` 진입부에 `guard !isAddingPin else { return }` 추가(markerTapped와 동일).
- [GAP/HIGH] **isCreating 미리셋** — `exitAddPin → cancelPendingWork → createTask.cancel` 후 `addPlaceVM=nil` 시 `performCreate`의 `defer{isCreating=false}`가 무의미한 nil VM에서 실행. 일시적 UI 불일치(취소 버튼 `.disabled(isCreating)`).
  - 권고: `cancelPendingWork`에 `isCreating = false` 명시(defer와 중복돼도 조기 종료 경로 명시).

## Warning (qa 신규)
- [PERF/Warning] **entryZoomTask 미취소** — `applyAddPinEntryZoom`의 권한 허용 Task가 `exitAddPin` 후에도 `requestOneShot`을 최대 5초 실행(위치 서비스 낭비). flyTo는 `!userDraggedSinceEntry && isAddingPin` 가드로 방어되나 위치 요청 자체는 지속.
  - 권고: `private var entryZoomTask: Task<Void,Never>?` 저장 → `exitAddPin`에서 `cancel()`.

## MEDIUM
- [GAP/MEDIUM] **mapCenter nil 시 FR-9/FR-11 스킵** — `bumpZoomOnly`/`seedInitialPinpoint` 모두 `guard let center = mapCenter else { return }`. Placeholder 폴백(토큰 미설정, cameraIdle 미발생) 또는 첫 진입 idle 미도착 race에서 `mapCenter` nil → 진입 역지오/줌인 완전 no-op. (#6 notDetermined→bumpZoomOnly no-op 동일 뿌리)
  - 권고: `applyInitialCamera`에서 `mapCenter` 동시 시드, 또는 `seedInitialPinpoint`/`bumpZoomOnly`에 서울시청 폴백.
- [GAP/MEDIUM] 생성 성공 `flyTo`→`didCreate=true`→`exitAddPin` 타이밍 창 — 현재 `pendingProgrammaticIdle`로 커버되나 원자성 미검증. 권고: 주석으로 의도 명시.
- [GAP/MEDIUM] `cancelPendingWork`가 검색 상태(didSearch/results/query) 미초기화 — 실오류 없음(VM 폐기로 복원). 권고: 책임 범위 주석.

## LOW
- [ASSUMPTION/LOW] `onChange(of: addPlaceVM?.didCreate)` Optional 체인 옵저버 iOS 16/17 동작 미검증(자기점검 기발견, 실버그 없음). 권고: MapViewModel `@Published` 플래그 또는 Combine sink.
- [ASSUMPTION/LOW] `withTaskGroup` actor 격리 가정 — `LocationServiceProtocol.requestOneShot()` actor 요구 미명시(기능상 정상). 권고: 주석.

## QUESTION (경미, 기록)
- `MapView:42` addVM 캡처 후 `viewModel.addPlaceVM?.selectResult` 재옵셔널 → `addVM.selectResult` 통일 권장(자기점검 Info 중복).
- `InlineAddPlaceModeTests` 함수명(`whenMapZoomNil`) vs 실제(`cameraIdle(zoom:3)` 시드) 괴리 — 가정값 3 동치라 동작 동일, 함수명 정정 권장.

## 정합성 검증 (PRD 엣지케이스 대비 — security-auditor)
- ✅ 정합: 역지오실패→좌표폴백 / 그룹미선택→에러 / 생성중드래그차단(BR-3) / 검색0건안내 / 네트워크오류→모드유지 / 룰렛·방문토스트 공존 / BR-1 탭전환 종료 / BR-3 생성중 취소비활성 / MUST-1 검색보존 / MUST-3 취소 / AC-13 마커차단 / validatePinInput(BR-5) / AC-11 / AC-15 / MUST-4
- ❌ 불일치: **BR-2 clusterTapped 차단** (위 HIGH #1) → 처리 결과에서 해소.

## 처리 결과 (phase-review Step 4 — "전부 수정", 2026-06-04)
> coder agent 반복 파싱 실패로 오케스트레이터가 직접 9건 Edit(Windows 컴파일 불가 → grep 정합 확인).
- ✅ [HIGH-1] `handle(.clusterTapped)`에 `guard !isAddingPin` 추가 — BR-2 클러스터 탭 차단(콕찍기 좌표 오염 제거).
- ✅ [HIGH-2] `cancelPendingWork`에 `isCreating = false` 명시 — 조기 종료 경로 취소 버튼 상태 정리.
- ✅ [Warning] `entryZoomTask` 프로퍼티 + `applyAddPinEntryZoom` 할당 + `exitAddPin` cancel — 모드 종료 후 위치 요청 5초 낭비 차단.
- ✅ [MEDIUM-3] `applyInitialCamera` 양 분기 `mapCenter` 시드 — Placeholder/첫 진입 race의 seed/bump no-op(FR-9/11 스킵) 해소.
- ✅ [MEDIUM-4] `performCreate` flyTo→didCreate 원자성 주석.
- ✅ [MEDIUM-5] `cancelPendingWork` 책임 범위 주석.
- ✅ [스타일] `MapView.onSelectResult` → `addVM.selectResult` 통일.
- ✅ [견고화] `MapView.onChange(addPlaceVM?.didCreate)` 제거 → `performCreate`가 didCreate 직후 `exitAddPin()` 직접 호출(Optional 체인 관찰 누락 창 제거).
- ✅ [테스트] `InlineAddPlaceModeTests` 함수명 `test_enterAddPin_whenZoomBelowMin_seoulCityHall_bumpsZoomToFallback`로 정정.
