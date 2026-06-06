## 코드 맵: 멀티그룹 내비 셸 재구성 (하단 2탭 + 상단바 분리)

### 핵심 파일
- ios/WhereWeGo/App/MainTabView.swift → 4탭 TabView + overlay FloatingTabBar 소유. **재구성 대상**: 하단 탭=지도/채팅(그룹 종속) 2개로 축소, 상단바(좌:그룹칩 / 우:알림·내정보) 신설.
- ios/WhereWeGo/App/FloatingTabBar.swift → 둥근 글래스 플로팅 탭바(MainTab 4케이스). 2탭(map/chat)으로 축소, MainTab enum 정리.
- ios/WhereWeGo/Features/Group/GroupAPI.swift:67~ → myActiveGroup()/createGroup/leaveGroup 보유. **listMyGroups() 추가 대상**(GET /api/v1/groups).
- ios/WhereWeGo/Features/Map/MapViewModel.swift:219,257,266 → load()에서 myActiveGroup() 내부 resolve → groupId 보유 → pinAPI.list(groupId:). **그룹 전환 시 외부 groupId 주입·재로드 경로 필요**.
- ios/WhereWeGo/Features/MyInfo/MyInfoViewModel.swift → 내정보(전역). 상단 👤 진입(시트/푸시)으로 이관.
- ios/WhereWeGo/Features/Notification/NotificationInboxView(.swift) → 알림(전역). 상단 🔔 진입으로 이관. (unreadCount 배지 유지)

### 참조 파일
- ios/WhereWeGo/App/OnboardingRouter.swift:122 → .groups 라우트 = MainTabView 종착.
- ios/WhereWeGo/Features/Map/MapView.swift → 지도 화면 chrome(우상단 🎲 룰렛, 우하단 +/내위치). 상단바와 레이아웃 충돌 점검.
- ios/WhereWeGo/Features/Chat/Bot/BotChatView(Model) → 채팅(그룹 종속). 그룹 전환 시 방 전환 점검.
- ios/WhereWeGo/Core/DesignSystem/Theme.swift → liquidGlass*/glassCard/WGColor/WGFont 토큰(상단바·그룹칩 스타일 재사용).
- backend …/interfaces/api/group/GroupV1Controller.java:93 → GET /api/v1/groups = listMyGroups(List<GroupSummaryResponse>) (백엔드 준비 완료).

### 설정
- ios/project.yml → XcodeGen(소스 폴더 글로빙 — 새 .swift 파일 추가 시 xcodegen generate 필요).
- 백엔드: group_members uq_group_members_active_user 제약 DROP됨 → 멀티 그룹 동시 소속 가능.
