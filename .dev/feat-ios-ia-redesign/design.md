# 설계: iOS IA 재설계 — 내비 골격 (GM-2 iOS 그룹 다중화 겸함)

## 설계 규모: 대형 (그룹 다중 상태 신규 + 5→4탭 + 지도 2레벨)

## 현황 (develop 정정)
- `MainTabView` = **5탭 TabView**(지도·어디갈까·채팅·알림·내정보) + `FloatingTabBar` 5칸. #104 reelFocus 배너 포함.
- **`GroupContext` 없음** — 단일 `ActiveGroup`(`GroupAPI.myActiveGroup()`)만. **`listMyGroups()` 없음**.
- 백엔드: `GET /groups`(GM-1, 그룹 목록), `GET /chat/bot/rooms`(GM-2 B) 존재.
- `OnboardingRouter` 종착 = `MainTabView`. `InviteCodeView`/`GroupStartView`/`GroupCreateView` 존재.

## 확정 설계 결정
1. **그룹 목록(레벨0) = 신규 `GroupListView`** (GroupSwitcherSheet는 develop에 없음).
2. **그룹 컨텍스트 = 신규 `GroupContext`**(ObservableObject): `groups[]` + `currentGroupId` + `lastGroupId`(UserDefaults 기억).
3. **탭 더블탭** = `FloatingTabBar` 지도 탭이 이미 선택된 상태에서 재탭 → 콜백 → 그룹 목록(레벨0).
4. **DM/알림/내정보 = 기존 화면 유지** (이번엔 채팅→DM 라벨/탭만, DM 내용은 후속 단계).
5. **어디가지(룰렛)**: 탭 제거 + **지도 좌하단 FAB로 진입(룰렛 시트)** 까지 골격에 포함(접근 경로 끊김 방지). **필터/범례 상단 이동은 C단계**(지도 화면 정리)로 분리.

## 변경 범위 (신규 3 · 수정 6 · 테스트 1)
**신규**
- `Features/Group/GroupContext.swift` — 전역 그룹 상태
- `Features/Group/GroupListView.swift` — 레벨0 그룹 목록
- (`GroupAPI` 내) `GroupSummary` DTO + `listMyGroups()`

**수정**
- `App/MainTabView.swift` — 5→4탭 + 지도 2레벨 + GroupContext 주입
- `App/FloatingTabBar.swift` — 4탭(어디갈까 제거, 채팅→DM), 지도 더블탭 콜백
- `App/DeepLinkRouter.swift` — `.chat` 유지(DM 탭으로 라우팅) 또는 `.dm` 리네이밍
- `Features/Group/GroupAPI.swift` — `listMyGroups()` + `GroupSummary`
- `Features/Map/MapView.swift` — 상단 그룹명·전환·뒤로·⋯ 오버레이 + 좌하단 어디가지 FAB
- `App/OnboardingRouter.swift` — 종착 시 GroupContext bootstrap 연계(또는 MainTabView 내부 bootstrap)

## 1. GroupContext (신규, @MainActor ObservableObject)
- `@Published private(set) groups: [GroupSummary]`
- `@Published var currentGroupId: Int?` (nil = 그룹 목록 레벨0)
- `lastGroupId` (UserDefaults persist, 마지막 본 그룹)
- `bootstrap() async`: `listMyGroups()` → `groups`. `currentGroupId = lastGroupId`(목록에 존재할 때만) else nil
- `enterGroup(_ id)`: currentGroupId = id + lastGroupId 저장
- `switchGroup(_ id)`: enterGroup + 지도 재로드 트리거(MapViewModel.switchTo)
- `backToList()`: currentGroupId = nil (lastGroupId 유지)

## 2. GroupAPI.listMyGroups + GroupSummary
- `struct GroupSummary: Decodable { let groupId: Int; let name: String; let memberCount: Int }`
- `func listMyGroups() async throws -> [GroupSummary]` → `GET /groups`

## 3. 지도 탭 2레벨 (MainTabView .map 콘텐츠)
- `groupContext.currentGroupId == nil` → `GroupListView`(레벨0, 그룹 선택 시 enterGroup)
- `!= nil` → `MapView`(그 그룹) (레벨1)
- 탭 복귀: currentGroupId(=lastGroupId 복원) 유지 → 지도 직행 (AC-3)

## 4. FloatingTabBar 4탭
- 지도 · DM(기존 채팅) · 알림 · 내정보. 어디갈까 케이스 제거.
- 지도 탭 더블탭(이미 .map 선택 중 재탭) → `onReselectMap` 콜백 → `groupContext.backToList()` (AC-4)

## 5. 지도 화면(MapView 오버레이)
- 상단: 그룹명 + 그룹 전환(목록 시트/드롭다운) + 뒤로(→목록, backToList) + ⋯(그룹관리 진입점 — 내용 D)
- 좌하단: 어디가지 FAB → RouletteView 시트(기존 RouletteViewModel 재사용). (우하단 ＋ speed-dial은 기존 유지)

## 6. 비범위 (후속, 같은 브랜치)
- 필터/범례 상단 이동(C) · 맵 로딩 최적화 실구현(C) · DM 그룹별 목록 UI(#105 소비) · 알림 상세/내정보 축소(D) · ⋯ 그룹관리 내용(D) · IC-2 초대코드

## 구현 순서
1. `GroupSummary` + `GroupAPI.listMyGroups` (의존: 없음)
2. `GroupContext` (의존: 1)
3. `GroupListView` (의존: 2)
4. `FloatingTabBar` 4탭 + 더블탭 콜백 (의존: 없음)
5. `MainTabView` 4탭 + 지도 2레벨 + GroupContext 주입 + bootstrap (의존: 2,3,4)
6. `MapView` 상단 오버레이(그룹 전환/뒤로/⋯) + 어디가지 FAB (의존: 2)
7. `DeepLinkRouter` 채팅→DM 라우팅 정합 (의존: 없음)
8. 테스트: GroupContext 상태 전이(bootstrap/enter/switch/backToList), 더블탭

## 테스트
- GroupContext: bootstrap(lastGroupId 복원/무효 시 nil), enter/switch/backToList 전이, lastGroupId persist
- 지도 2레벨 분기(currentGroupId nil↔값)
- (iOS 빌드/시뮬은 Mac DoD-B)

## 리스크 / 호환
- 그룹 0개/1개 처리: 0개=그룹 목록 빈 상태(생성/합류 유도), 1개=목록 거쳐도 바로 그 그룹 진입 가능(lastGroupId).
- `OnboardingRouter` 종착이 단일 ActiveGroup 가정 → GroupContext bootstrap로 전환. 기존 온보딩 흐름 무손상 확인.
- DM 탭은 이번엔 기존 BotChatView(단일 봇방). #105 그룹별 DM 목록 소비는 다음 DM 단계 — 그때 BotChatViewModel/ChatAPI를 groupId 기반으로 전환(현 deprecated /chat/bot/messages 사용 중).
- Mac 빌드(DoD-B) 필요. Windows 검증 불가.
