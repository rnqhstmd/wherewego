# PRD: P8 영역4 — 하단 플로팅 5탭바 시각 완성도 및 콘텐츠 가림 해소

> 브랜치: feat/ios-p8-area4-tabbar (base develop) · iOS SwiftUI 단독 · 빌드/시각 최종검증은 Mac(DoD-B)
> 확정 결정(2026-06-04):
> - 영역4 방향 = **5탭 플로팅바 유지 + 시각 수정**(웹 액션바 회귀 안 함)
> - Liquid Glass = **iOS26 분기에 glass API 코드 삽입(폴백과 분리, 구조 확보), 정확 파라미터는 Mac/DoD-B 보정**
> - footprint 적용 = **전 탭(지도·채팅·알림·내정보) 모두**

## 배경 / 문제
P7(내비 재설계)에서 시스템 탭바를 제거하고 커스텀 `FloatingTabBar`(둥근 캡슐 플로팅 바, 5탭: 어디갈까·채팅·＋·알림·내정보)를 도입했다. 5탭 구조는 제품 결정으로 확정·유지된다. 시뮬레이터 실행에서 3가지 시각/레이아웃 문제 발견:

1. **Liquid Glass 미작동**: `FloatingBarBackground`의 `#available(iOS 26.0, *)` 분기와 iOS 17~25 폴백 코드가 **완전히 동일**(둘 다 솔리드 `Capsule().fill(WGColor.panel=#FFFFFF)`) → 불투명 흰 캡슐로만 렌더링. iOS26 Liquid Glass가 실제로 분기되지 않고 `TODO(DoD-B)`로 남아 있음.
2. **safe area 미반영**: 바가 `.padding(.bottom, 12)` 고정값만 사용 → 홈 인디케이터가 있는 기기(iPhone X 이후)에서 safe area bottom inset 미반영, 인디케이터와의 간격 부정확.
3. **콘텐츠 겹침**: 지도 "내 위치" 버튼(`MapView` 우하단 `.padding(.bottom,28)`), 채팅 입력바(`BotChatView` `.padding(.bottom,8)`), 알림·내정보 스크롤 하단이 플로팅 바 뒤로 가림. `MainTabView`가 자식 콘텐츠에 바 footprint(높이 64 + 하단여백 + safe area)를 전달하지 않기 때문.

## 목표 / 비목표
**목표**
- 전 탭에서 플로팅 바와 콘텐츠 간 **겹침 0**.
- safe area 기기에서 바가 홈 인디케이터 위에 올바르게 배치.
- iOS 26 기기에서 Liquid Glass 비주얼이 **실제 분기**(구조 확보, 시각 확정은 DoD-B).
- iOS 17~25 폴백(1차 런타임·다수 사용자)에서 솔리드 흰 캡슐 + shadow가 깔끔하게 렌더링.

**비목표**
- 웹(`frontend/`) 액션바로의 회귀 ✗ — 5탭 구조 확정·유지.
- 5탭 구조 변경(탭 추가/제거/순서) ✗.
- 백엔드 또는 프론트엔드 코드 변경 ✗ — iOS SwiftUI 단독.
- 새 탭/기능 추가 ✗.
- glass/material 전용 디자인 토큰 신규 정의 ✗ — 기존 `WGColor` 토큰으로만 처리.
- 영역 1(핀 추가 인라인)·2(핀 상세 말풍선)·3(채팅 재연결) ✗ — 별도 Phase/워크트리.

## 요구사항

### 기능 요구사항
- **[Must] FR-1 탭바 footprint 단일 정의**: 바 높이(캡슐 64pt) + 하단 여백(12pt) + safe area bottom inset을 합산/표현하는 단일 상수 또는 환경값을 정의하고, 모든 자식 탭이 이를 참조해 하단 여백을 확보한다. 매직넘버 분산 금지.
- **[Must] FR-2 지도 "내 위치" 버튼 겹침 해소**: 우하단 "내 위치" 버튼(48×48 원형)이 footprint만큼 올라가 바 뒤로 가리지 않는다. 지도 배경(full-bleed Mapbox)은 변경하지 않고 오버레이 버튼 위치만 조정한다.
- **[Must] FR-3 채팅 입력바 겹침 해소**: `BotChatView` 입력바(텍스트필드+전송)가 footprint만큼 하단 여백을 확보해 바 뒤로 가리지 않는다. 입력바 배경(`WGColor.bg`)이 바 위까지 채워진다.
- **[Must] FR-4 safe area inset 반영**: 바의 하단 여백이 기기 safe area bottom inset을 실제 반영한다. inset이 0인 기기(홈 버튼·가로모드 일부)는 **최소 12pt** 보장.
- **[Must] FR-5 iOS 26 Liquid Glass 분기 구조 구현**: `FloatingBarBackground`의 iOS 26 분기에 실제 glass 효과 modifier(예: `.glassEffect` 계열 또는 `.ultraThinMaterial` 기반)를 적용해 **폴백과 다른 코드 경로**를 확보한다. 정확한 modifier명·파라미터는 Mac/Xcode 26에서 보정(DoD-B). 컴파일 안전(`#available` 가드) 유지.
- **[Must] FR-6 알림·내정보 탭 하단 여백 확보**: `NotificationInboxView`·`MyInfoView` 등 스크롤 콘텐츠가 footprint만큼 하단 padding 또는 scroll content inset을 확보해 마지막 항목이 바 뒤로 잘리지 않는다.

### 비즈니스 규칙
- **[Must] BR-1 ＋FAB 동작 보존**: footprint 적용 후에도 ＋ 버튼은 `onPlusTap`만 호출하고 `selection` 불변. `AddPlaceSheet` 진입·중복열림 가드(`showAddPlace`) 유지.
- **[Must] BR-2 미읽음 배지 보존**: 알림 아이콘 우상단 빨간 점(`WGColor.pinNew`, 8pt)이 `hasUnread` 조건대로 표시. 건수 미표시 유지. footprint 변경이 배지 위치에 영향 없음.
- **[Must] BR-3 지도 full-bleed 유지**: Mapbox 지도는 화면 전체(safe area 포함)를 채우는 현 동작 유지. footprint는 오버레이 버튼에만 적용, 지도 영역 축소 금지.
- **[Must] BR-4 룰렛 버튼 불변**: 우상단 룰렛 버튼(`.padding(.top,60)`)은 바와 겹치지 않으므로 변경하지 않는다.
- **[Should] BR-5 폴백 분기 명시 분리**: 폴백(17~25)은 "솔리드 흰 캡슐 + `shadowMd`"를 명시적으로 유지. iOS26 분기 작업 중 폴백이 의도치 않게 바뀌지 않도록 코드상 분리 명확.

### 품질 기대
- **[Should] QE-1 무회귀**: footprint/환경값 추가가 `MainTabView` 탭 전환·딥링크 소비(`consumePending`)·알림 배지 갱신에 영향 없음.
- **[Should] QE-2 키보드 회피 보존**: 키보드 노출 시 채팅 입력바가 키보드 위로 올라가고, footprint 조정이 SwiftUI 키보드 회피 동작을 방해하지 않음.

## 수용 기준(AC)

### 코드 검증 가능 (이 Windows 환경에서 확인)
- **AC-1 [FR-1]**: 바 높이+하단여백+safe area inset을 표현하는 단일 상수/환경값이 코드에 존재하고 자식들이 이를 참조.
- **AC-2 [FR-2]**: footprint(바 점유 높이)는 `.safeAreaInset` 부착으로 자동 예약되어 "내 위치" 버튼이 자동 회피한다. 버튼-바 사이 숨 여백은 매직넘버(28/12) 단독이 아니라 `FloatingTabBar.Metrics.bottomGap` 상수를 참조한다. 시각 비가림은 DoD-B(AC-B3). [설계 전환 반영: 수동 footprint 패딩 → safeAreaInset 자동 회피]
- **AC-3 [FR-3]**: `BotChatView` 입력바 하단 여백 또는 외부 `.safeAreaInset(edge:.bottom)`이 footprint 반영 — 고정 8pt 단독 아님.
- **AC-4 [FR-4]**: 바는 `.safeAreaInset(edge:.bottom)` 부착으로 SwiftUI가 container safe area(홈 인디케이터) 위에 자동 배치한다(키보드 외 safe area 존중 — `.ignoresSafeArea`는 `.keyboard`에만 적용). `Metrics.bottomGap=12`는 바-인디케이터 간 최소 간격이며 inset 0 기기에서도 12pt 보장. **수동 `safeAreaInsets.bottom` 가산은 safeAreaInset과 중복되어 이중 가산이 되므로 금지**(design-critic 지적 반영). 기기별 시각 확정은 DoD-B(AC-B1/B2). [설계 전환 반영: 수동 safe area 읽기 → safeAreaInset 자동 배치]
- **AC-5 [FR-5]**: `FloatingBarBackground`의 iOS26 분기가 폴백과 **동일 코드가 아님**. glass modifier 적용 코드가 존재(`#available` 가드).
- **AC-6 [BR-1]**: `plusButton`의 `onPlusTap()` 호출부에 `selection` 변경 없음.
- **AC-7 [BR-2]**: `hasUnread` → `pinNew` 8pt 원 overlay 로직 불변.
- **AC-8 [BR-3]**: `MapView`에 전달되는 frame/`ignoresSafeArea` 설정 불변(지도 full-bleed).
- **AC-9 [FR-6]**: `NotificationInboxView`·`MyInfoView` 스크롤에 footprint 하단 여백/inset 반영.

### Mac/Xcode 시각 검증 (DoD-B)
- **AC-B1**: iPhone 15 Pro(iOS 17, 홈 인디케이터)에서 바가 인디케이터 위에 자연스럽게 배치, 겹침 없음.
- **AC-B2**: iPhone SE(safe area bottom=0)에서 바 하단 최소 12pt 유지.
- **AC-B3**: 지도 탭 "내 위치" 버튼 전체가 바 위에 노출·탭 가능.
- **AC-B4**: 채팅 탭 입력바(필드+전송)가 바 위에 노출·전송 가능.
- **AC-B5**: iOS 26 시뮬레이터에서 바 배경이 반투명 유리 재질로 렌더(뒤 콘텐츠 은은 투과).
- **AC-B6**: 채팅 텍스트필드 포커스 시 키보드 위 입력바 배치, 바와 겹침 없음.
- **AC-B7**: 알림·내정보 탭 마지막 항목이 바 뒤로 잘리지 않음.

## 엣지케이스
| 상황 | 기대 동작 |
|------|----------|
| safe area inset=0 (홈버튼·가로모드) | 최소 12pt 하단 여백 유지, 바가 화면 끝에 안 붙음 |
| 가로모드 | safe area 재계산 적용. 좌우 inset 있어도 바 가로여백(`.padding(.horizontal,24)`) 불변 |
| 키보드 노출(채팅) | 입력바가 키보드 위로, 바와 입력바 겹침 없음 |
| 미읽음 0→1+ 전환 | 배지 점 즉시 노출, 레이아웃 깨짐 없음 |
| 미읽음 99+ (건수 미표시) | 배지 8pt 불변, 숫자 없음 |
| ＋FAB 연타 | `showAddPlace` 가드로 중복 시트 방지(기존 동작 유지) |
| 지도 빈 핀 상태(EmptyMapCard) | EmptyMapCard가 바에 가리지 않음(footprint 적용 확인) |
| 딥링크 탭 전환 | 전환된 탭 콘텐츠에도 footprint 여백 적용됨 |

## 영향 범위 / 파일
- `ios/WhereWeGo/App/FloatingTabBar.swift` — safe area 반영, iOS26 glass 분기 구조
- `ios/WhereWeGo/App/MainTabView.swift` — footprint 상수/환경값 정의·전달
- `ios/WhereWeGo/Features/Map/MapView.swift` — 내 위치 버튼 하단 여백(룰렛 불변, 맵 full-bleed)
- `ios/WhereWeGo/Features/Chat/Bot/BotChatView.swift` — 입력바 하단 여백
- `ios/WhereWeGo/Features/Notification/NotificationInboxView.swift` — 스크롤 하단 inset
- `ios/WhereWeGo/Features/MyInfo/MyInfoView.swift` — 스크롤 하단 inset
- `ios/WhereWeGo/Core/DesignSystem/Theme.swift` — WGColor 폴백 토큰(panel·shadowMd) 유지 확인(변경 없음 원칙)
