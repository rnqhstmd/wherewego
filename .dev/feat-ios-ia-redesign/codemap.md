## 코드 맵: iOS IA 재설계 — 내비 골격 (4탭 + 그룹 컨텍스트 + 지도 2레벨)

### 핵심 파일 (이번 단계=내비 골격 변경 대상)
- ios/WhereWeGo/App/MainTabView.swift → 현 3탭(지도·채팅·어디갈까)+상단(그룹칩·알림·내정보) → **4탭 셸(지도·DM·알림·내정보)** + 지도 2레벨 라우팅. (#104에서 reelFocus 배너/consumePending 추가됨 — develop 현재 버전 기준 재작성)
- ios/WhereWeGo/App/FloatingTabBar.swift → 탭 정의 3→4탭, 어디갈까 제거(지도 FAB로)
- ios/WhereWeGo/App/MainTab(enum, MainTabView 내 또는 별도) → .chat→.dm 등 탭 케이스 재정의
- ios/WhereWeGo/App/DeepLinkRouter.swift → Destination .chat→.dm, 탭 전환 케이스
- ios/WhereWeGo/App/OnboardingRouter.swift → 온보딩 종착(지도 탭, 마지막 그룹 진입)
- ios/WhereWeGo/App/TopBar.swift → 지도 상단(그룹 전환 버튼·뒤로가기·⋯ 진입점)
- ios/WhereWeGo/Features/Group/GroupContext.swift → 그룹 컨텍스트 전역 상태(목록·현재 그룹·마지막 그룹 기억)
- ios/WhereWeGo/Features/Group/GroupSwitcherSheet.swift → 그룹 목록/전환 UI(지도 2레벨 레벨0 = 그룹 목록 후보)
- (신규 후보) 그룹 목록 화면(지도 탭 레벨0) — GroupSwitcherSheet 재활용 또는 신규

### 참조 파일
- ios/WhereWeGo/Features/Map/MapView.swift, MapViewModel.swift → 지도(그룹별, focusReel/그룹 전환 switchTo)
- ios/WhereWeGo/Features/Group/GroupAPI.swift → listMyGroups(활성 그룹 목록)
- ios/WhereWeGo/App/AppDependencies.swift → VM 의존성 주입(탭 추가 시)
- **#102 브랜치(feat/ios-invite-code-entry, CLOSED 보존)** → 구 네비 셸(하단 2탭+상단) 구현 — 참조용(구 IA, 새 4탭과 다르나 그룹 컨텍스트/스위처 패턴 일부 재사용 가능)

### 후속 단계(같은 브랜치, 이번 비범위)
- 지도맵 최적화(C), DM 탭 UI(#105 GET /chat/bot/rooms 소비), 알림 상세/내정보 축소(D), ⋯ 그룹관리(D), IC-2 초대코드(56aa535 흡수)

### 비고
- 이번 단계는 **셸/라우팅 골격**만. DM·알림·내정보 탭은 진입 가능하되 내용은 기존/플레이스홀더.
- Mac 빌드(DoD-B) 필요. references/ 없음.
