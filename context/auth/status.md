# auth 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현

## 요구사항

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-AUTH-1 | 카카오 OAuth2 로그인 (인가 코드 직접 처리 방식) | ✅ | [#3](https://github.com/rnqhstmd/wherewego/pull/3) |
| FR-AUTH-2 | 카카오 사용자 정보 → users 테이블 upsert (최소 세트) | ✅ | [#3](https://github.com/rnqhstmd/wherewego/pull/3) |
| FR-AUTH-3 | JWT Access Token 발급 (TTL 1h, typ=access + jti claim) | ✅ | [#3](https://github.com/rnqhstmd/wherewego/pull/3) |
| FR-AUTH-4 | JWT Refresh Token 발급/저장 (TTL 14d, SHA-256 해시 저장) | ✅ | [#3](https://github.com/rnqhstmd/wherewego/pull/3) |
| FR-AUTH-5 | JWT 검증 필터 (Spring Security Stateless, 쿠키 access_token 추출) | ✅ | [#3](https://github.com/rnqhstmd/wherewego/pull/3) |
| FR-AUTH-6 | 로그아웃 (Refresh Token DB 폐기, Max-Age=0 쿠키, 멱등) | ✅ | [#3](https://github.com/rnqhstmd/wherewego/pull/3) |

## 운영 버그 수정

| 항목 | 상태 | 상세 |
|------|------|------|
| 카카오 로그인 JPA 커넥션 풀 고갈 | ✅ | `KakaoOAuth2UserService`의 DB 조회 로직을 별도 `UserLoginPersistence` Spring Bean으로 분리 — 트랜잭션 경계 명확화로 커넥션 조기 반환. HikariCP `maximum-pool-size: 10` 명시. [PR #54](https://github.com/rnqhstmd/wherewego/pull/54) |
| 카카오 로그인 간헐적 502 (Neon 콜드 스타트) | ⬜ | 유휴 후 첫 로그인이 Neon suspend 콜드 스타트로 재시도 예산(~10.5s) 초과 → `AUTH_KAKAO_API_FAILED`(502). 잠재 버그: `KakaoOAuthClient` 타임아웃 미배선. **다음 phase 구현** → [phase-14-login-cold-start.md](phase-14-login-cold-start.md) |

## 다음 phase

| Phase | 범위 | 상태 |
|-------|------|------|
| [Phase 14 — 로그인 콜드 스타트 안정화](phase-14-login-cold-start.md) | Neon keep-warm 스케줄러 + 재시도 예산 확대 + 카카오 OAuth 타임아웃 배선 (전체 3종) | ⬜ 미시작 (원인 분석 완료) |
