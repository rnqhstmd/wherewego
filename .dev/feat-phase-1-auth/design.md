# 설계서: Phase 1 — 카카오 OAuth2 + JWT 인증 (auth 도메인)

## 설계 규모
**대형** — 신규 도메인(`auth`, `user`) + Spring Security 신규 도입 + 4개 신규 API + 신규 의존성 3종.

## 배경 및 목적
- Phase 0 인프라 위에 첫 비즈니스 기능인 인증을 구축한다.
- DB `users` 테이블(V001), `JwtProperties`, `KakaoApiProperties`가 이미 준비됨.
- Vercel(FE) ↔ EC2(BE) Cross-domain 구성. 토큰은 httpOnly 쿠키(`SameSite=None; Secure`)로 전달.
- 목표: 카카오 로그인으로 진입, 만료 시 무중단 재발급, `client_id` FE 비노출.

## 프론트엔드 계약 (Q1)

본 백엔드 설계는 다음 프론트엔드 동작을 **계약으로 가정**한다:

1. **Refresh 직렬화**: 프론트엔드는 `access_token` 만료(401) 감지 시 `/api/v1/auth/token/refresh` 호출을 **단일 in-flight + 대기 큐** 패턴으로 직렬화한다. 다수의 동시 401이 발생해도 refresh 요청은 1회만 전송하고, 나머지 요청은 refresh 완료 후 신규 쿠키로 재시도한다.
2. **계약 위반 시 동작**: 위 계약이 깨져 refresh 요청이 동시에 다수 도달하면, 백엔드는 BR-3 단순 검사로 일부 요청에서 강제 로그아웃이 발생할 수 있다. 사용자는 재로그인으로 정상 복구된다.
3. **CORS**: 모든 API 요청은 `credentials: 'include'` (axios `withCredentials: true`)로 전송한다.
4. **로그아웃 멱등**: `/api/v1/auth/logout`은 access_token 유무·만료 여부와 무관하게 항상 `Set-Cookie Max-Age=0` 2건을 반환한다.
5. **OAuth state 파라미터 책임 (phase-review Q3 결정)**: 백엔드는 `state` 파라미터를 생성하지 않는다. 프론트엔드 SPA가 카카오 인가 페이지로 이동할 때 자체적으로 `state` (예: UUID + SPA 세션 storage)를 생성하여 인가 URL에 부착하고, 콜백 수신 시 검증한다. 백엔드 `KakaoLoginUrlGenerator.generate()`는 base URL만 제공한다.

## 데이터 모델

### `users` 테이블 (V001 기존)
- 컬럼 변경 없음. 마이그레이션 V002 불필요.
- `refresh_token TEXT`에 **JWT 원본이 아닌 SHA-256 해시(hex)** 저장 (Q3).
- V001 코멘트 갱신: `refresh_token TEXT, -- JWT Refresh Token의 SHA-256 해시 hex (14일 TTL)` (line 31).

### `UserModel` (신규 엔티티, `domain/user/`)
- `BaseEntity` 상속 (id, createdAt, updatedAt, deletedAt 자동)
- 컬럼: `kakaoUserId: Long` (UNIQUE), `nickname: String` (NOT NULL, 100), `profileImageUrl: String` (nullable), `refreshTokenHash: String` (nullable, SHA-256 hex)
- 도메인 메서드:
  - `static create(kakaoUserId, nickname, profileImageUrl)`
  - `updateProfile(nickname, profileImageUrl)` (FR-3)
  - `replaceRefreshTokenHash(String hash)` (Rotation)
  - `clearRefreshTokenHash()` (Logout)
  - `matchesRefreshTokenHash(String hash): boolean` (BR-3)
  - `isActive(): boolean` → `deletedAt == null` (BR-6/Q4)

## JWT 모듈 (Q2 typ claim)

### `JwtTokenProvider` (`domain/auth/jwt/`)
- 생성자: `(JwtProperties props)`
- HS256, `Keys.hmacShaKeyFor(props.secret().getBytes(UTF_8))`
- 시그니처:
  - `String issueAccessToken(Long userId)` → payload `{sub, typ: "access", iat, exp}`
  - `String issueRefreshToken(Long userId)` → payload `{sub, typ: "refresh", iat, exp}`
  - `JwtValidationResult parseAccessToken(String token)` — typ != "access" → `INVALID_TYPE`
  - `JwtValidationResult parseRefreshToken(String token)` — typ != "refresh" → `INVALID_TYPE`

### `JwtValidationResult` (sealed interface)
- `record Valid(Long userId, Instant expiresAt)`
- `enum Invalid { EXPIRED, INVALID_SIGNATURE, INVALID_TYPE, MALFORMED }`

### `RefreshTokenHasher` (Q3)
- `static String sha256Hex(String rawToken)` — `MessageDigest.getInstance("SHA-256")` → 64자 hex

## Spring Security

### `SecurityConfig` (`config/security/`)
- `@EnableWebSecurity`
- `csrf.disable()`, `cors(corsSource)`, `sessionManagement(STATELESS)`
- permitAll: `/api/v1/auth/**`, `/actuator/health`, `/swagger-ui/**`, `/v3/api-docs/**`, `/api/v1/examples/**` (Q6 임시)
- `addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)`
- `exceptionHandling.authenticationEntryPoint(jsonAuthEntryPoint)`

### `JwtAuthenticationFilter` (`config/security/`)
- `extends OncePerRequestFilter`
- 생성자: `(JwtTokenProvider)` — **DB 미조회 (Q4)**
- 흐름: 쿠키에서 `access_token` 추출 → `parseAccessToken` → Valid면 SecurityContext에 `UsernamePasswordAuthenticationToken(userId, null, List.of())` 주입. Invalid면 context 비우고 통과 (EntryPoint가 401).
- **탈퇴자 잔존 리스크**: access_token 유효 동안(최대 1h) 탈퇴 사용자도 보호 API 통과. Refresh/Login 시점 차단으로 1h 내 무효화.

### `JwtAuthenticationEntryPoint`
- 401 JSON 응답 (`ApiResponse.fail("AUTH_UNAUTHORIZED", ...)`)

### `@AuthUser` + `AuthUserArgumentResolver`
- Controller에서 `@AuthUser Long userId`로 인증 사용자 ID 주입

## 카카오 클라이언트 (Q5: RestClient 인라인)

### `KakaoOAuthClient` (`infrastructure/auth/kakao/`)
- 생성자: `(KakaoApiProperties, @Value("${kakao.oauth.token-base-url:https://kauth.kakao.com}") String tokenBaseUrl, @Value("${kakao.oauth.user-base-url:https://kapi.kakao.com}") String userBaseUrl)`
- 내부에서 `RestClient.builder().baseUrl(...).build()` 인라인 생성
- 시그니처:
  - `KakaoTokenResponse exchangeCodeForToken(String code)` — POST `/oauth/token` form-urlencoded
  - `KakaoUserInfoResponse fetchUserInfo(String kakaoAccessToken)` — GET `/v2/user/me` Bearer
- 예외 매핑: HTTP 4xx/5xx → `CoreException(AUTH_KAKAO_API_FAILED)` (502 Bad Gateway, QE-1)
- **테스트**: `@TestConfiguration`이 WireMock URL 주입한 빈을 `@Primary`로 등록 대체

### `KakaoLoginUrlGenerator` (`domain/auth/kakao/`)
- `@Component`, `(KakaoApiProperties)` 주입
- `String generate()` → `https://kauth.kakao.com/oauth/authorize?response_type=code&client_id=...&redirect_uri=...` (URLEncoder.encode)

## 인증 서비스 (Q5: Facade 제거)

### `AuthService` (`domain/auth/`)
- 의존: `KakaoOAuthClient`, `UserRepository`, `JwtTokenProvider`, `RefreshTokenHasher`, `KakaoLoginUrlGenerator`
- 시그니처:
  - `KakaoLoginUrlInfo getKakaoLoginUrl()` (FR-1)
  - `AuthResultInfo loginWithKakao(String code)` (FR-2, FR-3, BR-1, BR-6)
  - `AuthResultInfo refreshTokens(String refreshTokenRaw)` (FR-6, BR-3, BR-6)
  - `void logout(Long userId)` (FR-7, 멱등)

### `loginWithKakao` 흐름
1. `kakaoClient.exchangeCodeForToken(code)` → kakao access token
2. `kakaoClient.fetchUserInfo(kakaoAccess)` → `(kakaoUserId, nickname, profileImage)`
3. `userRepo.findByKakaoUserId(kakaoUserId)`:
   - 존재 + `isActive()` → `updateProfile` (AC-3)
   - 존재 + `!isActive()` → `CoreException(AUTH_USER_DEACTIVATED)` (AC-13)
   - 미존재 → `UserModel.create` 저장 (AC-2)
4. `accessRaw = jwt.issueAccessToken(userId)`, `refreshRaw = jwt.issueRefreshToken(userId)`
5. `user.replaceRefreshTokenHash(hasher.sha256Hex(refreshRaw))` → save
6. return `AuthResultInfo(userId, nickname, profileImageUrl, accessRaw, refreshRaw)`

### `refreshTokens` 흐름
1. `jwt.parseRefreshToken(raw)`:
   - `Invalid(*)` → `CoreException(AUTH_REFRESH_TOKEN_INVALID)` (Q2 typ 포함)
   - `Valid(userId, _)` → 계속
2. `userRepo.findById(userId).orElseThrow(AUTH_USER_NOT_FOUND)`
3. `if (!user.isActive())` → `AUTH_USER_DEACTIVATED` (Q4)
4. `if (!user.matchesRefreshTokenHash(hasher.sha256Hex(raw)))` → `AUTH_REFRESH_TOKEN_INVALID` (AC-8)
5. 신규 access/refresh 발급 + 해시 교체 (Rotation, AC-7)

### `logout` (멱등)
- `userRepo.findById(userId).ifPresent(u -> u.clearRefreshTokenHash())` (AC-9)

## 컨트롤러 (Q5: Service 직접 주입)

### `AuthV1Controller` (`interfaces/api/auth/`)
- `@RequestMapping("/api/v1/auth")`, 의존: `AuthService`, `AuthCookieFactory`

| Method | Path | 시그니처 |
|--------|------|---------|
| GET | `/kakao/login-url` | `ApiResponse<LoginUrlResponse> getLoginUrl()` |
| POST | `/kakao/callback` | `ResponseEntity<ApiResponse<UserResponse>> kakaoCallback(@RequestBody @Valid KakaoCallbackRequest)` |
| POST | `/token/refresh` | `ResponseEntity<ApiResponse<Object>> refresh(@CookieValue("refresh_token") String)` |
| POST | `/logout` | `ResponseEntity<ApiResponse<Object>> logout(@CookieValue(value="access_token", required=false) String)` |

- `kakaoCallback` 응답: `ResponseEntity.ok().header(SET_COOKIE, accessCookie.toString()).header(SET_COOKIE, refreshCookie.toString()).body(ApiResponse.success(UserResponse.from(result)))`
- `refresh` `refreshToken == null/blank` → `AUTH_REFRESH_TOKEN_INVALID`
- `logout`: access_token 파싱 시도 → Valid면 `authService.logout(userId)`, 아니면 무시. 항상 만료 쿠키 2건 응답.

### `AuthV1Dto` (record container)
- `KakaoCallbackRequest(@NotBlank String code)` (AC-14)
- `LoginUrlResponse(String loginUrl)`
- `UserResponse(Long id, String nickname, String profileImageUrl)` + `from(AuthResultInfo)`

## 쿠키 / CORS / Properties (Q5: WebSecurityProperties 통합)

### `WebSecurityProperties` (`config/env/`)
```java
@Validated
@ConfigurationProperties(prefix = "web-security")
public record WebSecurityProperties(@Valid Cookie cookie, @Valid Cors cors) {
    public record Cookie(boolean secure, String domain, @NotBlank String sameSite) {}
    public record Cors(@NotEmpty List<@NotBlank String> allowedOrigins) {}
}
```

### `application.yml`
```yaml
web-security:
  cookie:
    secure: ${WEB_SECURITY_COOKIE_SECURE:true}
    domain: ${WEB_SECURITY_COOKIE_DOMAIN:}
    same-site: ${WEB_SECURITY_COOKIE_SAME_SITE:None}
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000}

---
spring.config.activate.on-profile: local, test
web-security:
  cookie:
    secure: false
    same-site: Lax
    domain: ""
```

### `AuthCookieFactory` (`config/security/`)
- 시그니처:
  - `ResponseCookie accessCookie(String token)` — `Max-Age = jwt.accessTtlSeconds()` (3600)
  - `ResponseCookie refreshCookie(String token)` — `Max-Age = jwt.refreshTtlSeconds()` (1,209,600)
  - `ResponseCookie expiredAccessCookie()` — `Max-Age = 0`
  - `ResponseCookie expiredRefreshCookie()` — `Max-Age = 0`
- 공통: `httpOnly(true).secure(webSec.cookie().secure()).sameSite(webSec.cookie().sameSite()).path("/").domain(...)`

### `CorsConfig` (`config/security/`)
- `CorsConfigurationSource` 빈
- `webSec.cors().allowedOrigins()` + `allowCredentials=true` + methods=`GET,POST,PUT,DELETE,OPTIONS` + headers=`Authorization, Content-Type`

## 에러 코드 (`ErrorType.java` 추가)

```java
AUTH_UNAUTHORIZED              (HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHORIZED",              "인증이 필요합니다."),
AUTH_REFRESH_TOKEN_INVALID     (HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_TOKEN_INVALID",     "유효하지 않은 refresh token 입니다."),
AUTH_KAKAO_API_FAILED          (HttpStatus.BAD_GATEWAY,  "AUTH_KAKAO_API_FAILED",          "카카오 로그인을 일시적으로 사용할 수 없습니다."),
AUTH_USER_DEACTIVATED          (HttpStatus.UNAUTHORIZED, "AUTH_USER_DEACTIVATED",          "탈퇴한 사용자입니다."),
AUTH_USER_NOT_FOUND            (HttpStatus.UNAUTHORIZED, "AUTH_USER_NOT_FOUND",            "사용자를 찾을 수 없습니다."),
```

## 변경 파일 목록

### 신규 (~20개)

| # | 경로 | 역할 |
|---|------|------|
| 1 | `config/env/WebSecurityProperties.java` | Cookie + Cors 통합 (Q5) |
| 2 | `config/security/SecurityConfig.java` | SecurityFilterChain |
| 3 | `config/security/JwtAuthenticationFilter.java` | 쿠키 access_token 검증 |
| 4 | `config/security/JwtAuthenticationEntryPoint.java` | 401 JSON |
| 5 | `config/security/CorsConfig.java` | CorsConfigurationSource |
| 6 | `config/security/AuthCookieFactory.java` | ResponseCookie 생성 |
| 7 | `config/security/AuthUser.java` + `AuthUserArgumentResolver.java` | `@AuthUser` |
| 8 | `config/security/WebMvcConfig.java` | ArgumentResolver 등록 |
| 9 | `domain/auth/jwt/JwtTokenProvider.java` | 발급/파싱 (typ claim) |
| 10 | `domain/auth/jwt/JwtValidationResult.java` | sealed Valid/Invalid |
| 11 | `domain/auth/jwt/RefreshTokenHasher.java` | SHA-256 해시 (Q3) |
| 12 | `domain/auth/kakao/KakaoLoginUrlGenerator.java` | 인가 URL 생성 |
| 13 | `domain/auth/AuthService.java` | 로그인/리프레시/로그아웃 |
| 14 | `domain/user/UserModel.java` | users 엔티티 |
| 15 | `domain/user/UserRepository.java` | 인터페이스 |
| 16 | `infrastructure/user/UserRepositoryImpl.java` + `UserJpaRepository.java` | JPA 구현 |
| 17 | `infrastructure/auth/kakao/KakaoOAuthClient.java` (RestClient 인라인) | 카카오 API |
| 18 | `infrastructure/auth/kakao/KakaoTokenResponse.java` + `KakaoUserInfoResponse.java` | record DTO |
| 19 | `application/auth/AuthResultInfo.java` + `KakaoLoginUrlInfo.java` | 결과 record (Facade 없음) |
| 20 | `interfaces/api/auth/AuthV1Controller.java` + `AuthV1ApiSpec.java` + `AuthV1Dto.java` | API |

### 수정 (5개)
1. `support/error/ErrorType.java` — AUTH_* 5건 추가
2. `apps/wherewego-api/src/main/resources/application.yml` — `web-security.*` 블록
3. `backend/.env.example` — `WEB_SECURITY_COOKIE_*`, `CORS_ALLOWED_ORIGINS` 추가
4. `apps/wherewego-api/build.gradle.kts` — `spring-boot-starter-security`, jjwt 3종, WireMock, spring-security-test 추가
5. `apps/wherewego-api/src/main/resources/db/migration/V001__init_schema.sql:31` — refresh_token 코멘트만 갱신 (SQL 본문 변경 없음)

## 의존성 추가

```kotlin
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("io.jsonwebtoken:jjwt-api:0.12.6")
runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
testImplementation("org.springframework.security:spring-security-test")
testImplementation("org.wiremock:wiremock-standalone:3.9.1")
```

## 구현 순서 (5 Wave)

**Wave 1 — 기반 인프라 (병렬)**
1. WebSecurityProperties + application.yml + .env.example
2. ErrorType AUTH_* 추가
3. build.gradle.kts 의존성 추가
4. V001 코멘트 갱신 + architecture.md 갱신

**Wave 2 — JWT + User 도메인 (Wave 1 의존)**
5. JwtValidationResult (sealed)
6. JwtTokenProvider (typ claim)
7. RefreshTokenHasher
8. UserModel
9. UserRepository + UserRepositoryImpl + UserJpaRepository

**Wave 3 — 카카오 + AuthService**
10. KakaoLoginUrlGenerator
11. KakaoOAuthClient + KakaoTokenResponse/KakaoUserInfoResponse (RestClient 인라인)
12. AuthResultInfo + KakaoLoginUrlInfo (application record)
13. AuthService

**Wave 4 — Security 필터 + Controller**
14. JwtAuthenticationEntryPoint
15. JwtAuthenticationFilter
16. CorsConfig
17. AuthCookieFactory
18. SecurityConfig (Q6: /api/v1/examples permitAll 포함)
19. AuthV1Controller + AuthV1ApiSpec + AuthV1Dto
20. @AuthUser + AuthUserArgumentResolver + WebMvcConfig

**Wave 5 — 테스트**
21. Unit: JwtTokenProviderTest, RefreshTokenHasherTest, UserModelTest, AuthServiceTest (Mockito)
22. Integration: AuthV1Controller MockMvc + WireMock + Testcontainers
23. Security: 보호 엔드포인트 401/200 시나리오
24. CORS: preflight 검증

## 테스트 전략

### 단위 (Mockito)
- `JwtTokenProviderTest`: typ claim 교차 검증 (access를 parseRefreshToken에 → `INVALID_TYPE`)
- `RefreshTokenHasherTest`: 동일 입력 → 동일 hex
- `UserModelTest`: 도메인 메서드 (rotation/clear/match/isActive)
- `AuthServiceTest`: 모든 분기 (신규/재로그인/탈퇴자/refresh rotation/잘못된 hash/잘못된 typ)

### 통합 (Testcontainers + WireMock)
- `AuthV1ControllerIT`:
  - WireMock으로 `/oauth/token`, `/v2/user/me` stub
  - `@TestConfiguration`이 WireMock URL 베이스 `KakaoOAuthClient`를 `@Primary` 등록
  - AC-1, AC-2, AC-3, AC-7~10, AC-13, AC-14 검증
- `AuthSecurityIT`:
  - `/api/v1/examples/{id}` permitAll 검증 (Q6)
  - 유효/만료/변조/typ 불일치 access_token → 200/401 (AC-4, AC-5)
  - CORS preflight (AC-15, AC-16)

### 트랜잭션 격리
- `DatabaseCleanUp`은 MySQL 구문 비호환 → 본 Phase는 `@Transactional` rollback 사용 (후속 작업 #3에서 Postgres 호환 교체)

## AC 추적표

| AC | 충족 컴포넌트 |
|----|--------------|
| AC-1 | `AuthCookieFactory.accessCookie/refreshCookie` + `AuthV1Controller.kakaoCallback` |
| AC-2 | `AuthService.loginWithKakao` 미존재 → `UserModel.create` |
| AC-3 | `AuthService.loginWithKakao` 존재 → `UserModel.updateProfile` |
| AC-4 | `JwtAuthenticationFilter` Valid 분기 + SecurityContext |
| AC-5 | `JwtAuthenticationFilter` Invalid + `JwtAuthenticationEntryPoint` 401 |
| AC-6 | `KakaoLoginUrlGenerator.generate` (FE에 URL만 노출) |
| AC-7 | `AuthService.refreshTokens` Rotation + `replaceRefreshTokenHash` |
| AC-8 | `matchesRefreshTokenHash` false → `AUTH_REFRESH_TOKEN_INVALID` |
| AC-9 | `AuthV1Controller.logout` 멱등 + `clearRefreshTokenHash` + `expiredCookies` |
| AC-10 | `KakaoOAuthClient` 4xx/5xx 매핑 → `AUTH_KAKAO_API_FAILED` (502) |
| AC-11 | `AuthCookieFactory.accessCookie.maxAge=3600` |
| AC-12 | `AuthCookieFactory.refreshCookie.maxAge=1209600` |
| **AC-13** | **`AuthService.loginWithKakao/refreshTokens`의 `isActive()` 검사 (Q4 DB 시점만)** |
| AC-14 | `AuthV1Dto.KakaoCallbackRequest @NotBlank` + `@Valid` |
| AC-15 | `CorsConfig.allowCredentials=true` + filter 쿠키 추출 |
| AC-16 | `CorsConfig.allowedOrigins` 환경변수 제한 |

### MUST-ADDRESS 매핑
- MUST-1 (Race condition) → "프론트엔드 계약" 섹션 / 백엔드는 BR-3 단순 검사
- MUST-2 (토큰 혼용) → JWT `typ` claim
- MUST-3 (평문 저장) → `RefreshTokenHasher` SHA-256
- MUST-4 (deleted_at) → Refresh + Login 시점 DB 검증, Filter 미조회
- MUST-5 (파일 과다) → 20개 / 5 Wave

## 후속 작업 (TODO)

1. ~~`/api/v1/examples/**` 인증 적용~~ — **완료**: phase-review에서 example 도메인 완전 제거
2. **Redis 블랙리스트** — 탈퇴/강제 로그아웃 즉시 반영 필요 시 도입
3. **DatabaseCleanUp Postgres 호환** — MySQL `SET FOREIGN_KEY_CHECKS` → Postgres `TRUNCATE CASCADE`
4. **architecture.md 보강** — refresh_token 해시 정책, typ claim, FE 직렬화 계약, deleted_at 잔존 리스크 명시
5. **CORS methods/headers 외부화** — 운영 시 properties로
6. **카카오 disconnect 콜백** — 사용자가 카카오에서 연결 해제 시 처리
7. **탈퇴자 RT hash 즉시 클리어 (Q2)** — 탈퇴 API 구현 시 `UserModel.delete()` 또는 별도 도메인 이벤트로 `refresh_token` 컬럼을 즉시 NULL로 초기화. 현재는 `isActive()` 검사로 재발급 차단만 처리.
8. **OAuth state 강화 옵션 (Q3)** — 현재 FE 책임. BE에서 추가 방어가 필요해지면 Phase 2 Redis 도입 후 state 발급/검증을 BE에서 처리.
9. **BaseEntity.id 초기값 정리 (ZT HIGH-1)** — `private final Long id = 0L`로 인해 JPA `save()`가 신규 엔티티를 `merge`로 처리 가능성. Phase 0 모듈 공통이므로 별도 작업 필요. `Persistable<Long>` 구현 또는 `id = null` 초기화 검토.
10. **dev/prod cookie.secure 명시 (ZT MEDIUM-8)** — `application.yml`의 dev/prod 프로파일에 `web-security.cookie.secure: true` 하드코딩하여 환경변수 오설정으로 인한 Secure 누락 방지.
11. **CoreException 로그 stacktrace 제거 (ZT MEDIUM-7)** — `ApiControllerAdvice.handle(CoreException)`이 stacktrace를 출력. 운영 로그 위생 위해 message만 출력으로 변경.
12. **AC-4 보호 엔드포인트 통합 테스트** — Phase 2+에서 첫 보호 엔드포인트가 등장하면 유효 access_token 200 응답 케이스 추가. AC-5/15/16은 Phase 1에 추가 완료.

## 확장 고려

- **다중 소셜 로그인**: `KakaoOAuthClient` → `OAuthClient` 추상화 + `users.provider` 컬럼
- **세션 무효화 일괄**: JWT secret rotation (현재 무중단 미지원)
- **Refresh 동시성 강화**: 모바일 다양화 시 grace window 또는 분산락
