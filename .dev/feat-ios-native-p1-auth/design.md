# 설계서(확정): P1 — 백엔드 인증 확장 (Bearer + Kakao/Apple 네이티브 + refresh + oauth 일반화)

> architect 초안 + design-critic(MUST 4건) + 사용자 결정 반영 확정본. 베이스 develop. 전부 additive(웹 무중단).
> 초안: design.draft.md. PRD: prd.md.

## 설계 규모
**대형** — 신규 엔드포인트 3, Apple JWKS 검증 인프라(nimbus), users 스키마 변경+백필, 보안 직결 토큰 검증.

## 확정 결정 (Q&A + critic 반영)
| # | 결정 |
|---|------|
| Apple 검증 라이브러리 | **nimbus-jose-jwt 추가** (RemoteJWKSet 캐싱·로테이션·RSA 내장). jjwt는 대칭키 전용 유지 |
| 빈 Bearer 헤더 | **쿠키 폴백** (`Bearer ` 빈값 → 쿠키 시도, 웹 회귀 0) |
| OauthProvider enum 위치 | `domain/user/OauthProvider.java` |
| kakao_user_id | **DROP NOT NULL** (컬럼·UNIQUE 유지. Apple은 NULL, PG UNIQUE NULL distinct) |
| Apple 로그인 동시성 | Kakao와 동일 Bulkhead + `@Retryable` 적용 |
| expiresIn 산출 | 컨트롤러가 `JwtProperties` 주입해 조립 (AuthTokenInfo 신규 안 만듦) |
| @Recover 충돌(MUST#1) | 공통 토큰발급 private 헬퍼 추출 + 신규 upsert `@Recover`는 인자타입(OauthProvider,String,NativeLoginCommand) 명확화. 모호성 시 별도 빈 분리. **PoC 검증 필수** |
| Apple nonce(MUST#3) | **소문자 hex SHA-256 계약 고정**. `sha256Hex(rawNonce)==claims.nonce`. 설계·Swagger·P3 클라이언트 계약에 명시 |
| AC-15 탈퇴자(MUST#4) | soft-delete가 (provider,oauth_id) UNIQUE 행 유지 → 조회됨 → 401. **백필된 탈퇴행 401 통합테스트 추가** |
| aud 검증 | **단수**(PRD AC-12 일치, 공격표면 최소). 다중은 실제 필요 시 확장 |

## 변경 범위

### 신규 파일 (8)
- `config/env/AppleAuthProperties.java` — `@ConfigurationProperties("apple")` record: `audience`(단수 String, 번들ID), `issuer`, `jwksUrl`, `jwksTtlSeconds`. @ConfigurationPropertiesScan 자동등록
- `infrastructure/auth/apple/AppleIdentityTokenVerifier.java` — nimbus `JWKSource`(캐싱) + `DefaultJWTProcessor`(RS256) 서명·exp·iss·aud 검증 + nonce 검증 → `AppleTokenClaims`
- `infrastructure/auth/apple/AppleTokenClaims.java` — record(sub, email)
- `domain/user/OauthProvider.java` — enum `{ KAKAO, APPLE }`
- `domain/auth/NativeLoginCommand.java` — find-or-create 입력 캐리어(provider, oauthId, nickname, profileImageUrl, email) + `toNewUser()` (provider별 팩토리 분기)
- `resources/db/migration/V014__generalize_oauth_provider.sql`
- `test .../infrastructure/auth/apple/AppleIdentityTokenVerifierTest.java`
- `test .../config/security/JwtAuthenticationFilterTest.java`

### 수정 파일
- `build.gradle.kts`(apps/wherewego-api) — **nimbus-jose-jwt 의존성 추가** (`com.nimbusds:nimbus-jose-jwt`)
- `config/security/JwtAuthenticationFilter.java` — Bearer 헤더 분기(빈 Bearer→쿠키 폴백). 검증 무변경
- `interfaces/api/auth/AuthV1Controller.java` — kakaoNative/appleNative/refresh 3핸들러 (Set-Cookie 미설정)
- `interfaces/api/auth/AuthV1ApiSpec.java` — 3 시그니처 + @Operation (nonce 계약 문서화)
- `interfaces/api/auth/AuthV1Dto.java` — 요청/응답 record
- `domain/auth/AuthService.java` — loginWithKakaoNative/loginWithApple/refresh(재사용) + JwtProperties 주입 + resolveAppleNickname
- `domain/auth/UserLoginPersistence.java` — upsertByOauthAndIssueTokens + 공통 헬퍼 + @Recover
- `domain/user/UserModel.java` — oauthProvider/oauthId/email 필드 + Apple 팩토리 + guard 보강 + kakaoUserId @Column(nullable) 완화
- `domain/user/UserRepository.java` + `infrastructure/user/UserRepositoryImpl.java` + `infrastructure/user/UserJpaRepository.java` — findByOauthProviderAndOauthId
- `support/error/ErrorType.java` — AUTH_APPLE_TOKEN_INVALID(401), AUTH_APPLE_JWKS_UNAVAILABLE(502)
- `infrastructure/auth/LoginRetryListener.java` — (신규 메서드에 listener 적용 시) 동기화
- `resources/application.yml` — `apple:` 블록
- `test .../migration/FlywayMigrationTest.java` — 컬럼 기대치 + 백필/UNIQUE/탈퇴행 검증, insertUser 헬퍼
- (SecurityConfig 변경 불필요 — /api/v1/auth/** 이미 permitAll)

## 상세 설계

### 1. JwtAuthenticationFilter — Bearer 분기 (FR-1, BR-1/2, AC-1~5)
추출 단계만 분기. 검증/SecurityContext(기존 41~52행) 무수정.
```java
private static final String BEARER_PREFIX = "Bearer ";
String token = extractToken(request); // 기존 34행 교체
private String extractToken(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith(BEARER_PREFIX)) {
        String value = header.substring(BEARER_PREFIX.length()).trim();
        if (!value.isEmpty()) return value;     // 헤더 우선
        // 빈 Bearer → 쿠키 폴백 (결정: 웹 회귀 0)
    }
    return extractAccessTokenFromCookie(request); // 기존 메서드 그대로
}
```
AC-1(헤더 200)/AC-2(쿠키 200)/AC-3(동시 헤더 우선)/AC-4·5(만료·위변조→clearContext→EntryPoint 401)은 기존 검증 로직이 보장.

### 2. 신규 엔드포인트 (FR-2/3/5) — 기존 AuthV1Controller에 추가
```java
@PostMapping("/kakao/native")
ResponseEntity<ApiResponse<TokenResponse>> kakaoNativeLogin(@Valid @RequestBody KakaoNativeLoginRequest req) {
    AuthResultInfo r = authService.loginWithKakaoNative(req.kakaoAccessToken());
    return ResponseEntity.ok(ApiResponse.success(TokenResponse.of(r, jwtProperties.accessTtlSeconds())));
}
@PostMapping("/apple/native") // authService.loginWithApple(req.toCommand())
@PostMapping("/refresh")        // authService.refreshTokens(req.refreshToken()) — 기존 메서드 재사용
```
Dto(record):
- `KakaoNativeLoginRequest(@NotBlank String kakaoAccessToken)`
- `AppleNativeLoginRequest(@NotBlank String identityToken, @NotBlank String nonce, String authorizationCode, AppleFullName fullName, String email)` — authorizationCode는 P1 수신만(revoke는 P2)
- `AppleFullName(String givenName, String familyName)`
- `RefreshRequest(@NotBlank String refreshToken)`
- `TokenResponse(String accessToken, String refreshToken, long expiresIn)` + `static of(AuthResultInfo, long ttl)`
- **AuthTokenInfo 신규 안 만듦**: 기존 `AuthResultInfo`(accessToken/refreshToken 포함) + 컨트롤러 주입 ttl로 조립 (critic SIMPLIFY).
- nonce 계약: ApiSpec @Operation에 "nonce = 클라이언트 평문, 서버는 SHA-256 소문자 hex로 identityToken nonce 클레임과 대조" 명시.

### 3. AuthService + UserLoginPersistence (FR-2/3/5/7, BR-3/6/8/9/11/12)
```java
// AuthService — Kakao 네이티브 (콜백과 동일 Bulkhead·HTTP밖 트랜잭션)
public AuthResultInfo loginWithKakaoNative(String kakaoAccessToken) {
    KakaoUserInfoResponse info = kakaoClient.fetchUserInfo(kakaoAccessToken); // 재사용(BR-3, AC-8: 위변조→카카오4xx→502)
    // nickname null 가드(기존 loginWithKakao 동일), Bulkhead acquire/release
    return userLoginPersistence.upsertByOauthAndIssueTokens(
        NativeLoginCommand.kakao(info.id(), info.resolvedNickname(), info.resolvedProfileImageUrl()));
}
// Apple
public AuthResultInfo loginWithApple(AppleNativeLoginCommand cmd) {
    AppleTokenClaims claims = appleVerifier.verify(cmd.identityToken(), cmd.nonce()); // BR-4/5
    String nickname = resolveAppleNickname(cmd.fullName()); // BR-12: given+family 또는 "Apple 사용자"
    return userLoginPersistence.upsertByOauthAndIssueTokens(
        NativeLoginCommand.apple(claims.sub(), nickname, firstNonNull(claims.email(), cmd.email())));
}
```
```java
// UserLoginPersistence — 신규 (@Transactional + @Retryable + listeners)
@Retryable(retryFor={CannotCreateTransactionException.class, DataIntegrityViolationException.class},
           maxAttempts=2, backoff=@Backoff(delay=500, multiplier=2.0), listeners="loginRetryListener")
@Transactional
public AuthResultInfo upsertByOauthAndIssueTokens(NativeLoginCommand cmd) {
    UserModel user = userRepository.findByOauthProviderAndOauthId(cmd.provider(), cmd.oauthId())
        .map(existing -> {
            if (!existing.isActive()) throw new CoreException(ErrorType.AUTH_USER_DEACTIVATED); // BR-6, AC-15
            if (cmd.provider()==OauthProvider.KAKAO) existing.updateProfile(cmd.nickname(), cmd.profileImageUrl());
            // BR-9: Apple 기존계정은 updateProfile 미호출 → email/nickname 불변
            return existing;
        })
        .orElseGet(() -> userRepository.saveAndFlush(cmd.toNewUser())); // race→DIV→@Retryable(AC-7)
    return issueTokensFor(user); // ★ 공통 헬퍼 추출 (기존 upsertAndIssueTokens와 공유)
}
// 공통 헬퍼: access/refresh 발급 + refreshTokenHash 저장 + AuthResultInfo.of(user, accessRaw, refreshRaw)
private AuthResultInfo issueTokensFor(UserModel user) { ... }
```
- **@Recover(MUST#1)**: 신규 메서드용 `@Recover (예외, NativeLoginCommand)` 시그니처로 추가 — 기존 `(예외, Long, String, String)` 3종과 **인자 타입·개수 모두 달라 모호성 제거**(신규는 인자 1개 NativeLoginCommand). 기존 정책 복제(CCT/DIV→친화에러, 그 외→원본전파). provider별 친화에러: KAKAO→AUTH_KAKAO_API_FAILED, APPLE→AUTH_APPLE_JWKS_UNAVAILABLE(또는 공용). **구현 시 Spring Retry 매칭 PoC로 확인**(모호 시 신규 upsert를 별도 빈 `OauthLoginPersistence`로 분리). LoginRetryListener RETRYABLE_EXCEPTIONS 동기화 확인.

### 4. Kakao 네이티브 검증 (BR-3, AC-6/7/8)
`KakaoOAuthClient.fetchUserInfo(token)` 재사용(76행). 위변조→카카오 4xx→onStatus→AUTH_KAKAO_API_FAILED(502, AC-8). 신규 메서드 없음.

### 5. Apple identityToken 검증 — nimbus-jose-jwt (FR-3/8/9, BR-4/5, AC-9/12/13/14, QE-2)
의존성: `build.gradle.kts`에 `implementation("com.nimbusds:nimbus-jose-jwt:<버전>")` 추가 (Spring Boot BOM 관리 버전 우선; 없으면 9.x 최신).
```java
@Component
class AppleIdentityTokenVerifier {
    private final ConfigurableJWTProcessor<SecurityContext> processor; // 빈 초기화 시 구성
    private final AppleAuthProperties props;

    // 구성(생성자):
    //   JWKSource<SecurityContext> src = JWKSourceBuilder.create(new URL(props.jwksUrl()))
    //        .cache(props.jwksTtlSeconds()*1000, RETRY) .rateLimited(...) .build();  // 캐싱·로테이션 내장(FR-8)
    //   DefaultJWTProcessor p = new DefaultJWTProcessor();
    //   p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, src));
    //   p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
    //        new JWTClaimsSet.Builder().issuer(props.issuer()).audience(props.audience()).build(),
    //        Set.of("sub","exp")));  // iss/aud exact + exp(만료) 검증

    AppleTokenClaims verify(String identityToken, String rawNonce) {
        try {
            JWTClaimsSet c = processor.process(identityToken, null); // 서명+iss+aud+exp (BR-4)
            String expected = RefreshTokenHasher.sha256Hex(rawNonce); // 소문자 hex (MUST#3)
            if (!expected.equals(c.getStringClaim("nonce")))          // nonce 없거나 불일치 → 401
                throw new CoreException(ErrorType.AUTH_APPLE_TOKEN_INVALID); // BR-5, AC-14
            return new AppleTokenClaims(c.getSubject(), c.getStringClaim("email"));
        } catch (RemoteKeySourceException e) {                        // JWKS 조회 실패 (QE-2)
            throw new CoreException(ErrorType.AUTH_APPLE_JWKS_UNAVAILABLE); // 502, 검증실패와 구분
        } catch (BadJOSEException | JOSEException | ParseException e) { // 서명/iss/aud/만료/형식 (AC-12/13)
            throw new CoreException(ErrorType.AUTH_APPLE_TOKEN_INVALID);   // 401
        }
    }
}
```
보안 경로: 만료/위변조/서명/iss/aud/nonce 불일치 → 401(AUTH_APPLE_TOKEN_INVALID). JWKS 네트워크 오류 → 502(AUTH_APPLE_JWKS_UNAVAILABLE, **nimbus RemoteKeySourceException로 구분**, QE-2). nimbus가 캐싱·키 로테이션(kid 미스 시 재조회) 자동 처리(FR-8).
```yaml
apple:
  audience: ${APPLE_BUNDLE_ID}
  issuer: https://appleid.apple.com
  jwks-url: https://appleid.apple.com/auth/keys
  jwks-ttl-seconds: ${APPLE_JWKS_TTL_SECONDS:21600}
```

### 6. UserModel 일반화 (FR-7, BR-9/12, AC-21/22)
```java
@Column(name="oauth_provider", nullable=false, length=20) @Enumerated(EnumType.STRING)
private OauthProvider oauthProvider;
@Column(name="oauth_id", nullable=false, length=255) private String oauthId;
@Column(name="email") private String email;                 // nullable
@Column(name="kakao_user_id", unique=true) private Long kakaoUserId; // nullable=false 제거 (Apple은 null)

public static UserModel create(Long kakaoUserId, String nickname, String profileImageUrl) { // 기존 보존
    // 내부에서 oauthProvider=KAKAO, oauthId=kakaoUserId.toString() 세팅 → 기존 호출부 무변경
}
public static UserModel createOauth(OauthProvider p, String oauthId, String nickname, String img, String email) { // Apple
}
@Override protected void guard() {
    if (oauthProvider==null) throw ...; if (oauthId==null||blank) throw ...;
    if (oauthProvider==KAKAO && kakaoUserId==null) throw ...; // 기존 가드 KAKAO 한정
    if (nickname==null||blank) throw ...;
}
```
BR-12 임시닉네임은 AuthService.resolveAppleNickname()에서. 모델은 nickname null 거부만.

### 7. V014 마이그레이션 (FR-6, BR-10, AC-19/20/22)
```sql
-- V014__generalize_oauth_provider.sql
-- additive: kakao_user_id 컬럼·UNIQUE(uq_users_kakao_user_id) 무손실 유지(BR-10). Apple은 kakao_user_id 없음→DROP NOT NULL.
-- 백필: 전 행 (KAKAO, kakao_user_id::text)(AC-19). email nullable(Apple 최초만).
-- ⚠ 운영 주의: UPDATE+SET NOT NULL은 풀스캔/락 가능. 현재 users 소규모라 수용. 대규모면 배치 분리 검토.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS oauth_provider VARCHAR(20) NOT NULL DEFAULT 'KAKAO',
    ADD COLUMN IF NOT EXISTS oauth_id       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS email          VARCHAR(255);
UPDATE users SET oauth_id = kakao_user_id::text WHERE oauth_id IS NULL;
ALTER TABLE users ALTER COLUMN oauth_id SET NOT NULL;
ALTER TABLE users ALTER COLUMN kakao_user_id DROP NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_oauth UNIQUE (oauth_provider, oauth_id);
COMMENT ON COLUMN users.oauth_provider IS 'P1: OAuth 공급자(KAKAO/APPLE). 기존 백필 KAKAO.';
COMMENT ON COLUMN users.oauth_id IS 'P1: 공급자별 식별자. Kakao=kakao_user_id::text, Apple=sub.';
COMMENT ON COLUMN users.email IS 'P1: Apple 최초 로그인 1회 저장. Kakao 미수집(NULL).';
```

### 8. refresh(body) (FR-5, BR-7/8/11, AC-16/17/18)
`AuthService.refreshTokens(raw)`(85행, 쿠키 비의존 — critic 확인) 100% 재사용. 컨트롤러가 body 추출 → TokenResponse(+ttl). 기존 /auth/token/refresh(쿠키) 무변경(AC-18).

### 9. ErrorType 추가
`AUTH_APPLE_TOKEN_INVALID`(401), `AUTH_APPLE_JWKS_UNAVAILABLE`(502). 기존 존재: AUTH_REFRESH_TOKEN_INVALID, AUTH_USER_DEACTIVATED, AUTH_KAKAO_API_FAILED, AUTH_USER_NOT_FOUND.

### 10. SecurityConfig — 변경 불필요
/api/v1/auth/** 이미 permitAll(39행, critic 확인). 보호 엔드포인트는 anyRequest().authenticated() + 필터.

### 11. 테스트 전략
- **필터 단위**(신규): Bearer만(AC-1)/쿠키만(AC-2)/동시 헤더우선(AC-3)/만료·위변조(AC-4/5)/빈 Bearer→쿠키 폴백.
- **Apple 검증 단위**(신규): 테스트 RSA 키쌍 자체발급 + JWKS stub(WireMock). 정상(AC-9)/aud불일치(AC-12)/만료(AC-13)/nonce불일치(AC-14)/서명불일치/JWKS오류→502(QE-2).
- **컨트롤러 통합**(확장, 기존 WireMock+Testcontainers): kakao/native(Set-Cookie부재 AC-6, 순차2회 중복없음 AC-7, 4xx→502 AC-8), apple/native(@DynamicPropertySource로 apple.jwks-url override, AC-9~14, 최초저장 AC-10, 재로그인불변 AC-11, **탈퇴자→401 AC-15**), refresh(새쌍·Set-Cookie부재 AC-16, 재사용 401 AC-17, 기존 쿠키 refresh 회귀 AC-18).
- **마이그레이션**(확장): 컬럼 기대치 + 백필(AC-19) + uq_users_oauth + kakao_user_id·uq_users_kakao_user_id 유지(AC-20) + Apple행(kakao_user_id NULL) INSERT(AC-22) + **백필된 탈퇴행 재로그인 401(MUST#4)**. insertUser 헬퍼에 oauth_provider/oauth_id 추가.

## 구현 순서
1. OauthProvider enum + UserModel 필드/팩토리/guard + kakaoUserId nullable
2. V014 마이그레이션
3. UserRepository/Impl/JpaRepository findByOauthProviderAndOauthId (의존1)
4. ErrorType 2종
5. JwtAuthenticationFilter Bearer 분기 + 필터 테스트 (독립)
6. build.gradle.kts nimbus 추가 + AppleAuthProperties + application.yml
7. AppleIdentityTokenVerifier + AppleTokenClaims + 검증 테스트 (의존4,6)
8. NativeLoginCommand + AuthV1Dto record
9. UserLoginPersistence.upsertByOauthAndIssueTokens + 공통헬퍼 + @Recover (의존1,3) — **@Recover 매칭 PoC**
10. AuthService 네이티브/Apple/refresh (의존7,8,9)
11. AuthV1ApiSpec + Controller 3핸들러 + nonce 계약 문서화 (의존8,10)
12. 통합 테스트 + FlywayMigrationTest 갱신 (의존2,11)
1·4·5·6·8 병렬 착수 가능.

## 하위호환·영향도
- 무변경 보장: 쿠키 인증·Kakao 콜백·/auth/token/refresh·findByKakaoUserId·kakao_user_id UNIQUE.
- UserModel.guard() 변경은 create() 내부 보강으로 기존 호출부(UserLoginPersistence 69행) 무영향.
- 신규 의존성: nimbus-jose-jwt 1개. 기존 jjwt(대칭키)는 유지(JWT 발급/검증), nimbus는 Apple 검증 전용.
- 테스트 픽스처 raw INSERT는 oauth_id NOT NULL이라 수정 필요(FlywayMigrationTest insertUser 등).
