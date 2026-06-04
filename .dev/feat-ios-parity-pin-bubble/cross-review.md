# Cross-Review 결과

- advisor: claude (qa-manager + security-auditor, cross-review 미션)
- 브랜치: feat/ios-parity-pin-bubble (base: develop, merge-base a13a689)
- DEV_DIR: .dev/feat-ios-parity-pin-bubble
- 실행: 2026-06-04

## AC 충족 매트릭스
**[Must] AC-1~14 전량 코드 레벨 충족.** AC-2(꼬리 앵커)는 `.position(anchor.y - h/2 - markerGap)` 구조 정확, 단 꼬리 테두리 시각은 DoD-B 이월(N1 참조). AC-3 추적·AC-9 일시숨김·AC-13 재탭·AC-14 화면밖 모두 코드+단위테스트로 확인.

## 설계 범위 이탈
**이탈 없음.** 변경 10파일(GeoMath/MapContainerView/MapRenderer/MapboxMapView/MapView/MapViewModel/PinBubbleView/PinDetailContent(←PinDetailSheet)/MapRendererMocks/MapViewModelTests) 모두 설계서 "변경 범위" 표 또는 구현 순서 1~11단계에 명시됨.

## 신규 위험 (trust-ledger/self-check에 없는 것만)

### Warning
- **N1 [시각] PinBubbleView.swift:90 — BubbleTail 테두리 stroke 미적용**
  - 본체(RoundedRectangle)는 `WGColor.hairline` stroke overlay 있으나 꼬리 삼각형은 `.fill`만 → 꼬리-본체 접합부 테두리 끊김. 웹 `SpeechBubblePopup` 꼬리는 본체 border와 연속.
  - 권고: BubbleTail에 `.stroke(WGColor.hairline, lineWidth:1)` overlay 또는 stroke shape 겹침. (DoD-A 코드)

### MEDIUM (security GAP)
- **N2 [GAP] PinBubbleView.swift:50 — BR-3 배경탭 가드가 `isPhotoBusy`만, `isMutating` 구간 미보호**
  - 핀 삭제(DELETE 응답 대기) 등 `PinDetailContent.isMutating=true` 구간에 배경탭하면 `isPhotoBusy=false`라 `closeBubble()` 즉시 실행 → 삭제 결과/실패 inlineError 미표시, `onRequestClose` 이중 호출 가능. PRD BR-3 원문은 "업로드/삭제"만 명시하나 mutating 일반에 공백.
  - 권고: 배경탭 가드를 `guard !detailVM.isPhotoBusy, !contentIsMutating`로 확장(최소 삭제 진행 구간 보호). isMutating을 PinBubbleView가 관찰하도록 바인딩/콜백 노출.
- **N3 [GAP/ASSUMPTION] PinDetailContent.swift:94-110 — 사진 피커/크롭 시트가 `activeSheet` 1패널 규칙 우회**
  - `showPhotoPicker`(.sheet)/`pickedImage`(.fullScreenCover)가 `@State`로 `MapViewModel.activeSheet`를 거치지 않음. 대부분 "말풍선 내 하위 모달"이라 의도된 동작이나, BR-2 예외 목록(addPlace/roulette/visitMemo)에 미명시. 크롭 중 외부 `activeSheet` 변경 시 BubbleOverlay unmount → 크롭 폐기(isPhotoBusy 미보호 단계).
  - 권고: "사진 피커/크롭은 activeSheet 1패널 예외"임을 설계서/주석에 명시. 크롭 중 강제 닫힘=폐기 정책 명문화(편집 중 다른 시트=폐기 정책과 일관).

### LOW
- **N4 [ASSUMPTION] selectedPinId @Published 공개 setter — clearSelectedPinScreenPoint 동반 계약 미강제**
  - `closeBubble()`이 `selectedPinId=nil` 직접 쓰기. 향후 다른 호출부가 screenPoint 동반 해제를 누락하면 잔상 위험. 현재는 설계 주석으로 계약 표현.
  - 권고: setter `private(set)` + `selectPin(id:screenPoint:)`/`deselectPin()` 전용 메서드로 접근 일원화(향후 리팩토링).

## references 위반
해당 없음 (references/ 디렉토리 없음).

## 총평
- 강점: (1) self-check Critical이던 `.position` 앵커 분리 + PreferenceKey 높이보정을 코드로 완전 해소. (2) QE-1 3중 차단이 설계대로 구현+distinct 테스트 검증.
- 합산: Critical 0, Warning 1(N1 시각), MEDIUM 2(N2 삭제중 가드 / N3 사진시트 정책), LOW 1(N4 캡슐화).
- 권고: N2(삭제 중 배경탭 보호)가 실질 — 코드 수정 권장. N1(꼬리 테두리)은 시각 정합 코드 1~2줄. N3는 정책 주석. N4는 향후.

## 처리 결과 (커밋 6ea0834, PR #96 반영)
- **N1 수정됨**: 본체+꼬리를 단일 `BubbleShape` 외곽선으로 합쳐 접합부 테두리 연속(꼬리 윗변 이중선 없음). 픽셀 미세조정 DoD-B.
- **N2 수정됨**: `isMutating`을 `PinDetailViewModel`로 이동(@Published 단일 관찰원), 배경탭 가드를 `!isPhotoBusy, !isMutating`로 확장 → 삭제/저장 진행 중 말풍선 닫힘 방지 + closeBubble 이중호출 차단.
- **N3 수정됨**: 사진 피커/크롭 = activeSheet 1패널 예외 정책 주석 추가(동작 변경 없음).
- **N4 보류**: selectedPinId 캡슐화(selectPin/deselectPin)는 리팩토링 범위라 향후 과제로 기록.
- 격리 게이트(import MapboxMaps 1파일) 유지. Windows 빌드 불가 → 시각/빌드 최종 검증 DoD-B(Mac).
