# Trust Ledger — P8 영역4 (하단 플로팅 5탭바 정합성)

## QA (qa-manager 리뷰)
- Critical 0 / Warning 0 / Info 1 / QUESTION 1.
- Info: FloatingTabBar 헤더 주석에 키보드 고정 책임 위치(MainTabView) 명시 권장(비강제).
- QUESTION: `.ignoresSafeArea(.keyboard)` 범위 — qa는 "입력 필드 없는 탭만 보고 무해, 유지(a)" 권고. **단 ZT가 채팅 입력바 영향을 포착(아래 ZT-3 참조) → ZT 판단이 우선.**
- AC-1~9·BR-1~5·QE-1~2 충족(메커니즘 기반, 시각 확정 DoD-B). 리뷰 통과.

## 통합 감사 (security-auditor) — CRITICAL 0, HIGH 3, MEDIUM 4, LOW 1

### 조치(코드 수정) 항목
- **[ZT-3 ASSUMPTION/HIGH] `.ignoresSafeArea(.keyboard, edges:.bottom)` 범위 오류** (MainTabView.swift:112)
  - 근거: TabView 전체에 적용 → 채팅 입력바(BotChatView)의 SwiftUI 키보드 회피까지 억제 → 키보드 표시 시 입력바 가림 위험. 설계 의도("바만 고정, 입력바는 회피")와 모순.
  - 권고/조치: `.ignoresSafeArea(.keyboard)`를 **FloatingTabBar(safeAreaInset 클로저 내부)에만** 적용 → 바는 고정, 입력바는 회피. **코드 수정 채택.**

### DoD-B 시각검증 항목 (코드 추가 시 이중 가산 위험 → 수동 inset 금지, Mac 확인)
- [ZT-1 GAP/HIGH] NotificationInboxView ScrollView 하단 자동 inset 미보장 — safeAreaInset 전파 여부. 수동 inset 추가 시 이중 가산 → DoD-B AC-B7로 확인.
- [ZT-2 GAP/HIGH] MyInfoView `.padding(.bottom,40)` < footprint(76) 가능성 — 자동 전파 가산 예상. DoD-B AC-B7 확인.
- [ZT-4 GAP/MEDIUM] MapView infoToast `.padding(.bottom,90)` 바 겹침 — DoD-B 확인(겹치면 90 보정, 범위 외).
- [ZT-5 GAP/MEDIUM] MapView EmptyMapCard 가림 — PRD 엣지케이스. DoD-B 체크리스트에 추가.
- [ZT-6 POLICY/MEDIUM] BotChatView 입력바 AC-3 조건부 충족(자동 전파 의존) — DoD-B AC-B4/B6 확인.
- [ZT-7 ASSUMPTION/MEDIUM] `@available(iOS26)` 메서드 분리 컴파일 — Mac/Xcode 빌드 시 경고 확인.
- [ZT-8 ASSUMPTION/LOW] bottomGap=12, inset 있는 기기 시각 여백 — DoD-B AC-B1/B2.

## 종합
- 코드 수정 1건(ZT-3 키보드 범위) 적용.
- 나머지 HIGH/MEDIUM/LOW는 전부 safeAreaInset 전파의 **시각 확정 의존** 항목 → 수동 코드 추가는 이중 가산 위험이라 **DoD-B(Mac/Xcode 26)** 검증으로 분리. design.md DoD-B 체크리스트 + 본 원장에 기록.
