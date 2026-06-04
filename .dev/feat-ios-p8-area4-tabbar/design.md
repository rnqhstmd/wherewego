# 최종 설계서: P8 영역4 — 하단 플로팅 5탭바 시각 완성도 및 콘텐츠 가림 해소

> 브랜치 feat/ios-p8-area4-tabbar (base develop) · iOS SwiftUI · 빌드/시각 최종검증 Mac(DoD-B)
> design-critic 1차 검토(MUST-ADDRESS 3건) 반영 완료본.

## 설계 규모
**소형~중형** — `.safeAreaInset` 부착으로 콘텐츠 회피가 자동화되어 수동 inset 코드가 제거됨. 핵심 변경은 `MainTabView` 바 부착 1건 + `FloatingTabBar` 매직넘버/glass 분기 정리. MapView는 1줄 미세 조정. **신규 파일 없음.**

## 접근 개요 — footprint 메커니즘: `.safeAreaInset` 부착 (확정)

1차안 "공유 상수 + 각 화면 수동 inset"은 critic 지적대로 **safe area 이중 가산**(홈 인디케이터 기기서 간격 2배) + **맵/스크롤 좌표계 불일치**가 있어 폐기. SwiftUI 관용 경로로 전환:

- **바 부착**: `MainTabView`에서 `FloatingTabBar`를 ZStack 오버레이가 아니라 `TabView`의 `.safeAreaInset(edge: .bottom) { FloatingTabBar(...) }`로 부착. SwiftUI가 바 높이만큼 각 탭 safe area를 **자동 예약** → (a) 이중 가산 소멸, (b) 채팅 입력바·알림/내정보 스크롤·맵 오버레이가 **자동 회피**.
- **바 자체 safe area(AC-4)**: safeAreaInset 부착 뷰는 SwiftUI가 부모 safe area 위에 배치 → 바가 자동으로 홈 인디케이터 위. inset 0 기기는 바 내부 `.padding(.bottom, bottomGap=12)`가 최소 여백 보장.
- **맵 full-bleed(BR-3/AC-8)**: `MapView`의 `MapContainerView().ignoresSafeArea()`(line 28)가 축소된 safe area를 무시하고 배경을 화면 끝까지 렌더 → 배경 불변. `loadedOverlay`(내위치/룰렛)는 ignoresSafeArea 미적용이라 축소된 safe area를 따라 **자동으로 바 위로 올라감**. 좌표계 분리(line 20~28 확인).
- **키보드(Q3/QE-2)**: 부착한 바에 `.ignoresSafeArea(.keyboard, edges: .bottom)` 적용 → 키보드 표시 시 **바 고정**, 채팅 입력바만 키보드 위 자연 회피. 수동 패딩 불필요, "키보드 위 빈 공간" 버그 없음.

**FR-1 충족**: 자동 회피로 외부 contentInset 상수 불필요. 남는 매직넘버는 `FloatingTabBar` 내부 `barHeight=64`/`bottomGap=12`뿐 → **`FloatingTabBar.Metrics` 중첩 enum**으로 흡수(신규 파일 없이 SSOT). 별도 `FloatingBarLayout.swift` **생성 안 함**(critic SIMPLIFY 반영).

## 변경 범위
**신규 파일: 없음**

**수정 (3)**
- `ios/WhereWeGo/App/FloatingTabBar.swift` — `barHeight`/`bottomGap` 리터럴 → 중첩 `enum Metrics`(FR-1); `FloatingBarBackground` glass/solid 분기 실제 분리(FR-5/AC-5/BR-5).
- `ios/WhereWeGo/App/MainTabView.swift` — `FloatingTabBar` ZStack 오버레이 → `TabView .safeAreaInset(edge:.bottom)` 부착 + `.ignoresSafeArea(.keyboard, edges:.bottom)`. ZStack→TabView 단일 루트.
- `ios/WhereWeGo/Features/Map/MapView.swift` — 내위치 버튼 `.padding(.bottom, 28)`→`12`(중복 여백 제거, 수치 DoD-B). 배경·룰렛·토스트 불변.

**변경 제외 (자동 회피로 충분)**
- `BotChatView.swift` / `NotificationInboxView.swift` / `MyInfoView.swift` / `ChatScrollContainer.swift` — safeAreaInset 자동 예약으로 입력바·스크롤 자동 회피 → **변경 없음.**

## 적용 컨벤션
- 타입 PascalCase, 멤버 camelCase. 상수 묶음 `enum + static let`(WGColor/WGFont 패턴). 바 상수도 `enum Metrics { static let ... }`.
- 색/그림자: `WGColor.panel`·`WGColor.shadowMd`. 매직 색상 금지.
- 배경은 `FloatingBarBackground: ViewModifier` 패턴 유지·확장.
- 주석 한국어 + `설계 §`/`FR-`/`AC-` 태그.

## 상세 설계

### 1. FloatingTabBar.swift — 매직넘버 흡수(FR-1) + glass 분기 분리(FR-5)
```swift
struct FloatingTabBar: View {
    /// 바 레이아웃 상수 SSOT(설계 §1, FR-1/AC-1·AC-2). internal — MapView 등 외부에서 bottomGap 참조(매직넘버 분산 금지).
    enum Metrics {
        static let barHeight: CGFloat = 64   // 필 바 높이
        static let bottomGap: CGFloat = 12   // 바와 safe area 사이 최소 여백(AC-4) / 공통 간격 단위
    }
    // body: .frame(height: Metrics.barHeight) / .padding(.bottom, Metrics.bottomGap)
}

private struct FloatingBarBackground: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) { glassBackground(content) }   // iOS26 전용 경로
        else { solidBackground(content) }                         // 17~25 폴백(BR-5: 반투명화 금지)
    }
    @available(iOS 26.0, *)
    private func glassBackground(_ content: Content) -> some View {
        // TODO(DoD-B): Xcode 26 SDK 에서 .glassEffect 계열 정확 파라미터로 교체.
        //  iOS 26.5 시뮬 '불투명 흰 캡슐'은 이 분기가 폴백과 동일했던 탓 → 반투명 분리.
        content.background(Capsule().fill(.ultraThinMaterial)
            .shadow(color: WGColor.shadowMd, radius: 12, x: 0, y: 4))   // AC-5: 폴백과 다름
    }
    private func solidBackground(_ content: Content) -> some View {
        content.background(Capsule().fill(WGColor.panel)               // P7 의도 보존
            .shadow(color: WGColor.shadowMd, radius: 12, x: 0, y: 4))
    }
}
```
- `.ultraThinMaterial`은 iOS17+ 가용하나 `@available(iOS26)` 메서드로 격리 → iOS26에서만 실행, 폴백 솔리드 유지(사용자 결정 #3).

### 2. MainTabView.swift — 바를 safeAreaInset으로 부착 (핵심)
```swift
var body: some View {
    TabView(selection: $selection) {
        MapView(viewModel: mapViewModel).tag(MainTab.map)            // 배경 ignoresSafeArea → full-bleed
        NavigationStack { BotChatView(viewModel: botViewModel) }.tag(MainTab.chat)
        NavigationStack { NotificationInboxView(viewModel: notificationInboxViewModel) }.tag(MainTab.notification)
        NavigationStack { MyInfoView(authAPI: dependencies.authAPI, viewModel: myInfoViewModel) }.tag(MainTab.myInfo)
    }
    .toolbar(.hidden, for: .tabBar)
    .tint(WGColor.cta)
    .safeAreaInset(edge: .bottom) {                                  // 바 부착: 콘텐츠 자동 회피(AC-3/9)+바 safe area 배치(AC-4)
        FloatingTabBar(selection: $selection,
                       hasUnread: notificationInboxViewModel.unreadCount > 0,
                       onPlusTap: { showAddPlace = true })
    }
    .ignoresSafeArea(.keyboard, edges: .bottom)                     // 키보드 시 바 고정(Q3/QE-2)
    .task { ... }.onChange(...) { ... }.sheet(...) { ... }          // 딥링크·배지·시트 전부 불변(QE-1)
}
```
- `.task`/`.onChange`/`.sheet`은 ZStack→TabView로 소유자만 바뀜, 로직 동일. `consumePending()`·selection 바인딩·unreadCount 관찰 불변.

### 3. MapView.swift — 내위치 버튼 중복 여백 제거 (FR-2/AC-2, 룰렛 불변 BR-4)
```swift
VStack { Spacer(); HStack { Spacer(); myLocationButton }
    .padding(.bottom, 12) }   // was 28 — 바가 safe area 차지로 자동 회피, 28은 중복(DoD-B 수치 확정)
```
- 배경(line 22~28)·룰렛 `.padding(.top,60)`·외곽 `.padding(.horizontal,16)`·토스트 불변. 내위치는 loadedOverlay(ignoresSafeArea 미적용) → 자동 회피 성립.

## 구현 순서
1. **[Must] FloatingTabBar.swift** (의존 없음) — Metrics 흡수 + glass/solid 분리. 자기완결.
2. **[Must] MainTabView.swift** (의존 1) — safeAreaInset 부착 + 키보드 ignore.
3. **[Must] MapView.swift** (의존 없음) — 내위치 패딩 축소. 1과 병렬 가능, 시각검증은 2 적용 후 DoD-B.
→ 1·3 병렬 → 2.

## 무회귀 보장 (QE-1)
- ＋FAB selection 불변(BR-1/AC-6): `plusButton` 비참조.
- 미읽음 배지(BR-2/AC-7): overlay Circle + hasUnread 인자 동일 전달, 불변.
- 딥링크(QE-1): consumePending/.onChange/.task 불변(TabView로 modifier 일관 부착).
- 맵 full-bleed(BR-3/AC-8): MapContainerView().ignoresSafeArea() 불변.
- 룰렛(BR-4): 불변.
- 키보드(QE-2): 바만 고정, 입력바는 scrollDismissesKeyboard + 자동회피 불변. BotChatView 수정 없음.

## AC 매핑
| AC | 충족 |
|----|------|
| AC-1 단일상수 | FloatingTabBar.Metrics (자동회피라 외부 상수 불요) |
| AC-2 내위치 | safeAreaInset 자동회피 + 중복여백 제거 |
| AC-3 입력바 | safeAreaInset 자동 예약 |
| AC-4 바 safe area(최소12) | safeAreaInset 배치 + bottomGap=12 |
| AC-5 iOS26≠폴백 | glassBackground(.ultraThinMaterial) vs solidBackground(panel) |
| AC-6 ＋불변 / AC-7 배지불변 / AC-8 full-bleed | 무회귀 §|
| AC-9 알림/내정보 inset | safeAreaInset ScrollView 자동 전파 |

> AC-2/3/9는 "콘텐츠가 바 footprint 인지"를 SwiftUI safe area로 충족. 수동 상수 가산이 오히려 이중가산 버그 → 자동 예약이 의도(비가림)를 더 정확히 만족. 실제 비가림은 DoD-B 시각검증.

## 탐색 추가 항목
- Features/Chat/ChatScrollContainer.swift:75 → scrollDismissesKeyboard(.interactively). 바 부착·키보드 ignore와 독립(QE-2 안전). 변경 없음.
- Features/Photo/SquareCropView.swift:28 → 유일 GeometryReader. 이번 설계는 관용 경로라 불필요(단순화).
- Core/Auth/AppleAuthService.swift:118 → 유일 keyWindow 접근. SwiftUI 부착이 컨벤션 정합.

## DoD-B 시각 QA 체크리스트 (Mac/Xcode 26)
- [ ] 기기별 바 배치: 홈인디케이터 有(iPhone 15)/無(SE) 모두 적정, inset 0 최소 12pt(AC-4).
- [ ] 비가림: 지도 내위치 / 채팅 입력바 / 알림·내정보 스크롤 하단(AC-2/3/9).
- [ ] **토스트 겹침(CONSIDER)**: MapView `infoToast .padding(.bottom,90)`(line 57)이 바와 안 겹치는지. 겹치면 90 보정(범위 외).
- [ ] 내위치 `.padding(.bottom)` 최종 수치(12~16) 확정.
- [ ] Liquid Glass: iOS26.5 시뮬에서 glassBackground 반투명 렌더(불투명 흰캡슐 회귀 해소). .ultraThinMaterial→glassEffect 교체 보정.
- [ ] 폴백 17~25: 솔리드 흰 캡슐 + shadowMd 유지(BR-5).
- [ ] 키보드: 채팅 키보드 시 바 고정, 입력바만 위로, 빈 공간 없음(QE-2).
- [ ] 키보드 **이중 밀림 없음**(cross-review ZT): safeAreaInset bottom inset이 키보드 시 변동해 입력바가 "키보드+바높이"만큼 이중으로 밀리지 않는지. 이상 시 키보드 처리를 FloatingTabBar body 내부로 격리 재검토.
- [ ] (cross-review) Mac/Xcode 빌드에서 `FloatingBarBackground` glass/solid 분기(`some View` 불투명 타입) 컴파일 경고 0 확인.
- [ ] 스크롤 잘림: 알림/내정보 마지막 항목까지 자연 스크롤.
