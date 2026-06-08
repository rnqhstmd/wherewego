# 설계: iOS IA 재설계 — C (맵/필터 정리)

> IA 재설계 묶음 브랜치 feat/ios-ia-redesign, 골격 A(PR #106) 위 누적. 스코프=B안(연출만·구조 변경 없음).

## 설계 규모: 소형 (UI 재배치 + switchTo 카메라 시퀀스, 구조 변경 없음 → design-critic 생략)

## 현황 (정독)
- 필터/범례: `MapView.loadedOverlay` **좌하단 클러스터**(`VStack{ Spacer; rouletteFAB; HStack{ TagLegendButton; TagFilterButton } }`). `TagFilterBar.swift` 팝업은 `.overlay(alignment:.bottomLeading){ popup.offset(y:-(44+8)) }` — **위로** 펼침.
- 상단: `MapView.groupTopOverlay` = `VStack{ groupTopOverlay; Spacer }`, `[<뒤로][그룹명⌄]…[⋯]` 1행. 릴스 배너는 MainTabView top overlay(별도).
- 그룹 전환: `GroupContext.switchGroup → onGroupChanged → MapViewModel.switchTo`. 현 switchTo: `loadState=.loading; pins=[]; 재조회; applyInitialCamera()` → 스피너 + EmptyMapCard 깜빡임 위험.
- B2 cameraCommand 계약: MapContainerView 1회 소비 후 nil. 연속 명령은 시간 분리 필요(switchTo 네트워크 await가 분리 제공).

## 확정 설계 결정

### D-1. 필터/범례 상단 이동 (FR-C1/C2, AC-C1/C2)
- 배치: 상단 그룹 오버레이 **2번째 행, 우측 정렬**. 좌하단은 어디가지 FAB 단독.
- MapView 상단 오버레이:
  ```
  if !isAddingPin {
    VStack(spacing: 10) {
      groupTopOverlay
      if case .loaded = loadState { mapFilterRow }   // Spacer, [!]범례, [▽]필터 (우측)
      Spacer()
    }
  }
  ```
  - `mapFilterRow` 신규 computed: `HStack(spacing:8){ Spacer(minLength:0); TagLegendButton(isOpen:legendPopupBinding); TagFilterButton(activeFilters:$vm.activeFilters, isOpen:filterPopupBinding) }.padding(.horizontal,16)`.
  - **`.loaded`에서만 노출**(기존 동작 보존). loading/error 땐 그룹 행만.
- 좌하단 클러스터: 필터/범례 제거 → `rouletteFAB`만:
  ```
  VStack{ Spacer; HStack{ rouletteFAB; Spacer }.padding(.bottom, FloatingTabBar.Metrics.contentFootprint + bottomGap) }.padding(.horizontal,16).zIndex(1)
  ```
- 상호배타(`activeCornerPopup`)·바깥 탭 닫힘 catcher(loadedOverlay `Color.clear`) 유지. catcher는 상단 오버레이보다 아래 레이어, 버튼/팝업 위 레이어 → 버튼 탭 정상·빈 곳 탭 닫힘(Spacer hit-test 비대상 통과).

### D-2. 팝업 아래 방향 전환 (FR-C1, AC-C2)
- `TagFilterBar.swift` 두 버튼: `.overlay(alignment:.bottomLeading){ popup.offset(y:-(44+8)) }` → **`.overlay(alignment:.topTrailing){ popup.offset(y:44+8) }`**.
- trailing 앵커: 버튼 우측 정렬 → 팝업(248/220)이 좌측으로 펼쳐져 화면 내 유지(leading이면 우측 오버플로).

### D-3. switchTo 줌아웃→줌인 연출 (FR-C3/C5, AC-C4)
- 핀 **원자 교체**(조기 `pins=[]` 제거) + `.loading` 생략(전면 스피너 미표시) → 깜빡임 제거.
- 신규 상수 `static let switchOverviewZoom: Double = 10`(DoD-B 미세조정).
- 시퀀스:
  ```swift
  func switchTo(groupId newGroupId: Int) async {
    guard newGroupId != groupId else { return }
    groupId = newGroupId
    selectedPinId = nil
    zoomOutForSwitch()
    do {
      let fetched = try await pinAPI.list(groupId: newGroupId)
      pins = fetched
      lastFetchedAt = now()
      loadState = .loaded
      await zoomInForSwitch()
    } catch {
      pins = []
      loadState = .error("핀을 불러오지 못했어요. 다시 시도해 주세요.")
    }
  }
  private func zoomOutForSwitch() {
    guard let c = mapCenter else { return }
    cameraCommand = CameraTarget(latitude: c.latitude, longitude: c.longitude, zoom: Self.switchOverviewZoom)
    mapZoom = Self.switchOverviewZoom
  }
  private func zoomInForSwitch() async {
    let s = locationService.authorizationStatus
    let granted = s == .authorizedWhenInUse || s == .authorizedAlways
    if granted { await applyInitialCamera() }
    else if !markers.isEmpty { fitBoundsCommand = markers }   // FR-C5
    else { await applyInitialCamera() }
  }
  ```
- `load()`/`applyInitialCamera()` **불변**(초기 로드·목록→선택 회귀 방지). switchTo만 변경.
- B2: zoom-out(cameraCommand) → await(fetch) → zoom-in(cameraCommand/fitBoundsCommand). await 경계로 zoom-out 선소비.

## 변경 범위 (신규 0 · 수정 3)
- `Features/Map/TagFilterBar.swift` — 팝업 alignment/offset 뒤집기 (D-2)
- `Features/Map/MapView.swift` — 상단 `mapFilterRow`(.loaded 게이트) 추가 + 좌하단 클러스터 정리(rouletteFAB만) (D-1)
- `Features/Map/MapViewModel.swift` — `switchOverviewZoom` + `switchTo` 재작성 + `zoomOutForSwitch`/`zoomInForSwitch` (D-3)

## 구현 순서
1. `TagFilterBar.swift` 팝업 방향 전환
2. `MapView.swift` 상단 mapFilterRow + 좌하단 정리
3. `MapViewModel.swift` switchTo 연출
4. 테스트 + Mac DoD-B

## 테스트 (단위 — Mac 실행, 시각=DoD-B)
- `MapViewModelTests` switchTo: pins 교체·selectedPinId nil·최종 .loaded / 위치 거부+핀 있음→fitBoundsCommand 세팅 / 위치 거부+핀 0→applyInitialCamera 폴백 / 같은 groupId no-op / fetch 실패→.error+pins []
- 필터 토글/주황 점/빈 Set 회귀(AC-C3) — 무변경, 기존 테스트 유지.

## 리스크 / 호환
- 2단 카메라 병합(즉시 응답) → DoD-B 확인, 필요 시 zoom-out 후 Task.sleep(120ms).
- 필터 `.loaded` 게이트 = 기존 동작 동일.
- B2 계약 신규 없음(기존 1회 소비 경로 재사용).
- iOS Windows 빌드 불가 → 시그니처/로직 직접 검토, 수치/시각 Mac DoD-B.
