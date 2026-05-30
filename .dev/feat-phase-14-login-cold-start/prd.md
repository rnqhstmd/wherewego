# PRD: Phase 14 — 로그인 콜드 스타트 안정화

## 배경
카카오 로그인이 유휴 후 첫 시도에서 간헐적으로 502 실패. 원인은 Neon 무료 티어 컴퓨트가 ~5분 유휴 시 suspend(scale-to-zero)되는데, 재시도 예산(현 worst-case 10.5s)이 Neon 콜드 스타트(보통 3~8s, P99 10s+)를 흡수 못 하면 `AUTH_KAKAO_API_FAILED`(502)를 반환하는 구조. 부가로 `KakaoOAuthClient`에 타임아웃이 미배선되어 카카오 지연 시 워커 스레드 무한 대기 위험.

근거 문서: `context/auth/phase-14-login-cold-start.md` (원인 분석·실패 경로·검증 방법).

## 요구사항

### Must
- **FR-1 (keep-warm 스케줄러)**: 활성 시간대에 `SELECT 1`을 주기 실행해 Neon suspend 방지. **env-gated, 기본 OFF**. 예외는 로그 후 삼키고 다음 주기 재실행.
- **FR-2 (재시도 예산 확대)**: prod `connection-timeout` 10000ms, `@Retryable maxAttempts` 2 유지 → worst-case ≈ 20.5s. `leak-detection-threshold` 30000ms로 상향(예산 확대에 맞춤).
- **FR-3 (카카오 타임아웃 배선)**: `kakao.callback.timeout-ms`(3000)를 `KakaoOAuthClient`의 `tokenClient`·`userClient` 양쪽 RestClient connect+read에 적용.

### 비즈니스 규칙
- BR-1: keep-warm은 무료 컴퓨트 한도(~191h/월) 초과 방지 위해 24h 핑 금지. 활성 윈도우(기본 07–23 KST) 제한.
- BR-2: keep-warm 핑 주기 < Neon suspend 임계값(~5분). 기본 4분.
- BR-3: 재시도 예산 확대 후에도 흡수 실패 시 기존 502 + "잠시 후 다시 로그인해 주세요" 유지.
- BR-4: 카카오 타임아웃 초과 시 기존 `RestClientException` → `AUTH_KAKAO_API_FAILED` 경로 유지.

## 확정된 결정 (사용자 승인)
| 항목 | 값 |
|------|-----|
| 재시도 예산 | 약 20초 (connection-timeout 10000ms + maxAttempts 2 + backoff 500ms) |
| leak-detection-threshold | 15000 → 30000ms |
| 카카오 타임아웃 | 단일 3000ms를 connect+read 양쪽 동일 적용 |
| keep-warm 활성 정책 | env-gated 기본 OFF, 윈도우 07–23 KST, 주기 4분 (켜면 동작) |
| connection-timeout 변경 범위 | prod 프로필만 (Neon). local/dev/test 미변경 |

## 수용 기준
- AC-1: prod `connection-timeout`=10000, `leak-detection-threshold`=30000으로 변경. 관련 주석(타이밍 math) 갱신.
- AC-2: `KakaoOAuthClient`가 `kakao.callback.timeout-ms`를 양쪽 RestClient에 적용. 정상 응답(≤3s)은 동작 변화 없음, 초과 시 502.
- AC-3: keep-warm 스케줄러가 `enabled=false`(기본)일 때 미동작. `enabled=true` + 활성 윈도우일 때만 `SELECT 1` 실행. 예외 발생해도 앱 정상.
- AC-4: 빌드/테스트 통과. 기존 auth 테스트(AuthServiceTest, UserLoginPersistence(Retry)IT 등) 회귀 없음.
- AC-5: `auth.login.retry.exhausted` 메트릭/로그로 DB 콜드스타트 기인 실패 식별 가능(기존 유지).

## 제외 범위
- Neon 유료화/DB 교체, HikariCP `minimum-idle` 상향, 프론트엔드 변경, `AUTH_KAKAO_API_FAILED` 코드명 변경.

## 엣지케이스
- 재시도 예산 초과 → 502 유지(BR-3).
- keep-warm 핑 실패 → 로그 후 삼킴, 다음 주기 재실행.
- Neon 월 컴퓨트 한도 소진 → 범위 밖(운영자 대시보드 모니터링).
- 동시 첫 로그인 race → 기존 `DataIntegrityViolationException` @Retryable 경로 유지.
