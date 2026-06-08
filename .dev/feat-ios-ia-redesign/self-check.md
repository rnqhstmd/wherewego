# 자기점검 + 리뷰 결과 (오케스트레이터 직접 — oh-my-gx agent 미반환 대체)

## CERTAIN (자동 수정 대상)
- 0건.

## 검증 내역 (iOS는 Windows 컴파일 불가 → 시그니처/enum/로직 정합 직접 검증)
### 컴파일 정합
- **`listMyGroups` 프로토콜 체인 완전 정합**: `GroupAPIProtocol`(AuthServiceProtocols.swift:17) 선언 + `GroupAPI`(:69) 구현 + **테스트 mock 12개 전부**(AddPlace/BotChat/InlineAddPlace/MainTab/MapCache/MapViewModel/MyInfo/PinDetail/Roulette/Visit/RouteGuard) `func listMyGroups() async throws -> [GroupSummary] { [] }` 추가됨.
- **`MainTab.discover` 제거 정합**: 코드에 `.discover` 실제 참조 0(grep). `RouletteSheet:8`은 주석(outdated, 무영향). `MainTabTests`가 "discover 식별자 부재" 검증.
- **`GroupSummary: Decodable, Identifiable`** → `GroupListView` ForEach 정합.
- `FloatingTabBar` `onReselectMap` 콜백 시그니처 ↔ `MainTabView`(:144 `onReselectMap: { groupContext.backToList() }`) 정합.

### 기능 정합 (설계/PRD 대비)
- **GroupContext**(신규): bootstrap(listMyGroups→groups, lastGroupId 유효 시 복원 else nil), enterGroup(currentGroupId+lastGroupId persist+onGroupChanged), switchGroup(동일 no-op), backToList(currentGroupId nil·lastGroupId 유지). onGroupChanged 약결합(MapViewModel 직접 참조 회피). (FR-2/FR-5, AC-3/AC-4)
- **MainTabView**: 5→4탭, 지도 2레벨(currentGroupId nil→GroupListView / 값→MapView), .task bootstrap, onReselectMap→backToList, reelFocus 배너 currentGroupId 가드. (FR-1/FR-2/FR-3, AC-1/AC-2)
- **GroupListView**(신규): 그룹 카드 목록(이름·인원) + 빈 상태(생성/합류 유도). enterGroup. (FR-5, AC-6)
- **FloatingTabBar**: 4탭(어디갈까 제거), 지도 재탭 onReselectMap. (FR-1/FR-3)
- **MapView**: 상단 오버레이(그룹명·전환·뒤로 backToList·⋯) + 좌하단 어디가지 FAB(룰렛 시트). (FR-4/FR-6 일부)
- **GroupAPI.listMyGroups** → GET /groups(GM-1 백엔드) 소비. (FR-5/AC-6)

### 테스트
- **GroupContextTests**(신규): bootstrap(복원/무효 nil/실패 폴백), enter/switch/backToList 전이, lastGroupId persist, onGroupChanged 트리거. (Mac 실행 대기)

## QUESTION / 비차단
- `RouletteSheet:8` 주석이 구 ".discover 전이" 언급 → outdated(무영향). 후속 정리 가능.
- iOS **Mac 빌드/시뮬레이터/단위테스트(GroupContextTests 포함) 실행 = DoD-B 잔존**(Windows 불가).

## 비범위 (후속, 같은 브랜치)
- 필터/범례 상단·맵 로딩 최적화(C), DM 그룹별 목록 #105 소비, 알림 상세·내정보 축소(D), ⋯ 그룹관리 내용(D), IC-2 초대코드.
