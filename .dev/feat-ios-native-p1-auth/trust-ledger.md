# Trust Ledger — P1 백엔드 인증 확장 (review 1회차)

> qa-manager(QA) + security-auditor(ZT) 통합 감사. 합산 findings + 조치.

## 통합 감사 (review)

### CRITICAL (자동 수정)
- [토큰검증/CRITICAL] AppleIdentityTokenVerifier `outageTolerant(jwksTtlSeconds*1000=6h)` — JWKS 엔드포인트 장애 시 캐시된 마지막 키를 최대 6시간 재사용 → Apple 긴급 키 폐기 시 **폐기 키로 서명된 위조/탈취 토큰이 6h 유효**.
  - 근거: `outageTolerant` 없으면 JWKS 조회 실패 시 `RemoteKeySourceException`→502(QE-2 이미 달성).
  - 권고/조치: **`outageTolerant` 제거.** JWKS 장애는 502로. → coder 수정.

### HIGH (수정)
- [인증우회/HIGH] `AuthService.withBulkhead`(144,147) Apple 과부하/InterruptedException 시 `AUTH_KAKAO_API_FAILED`(502) — Apple 사용자에 카카오 에러 오인. (QA Warning과 동일)
  - 조치: provider-agnostic 과부하 에러로 분리(또는 provider별 코드 주입). → coder.
- [정책위반/HIGH] `@Recover(DIV)` → `toFriendlyError`가 APPLE DIV 소진 시 `AUTH_APPLE_JWKS_UNAVAILABLE` 오탐(DIV=DB 동시성, JWKS 무관). 탈퇴자 동시 2요청 시 두번째가 502 가능.
  - 조치: DIV 소진은 provider-agnostic 서버 에러로 구분. → coder.
- [토큰검증/HIGH] `rawNonce=null` 계약 불일치 — verifier는 우연히 안전(`expectedNonce==null`→401)하나 `AppleLoginCommand.nonce`에 가드 없음.
  - 조치: verifier 진입부 null 명시 가드 + AppleLoginCommand.nonce 검증 + 단위테스트. → coder.

### MEDIUM
- [GAP] V014 백필 실제 DB 검증 부재 — 테스트가 SQL 텍스트 패턴만 검사. Testcontainers는 빈 DB라 "기존 행 백필"을 직접 검증 못함.
  - 조치: Flyway target V013→행 INSERT→V014 적용→oauth_id 백필 SELECT 검증 테스트 추가(가능 시). → coder.
- [누락시나리오] AC-15 Kakao 네이티브 탈퇴자 401 통합테스트 누락(Apple만 존재). → coder 추가.
- [누락시나리오] 탈퇴 시 `clearRefreshTokenHash()` 호출 여부 미확인 — **계정 삭제는 P2 범위**. P1에선 현 isActive() 차단으로 충분. P2 이월.
- [시크릿노출] KakaoOAuthClient 에러 메시지에 HTTP status 포함 — **기존 코드(P1 미변경)**. ControllerAdvice 노출 여부 확인 후 후속.
- [누락시나리오] Apple email 출처(토큰 클레임 vs 요청 body) 신뢰 정책 — 문서화. sub 기준 계정이라 탈취 불가, body email은 클레임 없을 때만.
- [ASSUMPTION] `@ConfigurationPropertiesScan` 존재 — **통합테스트 기동 성공으로 검증됨**(미존재 시 컨텍스트 로딩 실패). 해소.
- [GAP] `authorizationCode` 수신 후 미저장 — **P2 revoke 범위**. P2 설계 시 획득 경로 확인.

### LOW
- [GAP] nonce replay 방어 없음 — identityToken exp 단기(~10분)라 창 좁음. 후속 검토.
- [ASSUMPTION] Kakao access token 앱 귀속 미검증 — **네이티브는 클라 제공 토큰을 신뢰**(웹 콜백과 달리 새 신뢰면). 다른 Kakao 앱 토큰으로 우리 서비스 로그인 가능 → 사용자 결정(app_id 검증 추가 여부).

## QA 추가 (ZT와 중복 제외)
- [Info] LoginRetryListener:35 javadoc 신규 메서드 미참조 → 병기. → coder.
- [Info] AppleVerifier nonce null 조건순서 주석 + 테스트. → coder(HIGH#nonce와 통합).
- [Info] AuthV1ControllerIntegrationTest appleBody StringBuilder JSON 수동조립 → ObjectMapper 권장(테스트 안전성). 선택.

## 조치 계획 (review 2회차 전 coder 수정)
1. outageTolerant 제거(CRITICAL)
2. withBulkhead + @Recover(DIV) provider-agnostic 에러(HIGH ×2)
3. rawNonce null 명시 가드 + AppleLoginCommand 검증 + 테스트(HIGH)
4. RetryIT 신규 upsertByOauthAndIssueTokens 케이스(DIV race/CCT 소진 recover/CoreException 전파)
5. AC-15 Kakao 네이티브 탈퇴자 통합테스트
6. V014 백필 실제 DB 검증 테스트(가능 시)
7. LoginRetryListener javadoc
- 사용자 결정 대기: Kakao app_id 검증 추가 여부(LOW).
- P2 이월: clearRefreshTokenHash, authorizationCode 저장, KakaoOAuthClient 메시지.

## review 2회차 (재검토 결과)
- **QA**: 1회차 8항목 전부 해소. 신규 Critical 0. AC **22/22**. (Warning: appleBody 수동 JSON / QUESTION: RetryIT 미사용 save stub — 둘 다 비차단)
- **ZT**: 1회차 CRITICAL 1 + HIGH 3 **전부 해소**. 신규 발견:
  - [RISK/HIGH ×2] **기존 `loginWithKakao`(웹 콜백) Bulkhead + 기존 3인자 @Recover**가 과부하/소진 시 `AUTH_KAKAO_API_FAILED`(502) 반환 — **P1 미변경 기존 코드**. 신규 네이티브 경로는 503으로 해소됨. 고치려면 웹 경로 에러코드 변경 → **웹 회귀 위험(QE-1 위반 소지)**.
  - [RISK/MEDIUM] `KAKAO_APP_ID:0` 로컬/테스트 기본값 — 비-prod 환경 누락 시 검증 무력화. **prod yml은 기본값 없어 fail-fast(안전)**. 권고: `@Positive` 또는 appId<=0 가드.
  - [GAP/MEDIUM] access_token_info 4xx(만료 토큰)→502 — 단 **AC-8(위변조 Kakao→502)과 정합**(의도됨).
  - [ASSUMPTION/MEDIUM] Kakao 2회 호출 타임아웃 예산 문서화(보안 위험 없음).
  - [LOW] appleBody 테스트 JSON 조립.

### 2회차 처리 방침 (오케스트레이터 판단)
- 신규 HIGH 2건 = **기존 웹 경로**, P1 additive 범위 밖. P1에서 고치면 웹 회귀 위험 → **P2/별도 리팩터 이월 권고**.
- KAKAO_APP_ID 가드(MEDIUM): prod fail-fast로 실위험 낮음. 운영 시크릿에 KAKAO_APP_ID 필수(이미 prerequisites 항목). 선택적 `@Positive` 보강.
- access_token_info 502 = AC-8 정합(수용). 타임아웃/appleBody/save stub = 비차단(P2 또는 무시).
- **P1 본 범위는 클린**: CRITICAL 0, 신규경로 HIGH 0, AC 22/22, 빌드/테스트 green.
