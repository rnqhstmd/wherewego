# Cross-Review 결과

- advisor: claude (qa-manager + security-auditor 병렬)
- 브랜치: feat/ios-native-p3-shell-auth-onboarding (base: develop)
- DEV_DIR: .dev/feat-ios-native-p3-shell-auth-onboarding
- 실행: 2026-06-01, PR #90 gemini 봇 리뷰 교차 검증 포함

## AC 충족 매트릭스

[Must] AC-1~21 중 **20건 충족**, AC-7 부분 충족(1건, trust-ledger 기등록).

| AC | 충족 | 근거 |
|----|------|------|
| AC-1~6, 8~21 | O | project.yml/xcconfig/Kakao SPM/폰트/Keychain/인증배선/라우트가드/온보딩 전부 코드 반영(상세 에이전트 보고) |
| AC-7 | 부분 | LoginViewModel:37 카카오 취소 시 errorMessage=nil. BR-7 메시지 미표시 — **사용자가 "현행 유지(조용히 복귀)" 결정**(trust-ledger 기록) |

## 설계 범위 이탈

**이탈 없음.** ios/ 단독. LogoutHandlerBox는 KeychainTokenStore.swift 내부 헬퍼(설계 MUST#2 해소 목적, 의도 내 구현).

## 신규 위험 (trust-ledger 미등록)

### HIGH
- [GAP/HIGH] OnboardingRouter.swift:151-163 — **비-401 네트워크 오류 시 SplashView 무한 stuck**
  - 근거: resolveGroupRoute() do/catch의 catch가 모든 예외를 삼킴. 401(refresh실패→logout→phase전환)이 아닌 URLError(타임아웃/오프라인)·5xx·JSON파싱 오류 시 logout도 phase전환도 없고 route=.resolvingGroup 유지 → SplashView 무한 표시, .task 1회만 실행이라 재시도 없음. 앱 강제종료만이 탈출.
  - 출처: fix-1(self-check)의 try?→do/catch 수정이 도입한 부작용(회귀). gemini PR #90 #3과 일치(양 에이전트 합의).
  - 권고: catch에서 401(logoutHandler가 처리)과 비401 분리. 비401 → route=.groupStart 폴백(최소 stuck 방지) 또는 재시도 Alert.

### Warning
- [MAINT] WelcomeWizardViewModel.swift — 초대 링크 발급 실패 후 재시도 불가 UX. inviteLoaded 플래그가 실패 시 false 리셋 안 됨 → "다음에 할게요" 외 재시도 경로 없음. 권고: 실패 시 재시도 버튼 또는 플래그 리셋.

### Info
- [MAINT] WhereWeGoApp.swift:8 — AppDependencies(@MainActor class)를 @State로 보유. @StateObject(ObservableObject 채택 시) 또는 `let`이 의미상 정확. 동작은 무해.

## gemini PR #90 리뷰 교차 검증 (5건)

| # | 위치 | gemini 등급 | cross-review 판정 | 근거 |
|---|------|------------|------------------|------|
| 1 | Nickname.swift:36 한글 IME 조합 깨짐 | HIGH | **오판** | iOS IME는 조합 확정(commit) 후에만 TextField text 바인딩 반영 → .onChange가 조합 중 발화 안 함. NicknameViewModel sanitizeInput은 결과가 다를 때만 되돌림. 설계 §4 명시. 실제 깨짐 없음. (방어적 개선 여지는 있으나 필수 아님) |
| 2 | KeychainTokenStore.swift:88 액터 재진입 세션 혼선 | SECURITY-HIGH | **오판(과장)** | inFlightRefresh 가드가 동시 refresh를 1회로 직렬화(테스트 검증). actor isolation으로 Task 내 read/write 직렬. "다른 계정 로그인 덮어쓰기"는 phase=.authenticated 중 LoginView 비표시라 발생 경로 없음. trust-ledger "실제 버그 아님" 유효. SECURITY-HIGH 과장. refreshToken 일치 가드는 MEDIUM 방어보강(이월 가능). |
| 3 | OnboardingRouter.swift:163 SplashView stuck | HIGH | **타당(수정 권고)** | 위 신규 HIGH 참조. 양 에이전트 합의. fix-1 회귀. |
| 4 | NotificationView.swift:24 Main Actor 격리 위반 | HIGH | **오판** | View 프로토콜은 @MainActor 격리, struct 메서드(onAllow)는 암묵 상속. Swift 6 빌드 통과(60 tests)가 증명. 명시 @MainActor는 가독성 선택이지 결함 아님. |
| 5 | LoginView.swift:86 VoiceOver 접근성 | MEDIUM | **이미처리(P6 이월)** | trust-ledger [RISK/LOW] 접근성 P6 이월 기등록. 중복. accessibilityLabel 추가는 단순하나 P3 블로커 아님. |

## 정합 확인 (PRD BR / 설계 보안 약속 vs 코드)

BR-2(Apple nonce 평문/해시)·BR-3(Keychain only)·BR-5(refresh 실패→clear+Login) 정합. 설계 §5(inFlight 직렬화)·§8(nonce)·§9(GroupAPI nil 정규화)·§12(LogoutHandlerBox 2단계 조립) 전부 정합. fix-1~5(SecItemAdd 검증/fatalError 제거/logout 레이스/inFlight 테스트/네트워크오류 보존) 재발 없음.

## 총평

- 강점: ① LogoutHandlerBox로 .task 경쟁을 동기 초기화로 구조적 차단(설계 2단계 조립 의도 정확 구현). ② OnboardingRouter 라우트 결정을 순수 static 함수로 분리 → RouteGuardTests 단위 검증 + SRP.
- 합산: 신규 **HIGH 1(#3 stuck)** + Warning 1 + Info 1. AC 20/21. gemini 5건 중 타당 1·오판 3·이미처리 1.
- 권고: **#3(OnboardingRouter 비-401 stuck) 머지 전 수정**. gemini 오판 4건은 PR에 근거와 함께 답변(또는 resolve) 권장. Warning/Info는 선택 수정.
