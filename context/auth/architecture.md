# auth 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

- 클라이언트: Next.js → 자체 SPA가 카카오 인가 페이지로 리다이렉트 (state 파라미터 FE 책임) → 인가 코드 수신 후 백엔드 `POST /api/v1/auth/kakao/callback`에 전달
- 백엔드: Spring Boot + Spring Security Stateless. **OAuth2 Client 미사용 — 인가 코드 직접 처리 방식** (`KakaoOAuthClient`가 RestClient로 카카오 토큰/사용자 API를 직접 호출)
- 세션 방식: **JWT (Stateless)** + **httpOnly 쿠키 전달** (Cross-domain: Vercel ↔ EC2)
  - Access Token: 1시간 TTL (`typ=access`, `jti=UUID` claim)
  - Refresh Token: 14일 TTL (`typ=refresh`, `jti=UUID` claim), users 테이블에 **SHA-256 해시(hex)** 저장 (DB 덤프 유출 시 활성 세션 탈취 방어)
  - 쿠키 속성: `HttpOnly; Secure; SameSite=None; Path=/` (local 프로파일은 `Secure=false; SameSite=Lax` fallback)
- 사용자 테이블 (`users`):
  - `id` (PK, BIGSERIAL)
  - `kakao_user_id` (UNIQUE, Long)
  - `nickname` (VARCHAR(100), NOT NULL)
  - `profile_image_url` (TEXT, nullable)
  - `refresh_token` (TEXT, nullable) — **JWT 원본이 아닌 SHA-256 해시(hex) 저장**
  - `created_at`, `updated_at`, `deleted_at` (TIMESTAMPTZ, BaseEntity 자동 관리)
- 챗봇 연동: [[chatbot]] 도메인이 `botUserKey ↔ user_id` 매핑을 별도 테이블에 저장. auth는 user_id 식별까지만 책임

## 프론트엔드 계약 (Phase 1)

- **Refresh 직렬화**: FE는 `access_token` 만료(401) 감지 시 `/api/v1/auth/token/refresh` 호출을 단일 in-flight + 대기 큐 패턴으로 직렬화한다. 다수의 동시 401에도 refresh 1회만 전송. 계약 위반 시 BR-3 단순 검사로 일부 요청이 강제 로그아웃될 수 있음 (사용자는 재로그인으로 복구).
- **CORS**: 모든 API 요청에 `credentials: 'include'` (axios `withCredentials: true`).
- **OAuth2 state**: FE가 생성/검증 (BE는 인가 URL만 제공). Phase 2 Redis 도입 후 BE 강화 검토.
- **로그아웃 멱등**: `/api/v1/auth/logout`은 access_token 유무/만료와 무관하게 항상 `Set-Cookie Max-Age=0` 2건 반환.

## 알려진 리스크 / 가정

- **탈퇴자 잔존 리스크**: `JwtAuthenticationFilter`가 매 요청 DB 조회를 하지 않으므로, `deleted_at` 설정 직후에도 access_token이 유효한 동안(최대 1시간) 보호 API 통과. Refresh/Login 시점에 `isActive()` 검사로 차단. 즉시 차단이 필요해지면 Phase 후반에서 Redis 블랙리스트 도입 검토.
- **Refresh Token Rotation race**: 백엔드는 BR-3(DB 해시 일치)만 검사. 동시 refresh가 발생하면 일부 강제 로그아웃 가능 (FE 직렬화 계약으로 방어).
- **Neon 콜드 스타트로 인한 로그인 간헐 실패**: Neon 무료 티어가 ~5분 유휴 시 컴퓨트를 suspend(scale-to-zero)하고 prod `minimum-idle: 0` + keep-warm 부재로, 유휴 후 첫 로그인은 매번 콜드 스타트를 유발한다. 콜드 스타트가 재시도 예산(~10.5s)을 넘기면 `AUTH_KAKAO_API_FAILED`(502)로 실패. → [phase-14-login-cold-start.md](phase-14-login-cold-start.md)에서 keep-warm + 재시도 예산 + 카카오 타임아웃으로 해소 예정.
- **카카오 OAuth 클라이언트 타임아웃 미배선 (잠재 버그)**: `kakao.callback.timeout-ms: 3000`이 정의돼 있으나 `KakaoOAuthClient`가 `RestClient`에 연결하지 않아 실제 타임아웃이 없다. 카카오 API 지연 시 워커 스레드 무한 대기. Phase 14에서 함께 수정.

## 주제 문서

| 주제 | 설명 |
|------|------|
| (Phase 1 산출물) | `.dev/feat-phase-1-auth/prd.md`, `design.md`, `trust-ledger.md` |
| [Phase 14 — 로그인 콜드 스타트 안정화](phase-14-login-cold-start.md) | 카카오 로그인 간헐 502 원인 분석 + Neon keep-warm·재시도 예산·카카오 타임아웃 구현 계획 (⬜ 미시작) |
