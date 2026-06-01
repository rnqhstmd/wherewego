# Phase 14 — 로그인 콜드 스타트 안정화 (Neon keep-warm · 재시도 예산 · 카카오 타임아웃)

- 작성일: 2026-05-30
- 수정일: 2026-05-30
- 관련 레포: rnqhstmd/wherewego
- 상태: ⬜ 미시작 (원인 분석 완료, 설계/구현 대기)

## 개요

운영 중 카카오 로그인이 **간헐적으로 502**로 실패하는 문제. 마지막 사용 후 수 분~수십 분 유휴 상태가 지나면 첫 로그인이 실패하고, 곧바로 재시도하면 성공하는 패턴. "DB 콜드 스타트인 줄 알았는데 한 시간 전엔 됐다"는 관찰이 있었으나, **분석 결과 정확히 콜드 스타트가 맞다** (1시간 유휴 = Neon suspend 확정 조건).

## 근본 원인

**Neon(무료 티어) 컴퓨트 콜드 스타트가 로그인 DB 재시도 예산(~10.5초)을 초과**하면서 발생하는 502.

### 콜드 스타트 발생 조건 (구조적으로 매 유휴마다 재현)

| 근거 | 위치 |
|------|------|
| DB가 Neon 무료 티어 (싱가포르 리전), ~5분 유휴 시 컴퓨트 suspend (scale-to-zero) | `docs/TECH.md:104`, `jpa.yml:104` |
| prod `minimum-idle: 0` → HikariCP가 유휴 커넥션을 0개 유지 (suspend 시 stale 방지 목적) | `backend/modules/jpa/src/main/resources/jpa.yml:111` |
| DB를 깨워두는 keep-warm 스케줄러 없음 (`@Scheduled`는 모니터링 용도뿐) | 코드 확인 |
| EC2(서울 ap-northeast-2) ↔ Neon(싱가포르 ap-southeast-1) 크로스 리전 + `sslmode=require` + `channel_binding=require` 핸드셰이크 | `jpa.yml:118-120`, `docs/TECH.md:104` |

→ 마지막 요청 후 5분만 지나면 Neon은 잠들고, HikariCP는 깨어있는 커넥션이 0개라 다음 로그인은 **무조건 새 물리 커넥션 = 콜드 스타트**를 유발한다. 1시간 유휴면 100% suspend 상태.

### 실패가 터지는 경로

1. `AuthService.loginWithKakao` — 카카오 HTTP 2회는 성공 (`AuthService.java:55-56`)
2. `UserLoginPersistence.upsertAndIssueTokens`에서 DB 트랜잭션 시작 → 커넥션 획득 시도 (`UserLoginPersistence.java:55-56`)
3. Neon suspend 상태면 `connection-timeout: 5000`(5s) 안에 커넥션 획득 실패 → `CannotCreateTransactionException` (`jpa.yml:115`)
4. `@Retryable`이 500ms 뒤 1회 재시도 → worst-case 5s + 0.5s + 5s ≈ **10.5초** (`UserLoginPersistence.java:49-55`)
5. Neon 콜드 스타트가 10.5초를 넘기면 → `recoverCannotCreateTransaction`이 `AUTH_KAKAO_API_FAILED` 던짐 (`UserLoginPersistence.java:88-93`)
6. 이 에러는 HTTP **502 BAD_GATEWAY** (`ErrorType.java:19`) → 프론트가 `/login?error=oauth_failed`로 리다이렉트 (`frontend/src/app/login/callback/page.tsx:68-70`)

### 왜 "간헐적"인가 (콜드 스타트 시그니처)

- Neon 콜드 스타트 지연이 들쭉날쭉(보통 3~8초, 크로스 리전+핸드셰이크 겹치면 P99가 10초+ 스파이크) → 10.5초 안에 깨면 성공, 넘기면 502.
- 첫 시도 실패가 그 자체로 Neon을 깨우므로 → "안 돼서 다시 누르면 됨" 패턴이 전형적.

## 같이 발견한 잠재 버그

**카카오 OAuth 클라이언트에 HTTP 타임아웃이 없음.** `application.yml:71`에 `kakao.callback.timeout-ms: 3000`이 정의돼 있고 `KakaoApiProperties.Callback.timeoutMs`도 존재하지만, `KakaoOAuthClient`가 이 값을 **실제로 안 쓴다**:

```java
// KakaoOAuthClient.java:29-30 — requestFactory/타임아웃 미설정
this.tokenClient = RestClient.builder().baseUrl(tokenBaseUrl).build();
this.userClient  = RestClient.builder().baseUrl(userBaseUrl).build();
```

S3(`S3Config.java:32-33`)·Slack(`SlackNotifier.java:35`)은 타임아웃이 박혀 있는데 카카오 OAuth만 누락. 카카오 서버가 느려지면 워커 스레드가 무한 대기한다. 또한 **DB 콜드 스타트 실패가 `AUTH_KAKAO_API_FAILED`라는 이름으로 로깅**돼서, 로그만 보면 "카카오 장애"로 오진하게 만든다.

## 구현 범위 (확정: 전체 3종)

| # | 항목 | 성격 | 메모 |
|---|------|------|------|
| 1 | **Neon keep-warm 스케줄러** — 활성 시간대에 `SELECT 1`을 ~4분 주기로 실행해 컴퓨트 suspend 방지 | 근본 원인 제거 | 무료 컴퓨트 한도(~191h/월) 고려해 24h 핑 금지 → 활성 시간대 윈도우로 제한 필요 |
| 2 | **재시도 예산 확대** — `connection-timeout`·`maxAttempts` 상향으로 콜드 스타트 전체를 흡수 | 즉효 완화 | 첫 로그인이 느려질 뿐(~10–15초) 실패하진 않게. 프론트엔 이미 "잠시만요" 로딩 화면 존재 |
| 3 | **카카오 OAuth 클라이언트 타임아웃 배선** — `kakao.callback.timeout-ms`를 `RestClient` requestFactory에 실제 연결 | 잠재 버그 수정 | 문서·주석이 주장하는 3초 타임아웃을 실제로 강제 |

### 보류 항목 (이번 phase 범위 밖)

- **autosuspend 제거 (DB 교체/유료화)**: Supabase의 7일 일시정지가 싫어 Neon으로 옮겼으나, Neon 무료는 5분 suspend라 로그인 UX 관점에선 더 공격적. Neon 유료(suspend off) 또는 EC2 로컬 Postgres는 중기 과제로 보류.
- **keep-warm 연속 실패 알림(FR-4)**: keep-warm 활성화 시점에 연속 ping 실패 Slack 알림/메트릭 추가. 현재는 기본 OFF라 미구현.

## 구현 결과 (2026-05-30)

브랜치 `feat/phase-14-login-cold-start`. 변경 파일 6개:

| 항목 | 구현 | 결정값 |
|------|------|--------|
| #2 재시도 예산 | `jpa.yml` prod `connection-timeout` 5000→10000, main `leak-detection-threshold` 15000→30000. `UserLoginPersistence` @Retryable 로직 불변(주석만 갱신) | worst ≈ 20.5s |
| #3 카카오 타임아웃 | `KakaoOAuthClient` 생성자에서 `kakao.callback.timeout-ms`(3000)를 `SimpleClientHttpRequestFactory` connect+read로 양쪽 RestClient에 배선 | 3000ms connect=read |
| #1 keep-warm | `NeonKeepWarmScheduler`(신규) `@ConditionalOnProperty(db.keep-warm.enabled)` **기본 OFF** + `@Scheduled` 4분 + 활성 윈도우 [07,23) KST + `SELECT 1`, 예외 삼킴. `application.yml`에 `db.keep-warm` 블록 | 기본 OFF (`DB_KEEP_WARM_ENABLED=true`로 활성화) |

검증: 컴파일 ✅ / `NeonKeepWarmSchedulerTest`(경계·예외 포함) ✅ / `AuthServiceTest`·`UserLoginPersistenceTest`·`EnvBindingTest`(빈 포트) ✅. (전체 스위트의 Testcontainers/포트 의존 IT 실패는 로컬 환경 이슈 — 본 변경 무관.)

활성화 절차: Neon 대시보드에서 잔여 월 컴퓨트 확인 → `DB_KEEP_WARM_ENABLED=true` 환경변수 설정 후 재기동 (또는 활성 시간대만). 한도(~191h/월) 초과 시 컴퓨트 소진으로 역효과 주의.

## 확정 검증 방법 (가설을 코드가 이미 측정 중)

구현 전, 아래로 콜드 스타트 초과 가설을 확정한다:

1. **앱 로그**: `login retry exhausted ... exception=CannotCreateTransactionException` (ERROR) — `LoginRetryListener.java:81`
2. **메트릭**: `auth.login.retry.exhausted`, `auth.login.retry.attempts{exception=CannotCreateTransactionException}`
3. **Neon 대시보드**: 실패 시각에 컴퓨트 suspend→wake 기록 / 잔여 월 컴퓨트 시간(한도 소진 시 영구 실패로 전환됨)
4. **Nginx/브라우저 네트워크**: `POST /api/v1/auth/kakao/callback`이 502로 떨어지는지

## 성공 기준

- 활성 시간대 첫 로그인 502 = 0건 (keep-warm로 콜드 스타트 자체 제거)
- 콜드 스타트가 발생하더라도(keep-warm 윈도우 밖) 첫 로그인 성공 (재시도 예산 내 흡수)
- 카카오 API 지연 시 워커 스레드 무한 대기 제거 (타임아웃 강제)
