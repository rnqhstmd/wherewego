## 배경

운영 중 카카오 로그인이 **유휴 후 첫 시도에서 간헐적으로 502**로 실패. 원인은 Neon 무료 티어 컴퓨트가 ~5분 유휴 시 suspend(scale-to-zero)되는데, 재시도 예산(worst-case 10.5s)이 Neon 콜드 스타트(보통 3~8s, P99 10s+)를 흡수하지 못하면 `AUTH_KAKAO_API_FAILED`(502)를 반환하는 구조. "안 되면 한 번 더 누르면 됨" 패턴이 콜드 스타트의 전형적 시그니처.

부가로 `KakaoOAuthClient`에 `kakao.callback.timeout-ms`(정의됨)가 실제로 배선되지 않아 카카오 지연 시 워커 스레드 무한 대기 위험.

상세 분석: `context/auth/phase-14-login-cold-start.md`

## 변경 사항

| # | 항목 | 내용 |
|---|------|------|
| 1 | 재시도 예산 확대 | `jpa.yml` prod `connection-timeout` 5000→10000, main `leak-detection-threshold` 15000→30000 (worst ≈ 20.5s). `UserLoginPersistence` @Retryable 로직 불변(주석만 갱신) |
| 2 | 카카오 타임아웃 배선 | `KakaoOAuthClient` 생성자에서 `kakao.callback.timeout-ms`(3000)를 `SimpleClientHttpRequestFactory` connect+read로 양쪽 RestClient에 적용 |
| 3 | keep-warm 스케줄러 | `NeonKeepWarmScheduler`(신규) — `@ConditionalOnProperty(db.keep-warm.enabled)` **기본 OFF**, `@Scheduled` 4분, 활성 윈도우 [07,23) KST에 `SELECT 1`, 예외 삼킴. `application.yml`에 `db.keep-warm` 블록 |

## 검증

- 컴파일 ✅ / `NeonKeepWarmSchedulerTest` 6종(윈도우 안·밖·경계 7시·23시·SQL예외·런타임예외) ✅ / `AuthServiceTest`·`UserLoginPersistenceTest` ✅ / `EnvBindingTest`(빈 포트) ✅
- 전체 스위트 일부 실패는 로컬 환경 이슈(포트 8080 점유 + Testcontainers/Docker 의존 IT)로 본 변경과 무관.

## 보안/품질 감사 (Trust Ledger 요약)

- **CRITICAL 0, HIGH 0.** 안정성 개선 목적, 새 공격 표면 없음. 시크릿 평문 없음, keep-warm SQL은 하드코딩 `SELECT 1`.
- [MEDIUM] connection-timeout 10s × bulkhead(5) 상호작용 — Neon 완전 장애 시 로그인 적체 가능(일반 API는 풀 격리). 소규모 운영 전제 수용.
- [MEDIUM] keep-warm 연속 실패 알림(FR-4) 미구현 — 기본 OFF라 영향 없음, 활성화 시 후속.

## 활성화 안내 (keep-warm)

기본 OFF. Neon 대시보드에서 **잔여 월 컴퓨트(~191h/월)** 확인 후 `DB_KEEP_WARM_ENABLED=true` 환경변수로 활성화. 24h 상시 핑은 한도 초과 위험 → 활성 시간대([07,23) KST 기본) 권장.

## 후속 과제

1. keep-warm 활성화 시 FR-4(연속 실패 Slack 알림) + 컴퓨트 소비 모니터링
2. Neon 완전 장애 시 로그인 적체 운영 가이드
3. (선택) bulkhead permit·connection-timeout 동반 튜닝

🤖 Generated with [Claude Code](https://claude.com/claude-code)
