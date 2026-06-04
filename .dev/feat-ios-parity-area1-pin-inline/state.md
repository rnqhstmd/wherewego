phase: implement
status: in_progress
vcs-type: git
branch: feat/ios-parity-area1-pin-inline
base: develop
dev-dir: .dev/feat-ios-parity-area1-pin-inline
worktree: .claude/worktrees/feat-ios-parity-area1-pin-inline
project-type: [java-spring, node, ios-swift]
project-root: ./
args: "워크트리를 활용해서 분기후 phase p8 영역1 개발 시작해줘"
mode: normal
intent-source: user-selection
flags: (none)
started: 2026-06-04
current-step: "implement — coder 구현 + 자기점검"
scope: "P8 영역1 — 핀 추가 인라인화(＋ 별도 시트 → 메인 지도 중앙 십자선 + 얇은 하단 확정 카드, 웹 CrosshairOverlay/AddPinPickerContent 정합)"
phases:
  setup: completed
  requirements: completed
  design: completed
  implement: in_progress
  review: pending
  complete: pending
execution-log:
  - phase: setup
    result: "develop 기반 워크트리 생성(a13a689, #94 머지 = P7 포함) + EnterWorktree 진입. 코드맵 작성."
  - phase: requirements
    agent: product-owner
    result: "PRD 확정. Q&A 2건(검색=하단카드 검색창, 줌=웹 동일). Must 10+Should 3, 수용기준 코드16/Mac10. 사용자 승인."
  - phase: design
    agent: architect + design-critic
    result: "대형 설계 확정. critic MUST-ADDRESS 4건(검색 flyTo 덮어쓰기/진입 경쟁/Task 취소/mapZoom nil) → architect 보강(pendingProgrammaticIdle 카운터·줌인seed 배타·createTask 취소·mapZoom 시드). Q6(createTask 취소)·Q7(notDetermined 즉시14) 권장 채택. AC 16+4=20. 사용자 ㄱㄱ로 승인 생략·구현 진입."
design-decisions:
  - "5확정: AddPlaceSheet 삭제 / isResolvingAddress 추가 / one-shot 5초 자체 / 줌상수 신규(13/15/14) / 십자선opacity+카드슬라이드업"
  - "critic 4해소: pendingProgrammaticIdle 카운터(MUST-1), enterAddPin 줌인·seed 배타+userDragged 가드(MUST-2), isCreating 중 취소차단+createTask/Debouncer.cancel(MUST-3), mapZoom 시드+가정값3(MUST-4)"
implement-plan:
  - "B1(병렬): 1.MapEvent.cameraIdle zoom추가(MapRenderer+MapboxMapView #if/#else) / 2.CrosshairOverlay.swift 신규 / 4.AddPlaceViewModel.isResolvingAddress+3.5 Debouncer.cancel/cancelPendingWork/createTask"
  - "B2: 3.MapViewModel 인라인 상태/메서드(enterAddPin/exitAddPin/handle 3분기/applyAddPinEntry/줌상수)"
  - "B3: 5.InlineAddPlaceCard.swift 신규"
  - "B4(병렬): 6.MapView 오버레이통합 / 7.MainTabView 시트제거+토글"
  - "B5: 8.AddPlaceSheet.swift 삭제"
  - "B6: 9.테스트(InlineAddPlaceModeTests 신규 + cameraIdle zoom인자) / B7: 10.QE-1 애니메이션"
notes:
  - "iOS 빌드/시뮬레이터 검증 Windows 제약 → swift 컴파일/테스트는 Mac DoD-B. coder는 코드 정확성+기존 패턴 일관성에 집중."
  - "P8 영역2(feat/ios-parity-pin-bubble)·영역4(feat/ios-p8-area4-tabbar) 워크트리 병존. 영역1·2 point(for:) 공통기반은 영역2에서."
