# auth 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현

## 요구사항

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-AUTH-1 | 카카오 OAuth2 로그인 (Spring Security OAuth2 Client) | ⬜ | |
| FR-AUTH-2 | 카카오 사용자 정보 → users 테이블 upsert (최소 세트) | ⬜ | |
| FR-AUTH-3 | JWT Access Token 발급 (TTL 1h) | ⬜ | |
| FR-AUTH-4 | JWT Refresh Token 발급/저장 (TTL 14d) | ⬜ | |
| FR-AUTH-5 | JWT 검증 필터 (Spring Security) | ⬜ | |
| FR-AUTH-6 | 로그아웃 (Refresh Token 폐기) | ⬜ | |
