# 자기점검 결과 (qa-manager)

## CERTAIN Critical (자동 수정 완료)

- [Critical/완료] `AuthService.java:102` — logout의 `clearRefreshTokenHash` 호출 후 `userRepository.save()` 누락 (다른 메서드와 일관성 위반)
  - 수정: `ifPresent` 람다 내부에 `userRepository.save(user)` 명시 호출 추가
  - 검증: AuthServiceTest 11건 PASS

## Warning/Info (phase-review로 이월)

- [Warning] `AuthService.java:55` — 신규 사용자 경로에서 `orElseGet`이 이미 영속화하는데 이후 `replaceRefreshTokenHash` + `save`로 추가 UPDATE 발생 (INSERT + UPDATE 2 쿼리). 성능 영향은 미미하지만 `UserModel.create()`에 refresh hash를 초기 파라미터로 받거나 단일 save로 통합 가능
- [Warning] `KakaoOAuthClient.java:25-27` — `@Value`로 주입되는 `kakao.oauth.token-base-url`, `user-base-url` 키가 application.yml에 미정의 (기본값만 존재). `.env.example`에도 미등재. 테스트 WireMock 주입 외 운영에선 환경 분리 어려움
- [Warning] `CorsConfig.java:23` — `setAllowedHeaders` 화이트리스트에 `Cookie` 미포함. `withCredentials=true`로 자동 전송되지만 일부 브라우저/프록시에서 헤더 명시 시 차단 가능. `PATCH` 메서드도 누락
- [Info] `AuthService.java:47-55` — 기존 사용자 경로의 `userRepository.save(user)` 명시 호출은 dirty checking 환경에서 중복. 일관성 측면에서는 의도적이라 보이나 의미 명시 필요
- [Info] `AuthV1Controller.java:49-51` — `refreshToken` 엔드포인트 응답 바디가 `ApiResponse<Object>`로 빈 success. springdoc 스펙 문서에서 응답 타입이 Object로 노출됨

## QUESTION (phase-review에서 사용자 확인)

- [QUESTION-1] `SecurityConfig.java:33-39` — `/api/v1/auth/**` 전체 permitAll. logout/refresh도 인증 없이 접근 가능. 의도 확인 필요.
- [QUESTION-2] `AuthService.refreshTokens` — 탈퇴(`deleted_at`) 시 DB의 `refresh_token_hash`를 NULL로 즉시 초기화하는 흐름이 본 Phase 범위에 포함되지 않음. 탈퇴 플로우는 후속 Phase 예정인지 확인 필요.
- [QUESTION-3] `KakaoLoginUrlGenerator.generate()` — OAuth2 `state` 파라미터(CSRF 방어) 미생성. FE 책임인지, BE에서 생성/검증해야 하는지 결정 필요.
- [QUESTION-4] `AuthV1Controller.logout` — `@AuthUser` 대신 `@CookieValue access_token` 직접 파싱. permitAll로 두면서 멱등 처리 필요한 이유로 보이나, `authenticated()` 이동 + `@AuthUser` 일관성 사용 옵션도 가능.

## AC 충족 요약 (수정 후)

- AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8, **AC-9 (수정 완료)**, AC-10, AC-11, AC-12, AC-13, AC-14, AC-15, AC-16 — **16/16 충족**

## 환경 이슈 (참고)

- 통합 테스트(`AuthV1ControllerIntegrationTest`) 9건은 Docker 데몬 미기동으로 실행 불가. 코드 로직 문제 아님. phase-review 단계 또는 Docker 기동 환경에서 재실행 필요.
