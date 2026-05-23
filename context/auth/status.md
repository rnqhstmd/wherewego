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
