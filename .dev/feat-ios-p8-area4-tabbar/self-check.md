# 자기점검 결과 (phase-implement)

## CERTAIN (자동 수정 완료)
- [Warning] FloatingTabBar.swift:4 - stale 주석("ZStack 하단에 띄운다") → ".safeAreaInset(edge:.bottom) 부착"으로 수정 완료.
- [Info] MainTabView.swift:4 - stale 주석("ZStack 하단 FloatingTabBar") → ".safeAreaInset 부착"으로 수정 완료.

## SELF_CHECK_FINDINGS (phase-review 이월, 중복보고 방지용)
- [Info] AC-4 코드 레벨: FloatingTabBar는 `bottomGap=12` 고정이고 `safeAreaInsets.bottom`을 직접 더하지 않음. 이는 **의도된 설계** — `.safeAreaInset(edge:.bottom)`가 플로팅 콘텐츠를 safe area 위에 배치하므로 bottomGap이 홈 인디케이터 위 간격이 됨. 여기에 safeAreaInsets.bottom을 더하면 이중 가산(design-critic가 지적한 버그) 재발. **추가 금지.** 시각 확정은 DoD-B.

## SELF_CHECK_QUESTIONS (phase-review에서 사용자 확인 — 단, 대부분 DoD-B 시각검증 항목)
- [QUESTION] AC-4: safeAreaInset 부착 시 바가 홈 인디케이터 위에 자동 배치되는지 → Mac 시각검증(DoD-B AC-B1/B2). 코드상 의도는 맞음(이중가산 회피).
- [QUESTION] AC-3: MainTabView.safeAreaInset의 reduced safe area가 NavigationStack→BotChatView 입력바까지 전파되어 자동 회피하는지 → DoD-B AC-B4.
- [QUESTION] AC-9: 동일 전파가 NotificationInboxView·MyInfoView ScrollView 하단 inset으로 작동하는지 → DoD-B AC-B7. (MyInfoView 기존 bottom:40 패딩 + 자동 inset 가산 예상)
- [QUESTION] AC-2: MapView 내위치 `.padding(.bottom,12)`가 FloatingTabBar.Metrics(private)를 참조하지 않고 독립 수치 사용. safeAreaInset 자동 회피 설계라 내부 추가 패딩은 보조적이므로 의도적. (FR-1 SSOT는 바 내부 수치에 한정)

## AC 충족 요약 (qa-manager)
- ✅ AC-5(iOS26≠폴백), AC-6(＋selection불변), AC-7(배지불변), AC-8(맵 full-bleed 불변) — 코드 확정.
- ⚠️ AC-1/2/3/4/9 — 메커니즘(safeAreaInset 자동 회피)으로 충족, 시각 확정은 DoD-B.

## 빌드
- Windows 환경 xcodebuild 부재로 빌드 불가. Swift 문법·타입·@available 가드는 수기 검증 Green. 빌드/시각 회귀는 DoD-B(Mac/Xcode 26).
