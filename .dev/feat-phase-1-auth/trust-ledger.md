# Trust Ledger — Phase 1 auth

## 통합 감사 (phase-review)

### QA Manager
- **CERTAIN Critical**: 0건
- **Warning/Info (신규 3건)**:
  - [Warning] `BaseEntity.java:23` — `private final Long id = 0L` JPA 신규 엔티티 감지 영향 (Phase 0 공통)
  - [Warning] `AuthV1Controller.java:67-69` — logout에서 `JwtTokenProvider` 직접 주입 (레이어 경계)
  - [Info] `KakaoOAuthClient.java:56-59` — `catch (CoreException) { throw e; }` 패턴 중복
- **QUESTION (자기점검 이월 4건)**: 아래 ZT QUESTION과 별도 처리
- **AC 충족: 16/16**

### Security-Auditor (ZT 통합 감사)
- **CRITICAL**: 0건
- **HIGH 4건**:
  - [RISK/HIGH-1] `BaseEntity.id=0L` — JPA save() 전략 영향. 신규 엔티티가 `merge`로 처리되어 INSERT 대신 SELECT+INSERT. Phase 0 모듈 공통
    - 권고: `Persistable<Long>` 구현 또는 `id = null` 초기화
  - [RISK/HIGH-2] `AuthV1Controller.refresh` `@CookieValue(required=false)` — 누락 쿠키 감지가 컴파일 시점에 안 됨
    - 권고: `required=true` + `MissingRequestCookieException` 매핑 또는 컨트롤러 early-rejection
  - [GAP/HIGH-3] `AuthSecurityIT` 미존재 — AC-4/AC-5(보호 엔드포인트 401/200) + AC-15/AC-16(CORS preflight) 통합 검증 갭. 구현은 모두 확인됨
    - 권고: AuthV1ControllerIntegrationTest에 보안/CORS 케이스 추가
  - [RISK/HIGH-4] PRD 영향 범위 vs 코드 불일치 — example 도메인 완전 제거 후 PRD 영향 범위 문구 갱신 필요
- **MEDIUM 8건**: KakaoOAuthClient body null, dirty checking 비대칭, CoreException stacktrace 로그, dev/prod cookie.secure 명시, AC-15/16 통합 누락, state 파라미터, Cors `@DefaultValue`, architecture.md 미반영
- **LOW 4건**: BaseEntity getId() 항상 0L, logout access_token 없을 시 RT hash 잔존, ADR-0001 Redis 미사용 확인, EnvBindingTest withOverride prefix 충돌

### 미검증 가정 (ASSUMPTION)
- A-1: FE refresh 직렬화 (설계 명시) — OK
- A-2: 탈퇴자 access_token 1h 잔존 (설계 명시) — OK
- A-3: 카카오 응답 구조 안정성 — 외부 의존
- A-4: PostgresTestContainersConfig 단일 컨텍스트 공유 — shutdown hook 추가됨

### AC 충족 매핑
| AC | 충족 | 갭 |
|----|------|-----|
| AC-1, AC-2, AC-3 | 코드+IT | — |
| AC-4, AC-5 | 코드만 | IT 미존재 (HIGH-3) |
| AC-6, AC-7, AC-8, AC-9, AC-10 | 코드+IT | — |
| AC-11, AC-12, AC-13, AC-14 | 코드+IT | — |
| AC-15, AC-16 | 코드만 | IT 미존재 (HIGH-3) |
