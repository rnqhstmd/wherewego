## 코드 맵: iOS IA 재설계 — C (맵/필터 정리)

> 같은 브랜치 누적. 골격(A) 맵은 PR #106 + roadmap.md 참조. 이 맵은 C 변경 대상 중심.

### 핵심 파일 (C 변경 대상)
- ios/WhereWeGo/Features/Map/MapView.swift:366-384 → 좌하단 컨트롤 클러스터(어디가지 FAB 위 + [!]범례·[▽]필터 아래, HStack). **C-1: 필터/범례를 상단으로 이동**(어디가지 FAB와 좌하단 자리 충돌 해소). 어디가지 FAB는 좌하단 잔류.
- ios/WhereWeGo/Features/Map/MapView.swift:388-445 → groupTopOverlay(상단 그룹 오버레이: 뒤로·그룹명·⋯). **C-1: 필터/범례 상단 배치 시 이 행과 공존**(우측 또는 2단). reelFocusBanner(MainTabView top overlay)와도 상단 경합.
- ios/WhereWeGo/Features/Map/TagFilterBar.swift:71-75,165-169 → TagLegendButton/TagFilterButton 팝업이 버튼 **위로**(offset y:-(44+8), .bottomLeading). **C-1: 상단 이동 시 팝업을 아래로**(.topLeading, +offset) 뒤집기 필요.
- ios/WhereWeGo/Features/Map/MapViewModel.swift:284-322 → switchTo(groupId) + applyInitialCamera(현위치 zoom15 / 서울시청 zoom3 flyTo). **C-2: 줌아웃→내 위치 줌인 연출 + 핀만 교체**(이미 pins=[] 후 재로드 — 카메라 연출 보강).
- ios/WhereWeGo/App/MainTabView.swift:220-236 → mapTabContent **if/else**(레벨0 GroupListView / 레벨1 MapView). **C-2 쟁점: Mapbox 1회 로딩 위해 MapView 상시 마운트(ZStack 오버레이) 전환 검토**. 전환 시 enterGroup 경로도 같이 바꿔야 함(아래).
- ios/WhereWeGo/Features/Group/GroupContext.swift:82-99 → enterGroup(switchTo 미호출, MapView 마운트 .task 의존) / switchGroup(switchTo 호출) / backToList. **C-2(상시마운트 채택 시): enterGroup→onGroupChanged(switchTo) 전환** + MapView.task 이중로드 가드 조정.

### 참조 파일
- ios/WhereWeGo/Features/Map/MapView.swift:151-160 → MapView.task(진입 시 load(groupId:) + 폴링/방문감지 시작). 상시마운트 전환 시 로드 트리거 재설계 지점.
- ios/WhereWeGo/Core/Map/MapboxMapView.swift:41-85 → makeUIView 가 **마운트마다 새 MBMapView 생성**(=재로딩 비용 원천). 상시마운트면 1회만 생성.
- ios/WhereWeGo/Core/Map/MapContainerView.swift:18-62 → MapboxMapView↔PlaceholderMapView 분기(MapConfig.isMapboxConfigured). MapView body 배경.
- ios/WhereWeGo/Features/Map/MapViewModel.swift:299-322,406-422 → applyInitialCamera / flyTo(B2 cameraCommand 계약). 줌인 연출 구현 지점.
- ios/WhereWeGo/App/FloatingTabBar.swift (Metrics.contentFootprint/bottomGap) → 하단 회피 수치(C-1 상단 이동과 무관, 좌하단 어디가지 FAB 잔류분 참조).

### 설정
- ios/WhereWeGo/Core/Map/MapConfig.swift → styleURL/accessToken/isMapboxConfigured(토큰 미설정=Placeholder).

### 비고
- iOS = **Windows 빌드 불가** → 커밋만 Windows, 빌드/시뮬/단위테스트는 Mac(DoD-B). 시그니처/enum/로직 정합은 직접 검토로 보장.
- references/ 없음. 도메인 컨텍스트(context/)는 백엔드 도메인 — C(iOS UI)와 직접 관련 적음.
