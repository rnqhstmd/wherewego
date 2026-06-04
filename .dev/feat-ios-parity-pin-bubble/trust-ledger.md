# Trust Ledger — P8 영역2 (핀 상세 말풍선 오버레이)

> phase-review 1회차. QA(qa-manager) + ZT(security-auditor) 통합 감사. Critical/CRITICAL 0건.

## 통합 감사 (review)

### 정합 확인 (이상 없음)
- [POLICY/정합] MUST-1 격리: `import MapboxMaps`가 MapboxMapView.swift 1파일만. MapContainerView/MapViewModel/PinBubbleView/PinDetailContent 모두 Mapbox·UIKit 타입 미참조.
- [정합] D-2 재탭 가드(AC-13), D-4 시트 일시숨김(AC-9), AC-7 삭제 시 nil, AC-14 isPointVisible 화면밖 nil+clamp 없음, BR-3 배경탭 isPhotoBusy 가드, BR-5 stub 미표시, BR-6 currentPin nil 닫힘, QE-1 3중차단(게이팅/distinct/BubbleOverlay 격리), QE-2 단일출처 파생, instagram https 가드 이관 후 퇴행 없음.
- [정합] MapEvent 시그니처 변경이 MapRenderer/MapboxMapView/MockMapRenderer/MapViewModelTests 4곳 일관. point(for:) #if/#else+Mock 모두 구현. ScreenPoint NaN은 screenPoint(for:) isFinite로 nil 처리(검증 완료, 위험 아님).

### 기각 (오판)
- [기각] `MapboxMapView.swift` `Set<AnyCancelable>()` — QA·ZT 모두 Combine `AnyCancellable` 오타로 의심했으나, **`AnyCancelable`은 MapboxMaps SDK 고유 타입**(observe API 반환). develop 원본(P4)부터 Mac 빌드되던 정상 코드. 수정 시 오히려 깨짐. → 혼동 방지용 주석 1줄만 보강.

### 조치 항목 (수정 권장)
- [Warning/QA·FR-9] PinBubbleView ScrollView maxHeight 제한 없음 → 긴 콘텐츠 핀(사진+메모+주소)에서 말풍선이 화면 높이 초과. `frame(maxHeight:)` + `.clipped()` 추가 권장(DoD-A 코드).
- [GAP/MEDIUM·ZT] visitMemo onDismiss → `pendingDetailPinId`를 `selectedPinId`로 승격 시 그 핀이 pins에 없으면(시트 중 삭제/폴링) 좀비 selectedPinId. `pins.contains{$0.id==pinId}` 검증 추가 권장.
- [Warning/QA] PinDetailContent onChange(currentPin==nil) 초기 nil 미발동 → BubbleOverlay 조건으로 사실상 보호되나 `.onAppear` 방어 보강 권장.
- [GAP/LOW·ZT] PRD AC-9 문구 "닫힘" vs 구현 "일시 숨김"(D-4) 불일치 → PRD 문구 정정(문서).
- [Info/QA] cameraMoved distinct(1pt 반올림) 단위테스트 누락 → 추가 권장(QE-1 b 보증).

### DoD-B(Mac) 이월 / 기수용
- [ASSUMPTION/HIGH·ZT] 좌표계 정렬(ZStack topLeading+ignoresSafeArea로 mapView.bounds 원점==SwiftUI 좌표) — 설계가 DoD-B 검증으로 이미 명시. 권고: 좌표 오차 허용치(±Npt)를 DoD-B 통과 기준에 명시.
- [GAP/기수용] 사진 업로드 중 다른 핀 탭 → 진행작업 폐기 = PRD/설계 CONSIDER #5에서 "웹 정합(미저장 폐기 정책)"으로 이미 결정. 정책 공백 아님. weak mapViewModel로 누수 없음.
- [LOW] onRequestClose 중복 nil(무해, 주석), lastMapSize zero 초기(cameraMoved로 복구, 엣지), MapboxMapRenderer(Coordinator 직접 처리 + point(for:) 프로토콜 적합성용, 기존 구조).
- [확인됨] PinDetailSheet Xcode 멤버십 — XcodeGen 폴더 기반(project.yml sources)이라 파일 삭제로 자동 반영(coder 확인).
