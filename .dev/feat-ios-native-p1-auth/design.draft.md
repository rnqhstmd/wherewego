# 설계 초안: P1 — 백엔드 인증 확장 (Bearer + Kakao/Apple 네이티브 + refresh + oauth 일반화)

> architect 초안. design-critic 검토 + 사용자 Q&A 후 design.md로 확정.

## 설계 규모
**대형** — 신규 엔드포인트 3, 신규 외부 검증 인프라(Apple JWKS), 핵심 식별 모델(users) 스키마 변경+백필, 보안 직결 토큰 검증 경로 다수.

## 개요/접근
1. 전부 additive, 기존 경로 무수정 원칙(쿠키 인증·Kakao 콜백·`/auth/token/refresh`·`findByKakaoUserId`·`kakao_user_id` 무변경).
2. 필터는 추출 지점만 분기(검증·SecurityContext 처리 그대로).
3. AuthService 확장(신규 서비스 없음): HTTP는 AuthService, DB 쓰기는 UserLoginPersistence. find-or-create는 (provider, oauthId) 키.
4. Apple JWKS 검증은 jjwt 0.12.6로 충분(신규 의존성 불필요). 캐싱은 Caffeine, 조회는 RestClient.
5. UserModel 일반화는 컬럼·필드 추가만(kakaoUserId 보존, Apple 전용 팩토리 추가).
6. refresh(body)는 `AuthService.refreshTokens(raw)` 100% 재사용, Set-Cookie만 생략.

## 변경 범위

### 신규 파일 (10)
- config/env/AppleAuthProperties.java — Apple aud(복수)/issuer/JWKS URL/TTL. @ConfigurationProperties(prefix="apple")
- infrastructure/auth/apple/AppleJwksClient.java — /auth/keys RestClient 조회(KakaoOAuthClient 패턴)
- infrastructure/auth/apple/AppleIdentityTokenVerifier.java — jjwt Jwks 서명검증 + iss/aud/exp + JWKS 캐싱(Caffeine)
- infrastructure/auth/apple/AppleTokenClaims.java — 검증 클레임 record(sub, email)
- domain/(user|auth)/OauthProvider.java — enum {KAKAO, APPLE} (위치 질문1)
- domain/auth/NativeLoginCommand.java — 신규 계정 생성 입력 캐리어
- application/auth/AuthTokenInfo.java — 앱용 응답 Info(accessToken/refreshToken/expiresIn)
- resources/db/migration/V014__generalize_oauth_provider.sql — 컬럼+백필+UNIQUE
- test .../apple/AppleIdentityTokenVerifierTest.java
- test .../config/security/JwtAuthenticationFilterTest.java

### 수정 파일 (10+)
- config/security/JwtAuthenticationFilter.java — Bearer 헤더 분기(검증 무변경)
- interfaces/api/auth/AuthV1Controller.java — kakaoNative/appleNative/refresh 3핸들러
- interfaces/api/auth/AuthV1ApiSpec.java — 3 메서드 시그니처 + @Operation
- interfaces/api/auth/AuthV1Dto.java — 요청/응답 record
- domain/auth/AuthService.java — loginWithKakaoNative/loginWithApple/refresh(재사용) + JwtProperties 주입
- domain/auth/UserLoginPersistence.java — upsertByOauthAndIssueTokens + @Recover
- domain/user/UserModel.java — oauthProvider/oauthId/email 필드 + Apple 팩토리 + guard 보강
- domain/user/UserRepository.java + UserRepositoryImpl.java — findByOauthProviderAndOauthId
- infrastructure/user/UserJpaRepository.java — 파생 쿼리
- support/error/ErrorType.java — AUTH_APPLE_TOKEN_INVALID, AUTH_APPLE_JWKS_UNAVAILABLE
- config/security/SecurityConfig.java — 변경 불필요(/api/v1/auth/** permitAll)
- resources/application.yml — apple: 블록
- test .../migration/FlywayMigrationTest.java — 컬럼 기대치 + 백필/UNIQUE 검증

## 적용 컨벤션
- 레이어: interfaces/api(Controller+ApiSpec+Dto) → application(Info) → domain(Service/Model) → infrastructure(Client/Repo impl). Repo 인터페이스=domain, impl+JpaRepository=infrastructure.
- 에러: CoreException(ErrorType.XXX, "메시지"). ErrorType=(HttpStatus, code, message).
- DI: @Component/@RestController + @RequiredArgsConstructor + private final.
- Properties: record + @ConfigurationProperties + @Validated. @ConfigurationPropertiesScan 있어 자동 등록.
- 외부 HTTP: RestClient.builder().baseUrl().requestFactory(timeout) + onStatus 4xx/5xx→CoreException.
- JWT: jjwt 0.12.x, typ claim access/refresh 구분, JwtValidationResult sealed.
- DTO: record + jakarta validation, from(Info) 팩토리.
- 마이그레이션: V0NN__snake_case.sql, 상단 ===== 헤더, ADD COLUMN IF NOT EXISTS, COMMENT ON COLUMN.
- 트랜잭션: 외부 HTTP 메서드 @Transactional 금지, DB 쓰기는 UserLoginPersistence로 위임.

## 상세 설계

### 1. JwtAuthenticationFilter — Bearer 분기 (FR-1, BR-1/2, AC-1~5)
토큰 추출 단계만 분기. 검증/SecurityContext 처리(기존 34~52행) 그대로. extractAccessTokenFromCookie 폴백 보존.
```java
private static final String BEARER_PREFIX = "Bearer ";
String token = extractToken(request); // 기존 34행 교체
private String extractToken(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith(BEARER_PREFIX)) {
        String value = header.substring(BEARER_PREFIX.length()).trim();
        return value.isEmpty() ? null : value; // 빈 Bearer→폴백 안 함(질문2)
    }
    return extractAccessTokenFromCookie(request);
}
```
검증 로직 무수정 → AC-4/5 기존 코드가 보장.

### 2. 신규 엔드포인트 배치 (FR-2/3/5)
기존 AuthV1Controller(@RequestMapping("/api/v1/auth"))에 추가. 별도 컨트롤러 없음.
- ApiSpec: kakaoNativeLogin/appleNativeLogin/refresh 3 시그니처.
- Controller: @PostMapping /kakao/native, /apple/native, /refresh. Set-Cookie 미설정, body만 반환.
- Dto: KakaoNativeLoginRequest(@NotBlank kakaoAccessToken), AppleNativeLoginRequest(identityToken, nonce, authorizationCode[P1 미사용 수신만], fullName{givenName,familyName}, email), RefreshRequest(@NotBlank refreshToken), TokenResponse(accessToken, refreshToken, expiresIn) + from(AuthTokenInfo).
- AuthTokenInfo(신규): accessToken/refreshToken/expiresIn. expiresIn=JwtProperties.accessTtlSeconds(). 기존 AuthResultInfo(userId/nickname/...)는 쿠키 응답 전용 유지.

### 3. AuthService 확장 + UserLoginPersistence (FR-2/3/5/7, BR-3/6/8/9/11)
```java
// Kakao 네이티브 (콜백과 동일 Bulkhead·HTTP-밖-트랜잭션 패턴)
public AuthTokenInfo loginWithKakaoNative(String kakaoAccessToken) {
    KakaoUserInfoResponse userInfo = kakaoClient.fetchUserInfo(kakaoAccessToken); // 재사용 (BR-3)
    // nickname/profileImageUrl resolve, null 가드, Bulkhead acquire/release
    AuthResultInfo r = userLoginPersistence.upsertByOauthAndIssueTokens(
        OauthProvider.KAKAO, kakaoId.toString(), NativeLoginCommand.kakao(...));
    return AuthTokenInfo.of(r.accessToken(), r.refreshToken(), jwtProperties.accessTtlSeconds());
}
// Apple
public AuthTokenInfo loginWithApple(AppleNativeLoginCommand cmd) {
    AppleTokenClaims claims = appleVerifier.verify(cmd.identityToken(), cmd.nonce()); // BR-4/5
    String nickname = resolveAppleNickname(cmd.fullName()); // BR-12
    AuthResultInfo r = userLoginPersistence.upsertByOauthAndIssueTokens(
        OauthProvider.APPLE, claims.sub(), NativeLoginCommand.apple(claims.sub(), nickname, claims.emailOrRequest(cmd.email())));
    return AuthTokenInfo.of(...);
}
```
```java
// UserLoginPersistence — 신규(@Retryable + @Transactional, 기존 upsertAndIssueTokens 패턴)
public AuthResultInfo upsertByOauthAndIssueTokens(OauthProvider provider, String oauthId, NativeLoginCommand cmd) {
    UserModel user = userRepository.findByOauthProviderAndOauthId(provider, oauthId)
        .map(existing -> {
            if (!existing.isActive()) throw new CoreException(ErrorType.AUTH_USER_DEACTIVATED); // BR-6
            if (provider == KAKAO) existing.updateProfile(cmd.nickname(), cmd.profileImageUrl());
            // BR-9: Apple은 updateProfile 호출 안 함(email/nickname 불변)
            return existing;
        })
        .orElseGet(() -> userRepository.saveAndFlush(cmd.toNewUser()));
    // access/refresh 발급 + refreshTokenHash 저장(기존 동일)
    return AuthResultInfo.of(user, accessRaw, refreshRaw);
}
```
- @Recover 신규 3종 필요(시그니처 매칭, 질문5). BR-9: Apple 기존계정 updateProfile 미호출 → 불변. email은 신규생성(toNewUser)만.

### 4. Kakao 네이티브 검증 (BR-3, AC-6/7/8)
KakaoOAuthClient.fetchUserInfo(token) 그대로 재사용(76행). 4xx/5xx→AUTH_KAKAO_API_FAILED(502). 신규 메서드 불필요.

### 5. Apple identityToken 검증 인프라 (FR-3/8/9, BR-4/5, AC-9/12/13/14, QE-2)
라이브러리: jjwt 0.12.6 존재(build.gradle.kts 53~56). Jwks.parser() + keyLocator로 RS256 검증. nimbus 불필요(질문6).
```java
class AppleJwksClient { String fetchJwksJson(); } // 5xx/통신오류→AUTH_APPLE_JWKS_UNAVAILABLE(502/503) (QE-2)
class AppleIdentityTokenVerifier {
  AppleTokenClaims verify(String identityToken, String rawNonce) {
    // 1) JWKS 확보(캐시 or fetch). kid 미스→1회 강제 refresh.
    // 2) Jwts.parser().keyLocator(...).requireIssuer("https://appleid.apple.com").parseSignedClaims → 서명/exp. 실패→AUTH_APPLE_TOKEN_INVALID(401) (AC-13)
    // 3) aud ∈ props.audiences() (복수 허용). 불일치→401 (AC-12)
    // 4) nonce: sha256Hex(rawNonce)==claims.nonce. 불일치→401 (BR-5, AC-14). nonce 없는 토큰도 401.
    // 5) sub/email 추출
  }
}
```
보안 경로: 만료/위변조/서명/iss/aud/nonce 불일치 → 401(AUTH_APPLE_TOKEN_INVALID). JWKS 네트워크 오류 → 502/503(구분, QE-2). aud+iss로 혼용 방지. nonce는 RefreshTokenHasher.sha256Hex 재사용.
```yaml
apple:
  audiences: [ ${APPLE_BUNDLE_ID} ]
  issuer: https://appleid.apple.com
  jwks-url: https://appleid.apple.com/auth/keys
  jwks-ttl-seconds: ${APPLE_JWKS_TTL_SECONDS:21600}
```

### 6. UserModel 일반화 (FR-7, BR-9/12, AC-21/22)
oauth_provider(@Enumerated STRING, len20, NOT NULL) + oauth_id(len255, NOT NULL) + email(nullable) 추가.
- create(kakaoUserId,...) 보존하되 내부에서 oauthProvider=KAKAO, oauthId=kakaoUserId.toString() 세팅.
- createOauth(provider, oauthId, nickname, profileImageUrl, email) Apple 팩토리(kakaoUserId=null).
- guard(): oauthProvider/oauthId null 체크 + KAKAO일 때만 kakaoUserId null 체크 + nickname null 체크.
- 중대결정(질문3): kakaoUserId @Column(nullable=false) → Apple은 null이라 DB NOT NULL 위반. V014에서 kakao_user_id DROP NOT NULL + UserModel @Column(nullable=false) 제거 필요. UNIQUE는 NULL distinct라 유지 가능.
- BR-12 임시 닉네임은 AuthService.resolveAppleNickname()에서. 모델은 nickname null 거부만.

### 7. V014 마이그레이션 SQL (FR-6, BR-10, AC-19/20)
```sql
-- V014__generalize_oauth_provider.sql
-- additive: kakao_user_id 컬럼·UNIQUE(uq_users_kakao_user_id) 무손실 유지. Apple은 kakao_user_id 없음→DROP NOT NULL.
-- 백필: 전 행 (KAKAO, kakao_user_id::text). email nullable.
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

### 8. refresh(body) 엔드포인트 (FR-5, BR-7/8/11, AC-16/17/18)
AuthService.refreshTokens(raw)(84~116행) 토큰 문자열만 받음 → 100% 재사용. body에서 추출, Rotation·해시갱신은 기존 로직. 기존 /auth/token/refresh(쿠키) 무변경(AC-18). 컨트롤러에 jwtProperties 주입(expiresIn) 또는 오버로드(질문7).

### 9. ErrorType 추가 (BR-4, QE-2)
AUTH_APPLE_TOKEN_INVALID(401), AUTH_APPLE_JWKS_UNAVAILABLE(502). 이미 존재: AUTH_REFRESH_TOKEN_INVALID, AUTH_USER_DEACTIVATED, AUTH_KAKAO_API_FAILED, AUTH_USER_NOT_FOUND.

### 10. SecurityConfig — 변경 불필요
/api/v1/auth/** 이미 permitAll(39행). 신규 3엔드포인트 매칭. 보호 엔드포인트는 anyRequest().authenticated() + 필터.

### 11. 테스트 전략
- 필터 단위(신규): Bearer만/쿠키만/동시(헤더우선)/만료·위변조/빈Bearer.
- Apple 검증 단위(신규): 테스트 RSA 키쌍 자체발급 + JWKS stub. 정상/aud/만료/nonce/서명.
- 컨트롤러 통합(확장): WireMock+Testcontainers. kakao/native(Set-Cookie 부재·중복방지·4xx→502), apple/native(@DynamicPropertySource JWKS override, 정상/만료/aud/nonce, 최초저장·재로그인불변·탈퇴401), refresh(새쌍·Set-Cookie부재·재사용401·기존회귀).
- 마이그레이션(확장): 컬럼 기대치 + 백필(AC-19) + uq_users_oauth + kakao_user_id 유지(AC-20) + Apple행(kakao_user_id NULL) INSERT(AC-22). insertUser 헬퍼 수정.

## 의존성 및 영향도
- 신규 라이브러리: 없음(jjwt/Caffeine/RestClient 기존). build.gradle.kts 무수정(jjwt JWK API 확정은 질문6).
- 기존 영향: UserModel.guard() 변경(create 내부 보강으로 기존 호출부 무변경). 테스트 픽스처 raw INSERT는 oauth_id NOT NULL이라 수정 필요.
- 하위호환: 쿠키/콜백/token-refresh/findByKakaoUserId/kakao_user_id UNIQUE 무변경. V014 additive+NOT NULL 완화만(손실 0).

## 구현 순서
1. OauthProvider enum + UserModel 필드/팩토리/guard
2. V014 마이그레이션 SQL
3. UserRepository/Impl/JpaRepository findByOauthProviderAndOauthId (의존1)
4. ErrorType 2종
5. JwtAuthenticationFilter Bearer 분기 + 필터 테스트 (독립)
6. AppleAuthProperties + application.yml
7. AppleJwksClient + AppleIdentityTokenVerifier + AppleTokenClaims + 검증 테스트 (의존4,6)
8. AuthTokenInfo/NativeLoginCommand/AuthV1Dto record
9. UserLoginPersistence.upsertByOauthAndIssueTokens + @Recover (의존1,3)
10. AuthService 네이티브/Apple/refresh (의존7,8,9)
11. AuthV1ApiSpec + Controller 3핸들러 (의존8,10)
12. 통합 테스트 + FlywayMigrationTest 갱신 (의존2,11)
1·4·5·6·8 병렬 착수 가능.
