# 자기점검 결과 — P3

빌드: xcodebuild 시뮬레이터(iOS 26.5) BUILD SUCCEEDED. 로직/스펙/보안 검증.

## CERTAIN (자동수정)
- [Critical] GroupAPI.swift:44 — groups/me nil 정규화 역전. status 200 에러를 nil로 매핑 → "그룹없음(200+data null)"과 "200+FAIL(서버에러)" 구분 불가 → 오라우팅. 수정: code 기반 구분(HTTP_200/NO_CONTENT→nil, 401·errorCode 있는 FAIL→throw).
- [Warning→실질Critical] AppDependencies.swift:29 — logoutHandler 후주입 Task 레이스. bootstrap이 setLogoutHandler보다 먼저 실행 시 refresh 실패해도 logout 안 됨(무한 인증오류). 수정: RootView가 bootstrap 전 handler 설정 보장(wireUp async).
- [Warning] OnboardingRouter.swift:115 — resolveGroupRoute try?가 401(refresh실패)을 nil로 → logout 중 GroupStart 깜빡임. 수정: do/catch, 실패 시 route 유지(logout이 phase 전환).
- [Warning] WelcomeWizardViewModel.swift:27 — 그룹 API 중복 호출 + AC-19 스텝1 깜빡임. 수정: start() 전 초기 step 로딩 상태 처리.

## 이월 (phase-review)
- [Warning] KeychainTokenStore.swift:46 — Task [self] 캡처 재진입 위험. weak self + 명시 isolated 권장.
- [QUESTION] LoginViewModel.swift:37 — 카카오 취소 시 errorMessage nil(조용). BR-7 "오류 메시지" 해석(현재유지 vs 메시지표시).
- [QUESTION] GroupAPI.swift:41 — ActiveGroup? 이중옵셔널 디코딩 vs APIClient overload vs URLSession 직접. (수정에서 code 기반으로 일단 처리)
- [QUESTION] OnboardingRouter.swift:27 — 초기 .resolvingGroup SplashView 깜빡임(P6 디자인 QA 범위 여부).

## AC 충족 (자기점검 시점)
AC-1~6,8~18,20 충족. AC-7 부분(취소 메시지 QUESTION). AC-19 부분(깜빡임, 수정중). AC-21 부분(Critical#1, 수정중).
