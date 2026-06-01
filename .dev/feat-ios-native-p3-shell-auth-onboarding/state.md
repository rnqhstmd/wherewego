phase: complete
status: completed
vcs-type: git
branch: feat/ios-native-p3-shell-auth-onboarding
base: develop
dev-dir: .dev/feat-ios-native-p3-shell-auth-onboarding
project-type: java-spring, node (P3 실작업=Swift/iOS, config 미정의)
project-root: ./
args: "phase p3 구현시작"
flags: (none)
mode: normal
intent-source: user-selection
started: 2026-06-01
auto-stashed: false
last-known-head: af29cc62e5896e9a8d7586c39888730136f9c5b2
current-step: "complete — 인수 ACCEPT, 커밋/PR"
acceptance: "ACCEPT — AC-1~21 전부 충족. AC-6실행/8실행/22 발급물 대기. 보완: AC-21 기존사용자 위저드 경유(P4 wizardShown 플래그 검토 권장)"
steps:
  implement:
    - 구현 계획 승인: completed
    - 배치 구성: completed
    - coder 구현 (B1 인프라): completed
    - coder 구현 (B2 비-UI 로직): completed
    - coder 구현 (B3 UI): completed
    - 통합 빌드 검증: completed
    - 자기점검: completed
    - 테스트 작성: completed
  review:
    - mechanical-gate: completed   # xcodebuild build SUCCEEDED + test 58 passed
    - qa-review-1: completed   # QA Critical0/Warn4/Info3/Q3
    - zt-audit-1: completed    # ZT CRITICAL0/HIGH4/MED8/LOW2
    - fix-1: completed         # HIGH4+AC11+네트워크오류 수정. 60 tests passed. BR-7 취소=현행유지 결정
    - qa-confirm: completed     # 확인리뷰 통과, 회귀없음, Critical0. flaky 우려는 오판(테스트 자체 reset 확인)
review-log:
  - mechanical-gate: "xcodebuild build SUCCEEDED + test 60 passed (iOS26.5 시뮬, ad-hoc 서명)"
  - review-1: "QA Critical0/Warn4/Info3/Q3; ZT CRITICAL0/HIGH4/MED8/LOW2"
  - decision: "BR-7 취소=현행유지(사용자). HIGH4+AC11+네트워크오류 수정. MED/LOW 이월(P4/P6/문서)"
  - fix-1: "6건 수정(SecItemAdd throws/fatalError제거/LogoutHandlerBox/SplashView동기/네트워크오류보존/inFlight테스트). 60 tests passed"
  - qa-confirm: "확인리뷰 통과, 회귀없음, Critical0. StubURLProtocol flaky 우려는 오판(line189 자체 reset)"
  - deferred: "MED/LOW 이월: kSecAttrAccessible 상향(P4), 카카오취소케이스 실기검증, URL조립테스트, 이중clear문서화, 재설치Keychain(P4), 접근성(P6), APIError강타입, ATS/HTTPS정책기록, Release키주입가드(키발급후)"
domain-context: auth (glossary + architecture 로드됨)
references: (none)
phases:
  setup: completed
  requirements: completed
  design: completed
  implement: completed
  review: completed
  complete: completed
pr: "https://github.com/rnqhstmd/wherewego/pull/90 (base develop), 커밋 0eb4c7c"
context-feedback: "auth status.md P3 섹션 추가 + architecture.md iOS 클라이언트(P3) 구조/주제문서 반영"
  implement: pending
  review: pending
  complete: pending
notes:
  - 베이스 develop (P1 #86, P2 #87/88/89, 스캐폴드 #83 전부 머지됨 확인)
  - 설계 문서: .dev/feat-ios-native-swiftui/{roadmap,plan,prerequisites}.md
  - P3 범위: Xcode 프로젝트 + SPM(Mapbox/Kakao) + 폰트 + xcconfig + Keychain TokenStore + Kakao/Apple 로그인 + 온보딩(6단계) + 라우트가드
  - 환경: Xcode 26.5, Swift 6.3.2, xcodegen 설치됨, tuist 없음
  - 사용자 발급물(prerequisites §7 P3): Kakao iOS Native key+URL scheme, Sign in with Apple capability, Mapbox secret(.netrc), 폰트 파일 — requirements에서 확인 필요
requirements-decisions:
  - Q1 Xcode 관리: XcodeGen (ios/project.yml 버전관리, 생성된 .xcodeproj는 ios/.gitignore)
  - Q2 발급물 보유: Mapbox public 토큰(pk.)만 보유. Kakao Native Key·Apple 계정/Bundle ID/capability·폰트 4종·Mapbox secret(sk.) 미보유 → UI/구조만 선행, 실검증 연기
  - Q3 Mapbox SDK: P4로 연기 (secret 미보유 SPM 불가). P3 SPM=Kakao만, Apple은 내장 AuthenticationServices
  - Q4 Welcome 위저드: 2스텝 축소 (그룹+초대링크), 스텝3 챗봇 제거
  - Q5 최소 iOS: 17 (deployment target)
  - 검증 영향: Apple 계정 없음→코드서명/Sign in with Apple 런타임 불가. Kakao Key 없음→실로그인 불가. P3 달성=시뮬레이터 컴파일+UI/플로우/Keychain/라우트가드 로직+키 주입 배선구조. 실로그인은 키 발급 후
execution-log:
  - phase: setup
    result: "develop 베이스(이미 체크아웃+pull 최신), feat/ios-native-p3-shell-auth-onboarding 생성, codemap 15파일, DOMAIN_CONTEXT=auth, Xcode/xcodegen 가용 확인"
  - phase: requirements
    agent: product-owner
    result: "PRD 초안 FR18/BR10/AC21. Q&A 5건 순차+Align 확정. Mapbox public/secret 토큰 차이 확인(env=pk., netrc 없음)"
  - phase: design
    agent: architect
    result: "대형 설계, 신규 34파일/수정 2. backend 경로 Grep 확정(PUT /users/me, GET /groups/me null가능, invite-links). 4질문"
  - phase: design
    agent: design-critic
    result: "MUST-ADDRESS 2건(groups/me null 디코딩 vs APIClient 무변경 / TokenRefresher 분리 정당성)+CONSIDER 4(onLogout 동시성·xcconfig 증명·VM분할기준·NotificationView위치)"
  - phase: implement
    agent: coder (B1/B2/B3)
    result: "33파일 신규(인프라+비UI 13+UI 16). B2 1건 조정(OnboardingFlags nonisolated). project.yml schemes 보정"
  - phase: implement
    step: 통합빌드
    result: "환경 미스매치(Xcode26.5 SDK vs 시뮬런타임26.3) → iOS26.5 시뮬런타임 다운로드(8.5GB) 후 BUILD SUCCEEDED. AppIcon placeholder+Kakao OAuthToken Sendable 수정"
  - phase: implement
    agent: qa-manager (자기점검)
    result: "CERTAIN Critical1(groups/me 구분)+Warning4, QUESTION3. AC 대부분 충족"
  - phase: implement
    agent: coder (자기점검 수정)
    result: "Critical#1+Warning#3/#4/#5 수정(code기반 구분/wireUp logout레이스/do-catch/중복제거+loading). BUILD SUCCEEDED. Warning#2+QUESTION3 review 이월"
design-decisions:
  - Q1 상태관리: ObservableObject + @StateObject/@EnvironmentObject (actor APIClient와 일관)
  - Q2 NotificationView: Welcome 위저드 완료 직후 notifAsked=false시 1회 노출 → Groups (PRD 라우트 누락 보완)
  - Q3 Kakao 로그인: 카카오톡 앱 우선(loginWithKakaoTalk)/미설치시 loginWithKakaoAccount 폴백
  - Q4 Kakao SDK: exactVersion 최신 안정버 고정 후 Swift6 빌드 검증
  - MUST#1 groups/me null: architect 2차에서 GroupV1Controller 직렬화 형태(null vs 키부재) 확인 + APIEnvelope<ActiveGroup?> 디코딩 단위테스트 증명. 미증명시 APIClient 옵셔널 경로 보완
  - MUST#2 TokenRefresher: 순환의존 실재 여부 재검토. KeychainTokenStore 내부 private refresh POST로 단순화 가능성
