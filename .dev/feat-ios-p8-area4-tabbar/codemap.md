## 코드 맵: P8 영역4 — 하단 플로팅 5탭바 정합성 (Liquid Glass·safe area·콘텐츠 겹침)

### 핵심 파일
- ios/WhereWeGo/App/FloatingTabBar.swift:99-122 → 5탭 플로팅 바 + `FloatingBarBackground`(ViewModifier). **버그**: iOS26 분기와 폴백 코드가 동일(둘 다 솔리드 `Capsule().fill(WGColor.panel)`) → Liquid Glass `TODO(DoD-B)` 미구현. 바 `height 64` + `.padding(.bottom,12)` (safe area inset 없음).
- ios/WhereWeGo/App/MainTabView.swift:72-110 → `ZStack(.bottom)` TabView + FloatingTabBar 오버레이. 자식 콘텐츠(MapView/BotChatView/Notification/MyInfo)에 바 footprint만큼 하단 여백 미전달(겹침 근원). `unreadCount>0` → hasUnread.
- ios/WhereWeGo/Features/Map/MapView.swift:246-264 → 룰렛 버튼 우상단(`.padding(.top,60)`), **내 위치 버튼 우하단 `.padding(.bottom,28)`(바와 겹침)**. 외곽 `.padding(.horizontal,16)`. 맵 자체는 full-bleed 유지 필요.
- ios/WhereWeGo/Features/Chat/Bot/BotChatView.swift:93-136 → 채팅 입력바 VStack(`.padding(.bottom,8)`, `.background(WGColor.bg)`). 바 뒤로 가림.
- ios/WhereWeGo/Core/DesignSystem/Theme.swift:31-54 → `WGColor` 토큰(`panel`=#FFFFFF 솔리드, `shadowMd`=ink@0.13, `shadow`=ink@0.08, `hairline`). **glass/material 토큰 없음** → `.ultraThinMaterial`/iOS26 `.glassEffect` 도입 필요. 웹 `tokens.ts` 1:1 이식(drift 가드 대상).

### 신규 파일(설계)
- ios/WhereWeGo/App/FloatingBarLayout.swift → footprint 상수 SSOT(enum: barHeight 64, bottomGap 12, contentSpacing 12, contentInset computed). AC-1.

### 참조 파일
- ios/WhereWeGo/Features/Notification/NotificationInboxView.swift / ios/WhereWeGo/Features/MyInfo/MyInfoView.swift → 스크롤 콘텐츠(하단 footprint inset 대상, FR-6/AC-9).
- ios/WhereWeGo/Features/Chat/ChatScrollContainer.swift → 봇 채팅 스크롤(scrollDismissesKeyboard .interactively — 입력바 패딩 변경과 키보드 회피 독립, QE-2).
- ios/WhereWeGo/Features/Photo/SquareCropView.swift:28 → 코드베이스 유일 GeometryReader 사용처(바 safe area read 패턴 참조).
- .dev/feat-ios-nav-redesign/frontend-parity-findings.md:34-38 → P8 영역4 분석 원본(웹엔 5탭 없음, 문제 3가지, 수정안).
- .dev/feat-ios-native-swiftui/roadmap.md:69-79 → P8 정의·선행 결정(**5탭 유지 확정**).
- ios/WhereWeGo/Features/Notification/NotificationInboxViewModel.swift → `unreadCount`(바 미읽음 배지 소스).
- frontend/src/app/map/_components/ActionBar.tsx → 웹 지도 액션바(5탭 없음 — 정합 비교 기준, 회귀는 안 함).

### 설정
- ios/project.yml → XcodeGen 프로젝트. deploymentTarget **iOS 17.0**, Swift 6.0. 빌드=Mac/Xcode 전용(**Windows 빌드 불가 → DoD-B Mac 검증** 잔존). 폴백(17~25)이 1차 런타임, iOS26은 Liquid Glass 경로.
