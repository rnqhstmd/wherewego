# Trust Ledger — P3 (iOS 골격 + 인증 + 온보딩)

## 통합 감사 (review)

> security-auditor 통합 감사. 총 14건 (CRITICAL 0, HIGH 4, MEDIUM 8, LOW 2). iOS 클라이언트 보안 기준.

### HIGH
- [RISK/HIGH] SecItemAdd 반환값 미검증 — 토큰 저장 실패 무음 (KeychainTokenStore.swift:139)
  - 근거: `SecItemAdd(query, nil)` OSStatus 미검사. errSecDuplicateItem/NotAvailable 등 실패 시 저장 무음 누락 → 재기동 시 토큰 없음 → 자동 로그아웃. 상태 불일치.
  - 권고: status != errSecSuccess 시 throw/로깅. (수정 채택)
- [RISK/HIGH] SecRandomCopyBytes 실패 시 fatalError — 프로덕션 크래시 (NonceGenerator.swift:21)
  - 근거: fatalError는 앱 종료 직결. Apple 로그인 중 발생 시 데이터 없이 종료. QE-4 "크래시 없음" 위반 소지.
  - 권고: throw로 전환, AppleAuthService에서 .appleUnavailable 매핑. (수정 채택)
- [GAP/HIGH] wireUp() 호출 전 401 → logoutHandler nil 경쟁 (RootView.swift:33, AppDependencies.swift:38)
  - 근거: RootView.task의 wireUp→bootstrap 순서와 OnboardingRouter.task 병렬 실행이 SwiftUI 런타임상 미보장. 401→refresh 실패 시 handler nil이면 로그아웃 누락(무한 인증오류). 자기점검 #5 완화했으나 구조적 잔존.
  - 권고: KeychainTokenStore init에 logout 클로저 주입 or wireUp 동기 보장. (수정 채택)
- [GAP/HIGH] inFlight 동시 refresh 직렬화 테스트 미구현 (KeychainTokenStoreTests, 설계 §13 명시)
  - 근거: 설계가 "동시 2회 refresh → performRefresh 1회" 테스트 명시했으나 누락. 직렬화 회귀 미검증.
  - 권고: async let 2회 병렬 호출로 performRefresh 1회 검증 테스트 추가. (수정 채택)

### MEDIUM
- [RISK/MEDIUM] appendingPathComponent 경로주입 — GroupAPI.acceptInvite(token) 사용자입력 path 삽입(APIClient.swift:58, GroupAPI.swift:57). 권고: token alphanumeric 검증 or URLComponents.
- [RISK/MEDIUM] kSecAttrAccessibleAfterFirstUnlock — 백그라운드 접근 허용. P3는 백그라운드 작업 없으니 WhenUnlockedThisDeviceOnly 상향 가능. 설계 근거 명시 권고. (이월: P4 백그라운드 도입 시 재검토)
- [RISK/MEDIUM] BR-7 카카오 취소 시 errorMessage nil — 스펙 불일치 (LoginViewModel.swift:37). → QUESTION으로 사용자 결정
- [RISK/MEDIUM] performRefresh 네트워크오류도 logoutHandler 호출 — 과잉 로그아웃 (KeychainTokenStore.swift:98). 권고: URLError는 토큰삭제 없이 rethrow. (수정 채택)
- [GAP/MEDIUM] 카카오 취소 .Cancelled 외 케이스 미처리 (KakaoAuthService.swift:59). 이월: 키 발급 후 실기 검증.
- [GAP/MEDIUM] URL 조립 결과(/api/v1//groups/me) 미검증 (APIClient.swift:58). 권고: 테스트 추가. (경미 — 이월)
- [GAP/MEDIUM] 이중 clear (refresh 실패→clear + SessionStore.logout→clear). errSecItemNotFound로 무해하나 향후 revoke 추가 시 문제. (이월: 문서화)
- [GAP/MEDIUM] 앱 재설치 후 Keychain 잔존 → 중복 온보딩. PRD 인지·P4 연기.

### LOW
- [RISK/LOW] LoginView Apple 버튼 allowsHitTesting(false)+오버레이 — 접근성(P6). 이월.
- [ASSUMPTION/LOW] GroupAPI HTTP_200/NO_CONTENT 문자열 코드 의존 — APIClient 내부 변경 시 취약. 권고: APIError 강타입화(이월).

### POLICY
- [POLICY/HIGH] Debug http 평문 — localhost는 ATS 예외라 시뮬 안전. 실서버 URL 시 NSExceptionDomains만, NSAllowsArbitraryLoads 금지 정책 명시. (현 설정 안전, 정책 기록)
- [POLICY/MEDIUM] Release.xcconfig 카카오 키 placeholder — 배포 전 키 주입 가드 부재. 권고: 키 발급 후 주입 + CI 가드. (P3 알려진 제약, 키 발급 후)

### ASSUMPTION
- [ASSUMPTION/HIGH] performRefresh baseURL HTTPS는 Release만 — Debug에 실서버 URL 금지 정책 문서화. (정책 기록)
- [ASSUMPTION/MEDIUM] appendingPathComponent 선행슬래시 계약 암묵 — 주석에만 기록. (이월: URLComponents 전환 검토)
- [ASSUMPTION/MEDIUM] Apple 재로그인 fullName nil → 서버 기존닉네임 유지 — P1 백엔드 AC-11 의존, 실기 미검증. (P1 통합테스트 의존)

### 정합 확인 (PRD/설계 대비)
- [정합] BR-2 Apple nonce(request.nonce=sha256Hex(rawNonce), 서버 평문). [정합] BR-3 Keychain only(UserDefaults 토큰 없음). [정합] BR-5 refresh 실패→clear+logout. [정합] QE-3 Kakao placeholder 이중차단. [정합] FR-10 라우트가드 5단계.
- [불일치] BR-7 취소 메시지(VM에서 nil). [불일치] 설계 §13 inFlight 직렬화 테스트 누락.

### QA 교차 (qa-manager)
- CERTAIN: Critical 0. Warning 4(AppleAuthService Task self 강한참조, KeychainTokenStore Task[self] 재진입, WelcomeWizard try? 일관성, OnboardingRouter 초기 SplashView=AC-11). Info 3(BR-7, @State dependencies, JSONEncoder 반복).
- QUESTION: BR-7 취소메시지 / SplashView 초기노출 / ActiveGroup 디코딩(→ 오케스트레이터 직접 확인: APIClient data null→code HTTP_200 throw, GroupAPI 잡음, 테스트 StubURLProtocol 실검증, 해소).
- AC: Critical 없음. AC-1~6/8~10/12~21 충족. AC-7 부분(BR-7), AC-11 부분(SplashView).

### 미답변/이월
- MEDIUM/LOW 다수 이월(P4/P6 또는 문서화): kSecAttrAccessible 상향, 카카오 취소 케이스 실기검증, URL 조립 테스트, 이중 clear 문서화, 재설치 Keychain, 접근성, APIError 강타입, ATS/HTTPS 정책 기록.
