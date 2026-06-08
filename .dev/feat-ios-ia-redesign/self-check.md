# 자기점검 — C (맵/필터 정리)

> qa-manager(읽기 전용) 산출물 미반환 → 오케스트레이터 직접 검토. iOS=Windows 빌드 불가라 시그니처/로직/AC 정합 직접 검증, 빌드·시뮬·시각은 Mac DoD-B.
> (직전 골격 A 자기점검은 PR #106·roadmap.md 참조 — 이 파일은 C로 갱신됨.)

## Critical (자동 수정 대상): 0건
없음. AC-C1~C5 대비 정합 확인.

## AC 대비 검토 결과
- AC-C1 ✓ 상단 mapFilterRow(우측 [!]범례·[▽]필터), 좌하단 클러스터=rouletteFAB 단독.
- AC-C2 ✓ 팝업 .topTrailing + offset(y:44+8)(아래·좌측 펼침). 상단 VStack(spacing:10): 그룹 행 / 필터 행 분리(겹침 없음). 릴스 배너 겹침은 기존 DoD-B 이슈(아래 W2).
- AC-C3 ✓ legend/filterPopupBinding(activeCornerPopup) 상호배타·바깥탭 닫힘 catcher 무변경. catcher(loadedOverlay)는 상단 버튼/팝업보다 아래 레이어, Spacer hit-test 비대상 → 버튼 탭 정상·빈 곳 탭 닫힘 유지. 필터 토글/주황점 로직 무변경.
- AC-C4 ✓ switchTo: `.loading` 미설정(전면 스피너 미표시) + 핀 원자 교체(구 핀 fetch까지 유지 → EmptyMapCard 깜빡임 방지) + zoomOut→zoomIn.
- AC-C5 ✓ load()/applyInitialCamera() 무변경(초기 로드·목록→선택 경로 회귀 없음). selectedPinId=nil(전환 시)로 구 그룹 좀비 말풍선 제거.

## Warning / Info (phase-review·DoD-B 이월)
- [Warning] MapViewModel.swift switchTo — 2단 카메라(zoomOut cameraCommand → await fetch → zoomIn) 병합 가능성: 네트워크 즉시 응답 시 zoomOut이 MapContainerView 소비 전 zoomIn으로 덮일 수 있음. 설계 명시(필요 시 zoomOut 후 Task.sleep 120ms). **Mac DoD-B 확인**.
- [Warning] 상단 레이아웃 — reelFocusBanner(MainTabView top overlay) vs groupTopOverlay/mapFilterRow 시각 겹침: 기존 골격 단계부터의 DoD-B 수치 보정 이슈. C는 필터 행을 그룹 행 아래 배치해 악화시키지 않음. **Mac 시각 확인**.
- [Info] switchOverviewZoom=10 — 줌아웃 레벨 수치 미세조정 DoD-B.

## QUESTION (사용자 확인): 0건
스코프 핵심 질문(맵 1회 로딩 A/B)은 requirements에서 B안으로 확정됨.

## 변경 검증
- 수정 3 + 테스트 1: TagFilterBar.swift(팝업 방향) / MapView.swift(상단 필터행+좌하단 정리) / MapViewModel.swift(switchTo 연출) / MapViewModelTests.swift(switchTo 5 테스트 + StubPinAPI 그룹별·카운트 확장, 하위호환).
- 직접 정독으로 시그니처(cameraCommand:CameraTarget / fitBoundsCommand:[MapMarker]? / markers:[MapMarker])·enum(LoadState)·SwiftUI 패턴 정합 확인.
