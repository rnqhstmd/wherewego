## 코드 맵: P1 — 백엔드 인증 확장 (Bearer + Kakao/Apple 네이티브 + refresh + oauth 일반화)

> 기존 인증: **Kakao 단독 + 인가코드 직접 처리**(Spring Security OAuth2 Client 미사용). JWT Stateless + httpOnly 쿠키(access 1h, refresh 14d SHA-256 해시). P1은 전부 **additive**(웹 무중단).

### 핵심 파일 (수정/신규 대상)
- backend/apps/wherewego-api/src/main/java/com/wherewego/config/security/JwtAuthenticationFilter.java:22 → JWT 인증 필터. **현재 쿠키만 읽음** → Authorization: Bearer 헤더 분기 추가(헤더 우선, 없으면 쿠키)
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/user/UserModel.java:17 → users 엔티티. `kakao_user_id` UNIQUE NOT NULL → **oauth_provider + oauth_id 일반화**(기존 Kakao 매핑 보존) ★최난도
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/auth/AuthV1Controller.java → 인증 REST 컨트롤러. `POST /auth/kakao/native`, `POST /auth/apple/native` 신규
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/auth/AuthService.java → 인증 핵심 서비스. 네이티브 로그인 find-or-create + JWT 발급 확장
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/auth/jwt/JwtTokenProvider.java → JWT 발급/검증(access/refresh, typ/jti claim)

### 참조 파일
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/auth/kakao/KakaoOAuthClient.java → Kakao 토큰/유저정보 RestClient 직접 호출. 네이티브는 access token 검증 경로 재사용/추가
- backend/apps/wherewego-api/src/main/java/com/wherewego/config/security/AuthCookieFactory.java → 쿠키 발급(웹 병행 유지)
- backend/apps/wherewego-api/src/main/java/com/wherewego/config/security/SecurityConfig.java → 시큐리티 필터 체인/permitAll 경로
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/auth/jwt/RefreshTokenHasher.java → refresh 토큰 SHA-256 해시
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/auth/AuthV1Dto.java → 인증 요청/응답 DTO(네이티브 로그인 DTO 추가). AuthV1ApiSpec.java 동반
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/user/UserJpaRepository.java + UserRepositoryImpl.java → User 조회(oauth_provider+oauth_id 조회 추가)
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/auth/UserLoginPersistence.java → 로그인 시 사용자 영속화/refresh 저장

### 설정
- backend/apps/wherewego-api/src/main/java/com/wherewego/config/env/JwtProperties.java → JWT 설정(secret/TTL)
- backend/apps/wherewego-api/src/main/java/com/wherewego/config/env/KakaoApiProperties.java → Kakao API 설정. (Apple 검증용 신규 properties 필요 가능)
- backend/apps/wherewego-api/src/main/resources/db/migration/ → Flyway. 최신 V013 → **V014__generalize_oauth_provider.sql** 신규

### 탐색 추가 항목 (requirements 누적)
- domain/auth/UserLoginPersistence.java → Kakao find-or-create + JWT 발급 DB 쓰기. Apple 네이티브 로그인 동일 패턴 참조점
- support/error/ErrorType.java → AUTH 에러 코드 목록. `AUTH_APPLE_TOKEN_INVALID` 등 신규 추가
- application/auth/AuthResultInfo.java → 로그인 결과 DTO. 앱용 응답 `expiresIn` 추가 검토
- config/env/AppleAuthProperties.java(신규) → Apple 번들ID(aud)/JWKS URL 외부화. KakaoApiProperties 패턴 참조

### 탐색 추가 항목 (design 누적)
- config/security/AuthCookieFactory.java:13 → ACCESS_TOKEN/REFRESH_TOKEN 쿠키 상수. 네이티브는 미호출(Set-Cookie 없음)
- infrastructure/auth/kakao/KakaoUserInfoResponse.java:26 → resolvedNickname()/resolvedProfileImageUrl() 재사용
- domain/auth/jwt/RefreshTokenHasher.java:15 → sha256Hex(String). Apple nonce SHA-256 비교 재사용(BR-5)
- WherewegoApiApplication.java:18 → @ConfigurationPropertiesScan(AppleAuthProperties 자동 등록)
- test .../migration/FlywayMigrationTest.java:42,214 → users 컬럼 기대치·insertUser 헬퍼(V014 반영 수정)
- test .../interfaces/api/auth/AuthV1ControllerIntegrationTest.java:51-60 → WireMock+@DynamicPropertySource(Apple JWKS stub 적용)
- build.gradle.kts:53-56 → jjwt 0.12.6 존재(Apple JWKS 검증 가능, 신규 의존성 불필요)

### 구현 산출 (implement)
- domain/auth/AppleLoginCommand.java(신규) → Apple 검증 입력 커맨드(DTO→domain). AppleNativeLoginRequest.toCommand()
- infrastructure/auth/apple/AppleIdentityTokenVerifier.java(신규,134L) → nimbus JWKSourceBuilder+DefaultJWTProcessor+DefaultResourceRetriever(3s). QE-2 KeySourceException cause 검사
- nimbus-jose-jwt:9.40 (build.gradle.kts) — BOM 미관리라 버전 명시
- @Recover 별도빈 분리 안 함(RetryIT로 매칭 검증). provider별 toFriendlyError
- test/resources/application.yml → apple 더미값 추가(테스트 yml이 운영 yml 완전 대체)
- test .../migration/FlywayMigrationTest.java → 베이스 선행 실패(V008/010/012/013) 동반 수정 + V014 검증

### 미확정/조사 필요
- refresh 엔드포인트: 기존 `/api/v1/auth/token/refresh` 존재(FE 직렬화 계약). roadmap의 `/api/v1/auth/refresh`와 reconcile 필요 — 신규 경로 추가 vs 기존 재사용.
- Apple identityToken JWKS 검증: 신규 인프라(공개키 캐시/검증). nimbus-jose 등 의존성 확인 필요.
