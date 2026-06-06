# 자기점검 (qa-manager) — 내비 셸 재구성

수용기준 **AC-1~9 전부 충족** 확인.

## Critical (CERTAIN)
- [Critical→자동수정] MapView.swift:51 — 외부 주입 VM을 `@StateObject(wrappedValue:)`로 보유(소유권 계약 오류). 실 동작 버그는 없으나(MainTabView가 @StateObject 소유·동일 인스턴스 주입) `@ObservedObject`가 정확. 수정: `@ObservedObject private var viewModel` + `ObservedObject(wrappedValue:)`.

## Warning (phase-review 이월)
- [Warning] MainTabView switchActiveGroup — map/chat 재로드가 직렬 await. `async let`로 병렬화 권장.
- [Warning] GroupContext.bootstrap() — `try?`로 네트워크 에러와 "그룹 0개"(nil) 동일 처리. BR-2 구분 모호.
- [Warning] GroupSwitcherSheet — 시트 닫기 책임이 호출부(MainTabView)에 있음을 주석으로 계약 명시(이중 dismiss 방지).

## Info
- GroupSwitcherSheet `.idle`/`.loading` 동일 처리, MainTabView onChange 중복 isAddMenuExpanded, TopBar maxWidth 200 매직넘버.

## QUESTION (사용자 확인)
1. MapView 지도 탭 `reserveFloatingTabBarSpace()` 미적용 — 의도(full-bleed, MapView 내부 Metrics 직접 패딩)인가? (권장: 의도)
2. TopBar `.padding(.top, 8)` 고정 — 노치/Dynamic Island 겹침 없는가? (실기기 확인 필요)
3. BotChatViewModel.switchGroup()이 groupId 없이 동작 — 봇 방이 userId 기반(전역)이라 그룹 전환 시 새로고침만으로 충분한가? (코드상 전역 방 — 사용자 멘탈모델 "채팅=그룹 종속"과 불일치 가능)
