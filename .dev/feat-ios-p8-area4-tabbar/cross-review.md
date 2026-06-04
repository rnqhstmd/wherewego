# Cross-Review 결과

- advisor: claude (qa-manager + security-auditor, cross-review 미션)
- 브랜치: feat/ios-p8-area4-tabbar (base: develop)
- DEV_DIR: .dev/feat-ios-p8-area4-tabbar
- 비고: qa-manager 1차는 cwd 오인으로 P7(feat-ios-nav-redesign) 산출물을 잘못 검증 → 절대경로로 재실행한 결과 채택.

## AC 충족 매트릭스
| AC | 판정 | 근거 |
|----|------|------|
| AC-1 footprint 단일상수 | O | FloatingTabBar.Metrics(barHeight 64/bottomGap 12), MapView가 참조 |
| AC-2 내위치 상수 참조 | O(코드) | MapView.swift:265 `.padding(.bottom, FloatingTabBar.Metrics.bottomGap)`, 매직 28 제거. 비가림 DoD-B(AC-B3) |
| AC-3 입력바 footprint | O(자동) | safeAreaInset 자동 회피, BotChatView 의도적 무변경. DoD-B(AC-B4) |
| AC-4 바 safe area | O | safeAreaInset 부착 + ignoresSafeArea는 .keyboard에만(line 113). 이중가산 없음. DoD-B(AC-B1/B2) |
| AC-5 iOS26≠폴백 | O | glassBackground(.ultraThinMaterial) vs solidBackground(WGColor.panel), @available 가드 |
| AC-6 ＋selection 불변 | O | onPlusTap() 단독 |
| AC-7 배지 불변 | O | pinNew 8pt overlay 불변 |
| AC-8 맵 full-bleed | O | MapContainerView().ignoresSafeArea() 불변 |
| AC-9 알림/내정보 inset | O(자동) | safeAreaInset 자동 전파, 무변경 의도. DoD-B(AC-B7) |
| AC-B1~7 | DoD-B | Mac/Xcode 시각검증(Windows 미검증 정상) |

**AC-1~9 코드검증 전항 충족. AC-B1~7 DoD-B. 설계 범위 이탈 없음.**

## 설계 범위 이탈
이탈 없음. 수정 3파일(FloatingTabBar/MainTabView/MapView) 외 변경 없음. BotChat/Notification/MyInfo/Theme 무변경(의도).

## 정책/제약·무회귀 충실도 (security-auditor)
- 비목표 전부 정합: 웹 액션바 회귀 ✗·5탭 구조 변경 ✗·백엔드/프론트 변경 ✗·glass 전용 토큰 신규정의 ✗(.ultraThinMaterial은 시스템 머티리얼).
- BR-1~5 정합: ＋FAB selection 불변·배지 보존·맵 full-bleed·룰렛 불변·폴백 솔리드 유지.
- 무회귀: 딥링크/시트/배지 modifier가 TabView 단일 루트로 일관 부착.
- **ZT-3 신선도: 해소 확인** — `.ignoresSafeArea(.keyboard)`가 FloatingTabBar 클로저 내부에만 적용(TabView 전체 아님).

## 신규 위험 (trust-ledger·self-check에 없는 것만)
### Warning
- **[MAINT] design.md ↔ 코드 불일치** (qa+ZT 공통): 설계서 §1은 `private enum Metrics`로 표기했으나 실제는 `enum Metrics`(internal — MapView 참조 위해 의도적 승격). **코드가 정확, 문서 정정 필요**(PR 차단 아님).

### Medium (DoD-B 이월)
- **[RISK] `some View` 불투명 타입** (ZT): glassBackground(.ultraThinMaterial)와 solidBackground(panel)이 서로 다른 구체 타입 반환. 평가: `ViewModifier.body(content:)`는 프로토콜에서 `@ViewBuilder`이므로 if/#available 분기가 `_ConditionalContent`로 묶여 **컴파일됨**. 단 Windows 빌드 불가 → Mac 빌드로 최종 확인. (무조건 보장하려면 body에 명시적 `@ViewBuilder` 추가 가능, zero-risk)
- **[ASSUMPTION] 키보드 이중 회피** (ZT, ZT-3과 다른 신규 각도): safeAreaInset이 TabView에 기여하는 bottom inset이 키보드 표시 시 변동하면, 채팅 입력바가 "키보드+바높이"만큼 밀려 이중 회피될 가능성. ZT-3(억제 위험)의 반대 방향. → **DoD-B 채팅 키보드 시나리오에 "이중 밀림 없음" 항목 추가 검증.**

### Info / Low
- [CLEAN] infoToast `.padding(.bottom, 90)` Metrics 미참조 — ZT-4 기존 DoD-B. 차기 정리 시 상수화 고려.
- [ASSUMPTION/LOW] Metrics internal 접근수준 — 단일 앱 타겟 기준 정상. 멀티 타겟 분리 시 재검토.

## 총평
- Critical 0 / Warning 1(문서 정합) / Medium 2(DoD-B) / Info·Low 2.
- AC-1~9 충족, 범위 이탈 없음, ZT-3 해소 확인, trust-ledger 재발 없음.
- 코드측 통과. 조치 권고: ①design.md `private`→`enum Metrics` 정정 ②(선택) body에 `@ViewBuilder` 명시 ③DoD-B에 키보드 이중밀림 항목 추가.
