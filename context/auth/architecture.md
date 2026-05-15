# auth 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

- 클라이언트: Next.js → `/login/kakao` → 카카오 인가 페이지 리다이렉트
- 백엔드: Spring Boot + Spring Security OAuth2 Client (kakao provider)
- 세션 방식: **JWT (Stateless)**
  - Access Token: 1시간 TTL, 클라이언트 메모리 또는 httpOnly 쿠키 저장
  - Refresh Token: 14일 TTL, Supabase `users` 테이블에 저장
- 사용자 테이블 (`users`):
  - `id` (PK, BIGSERIAL)
  - `kakao_user_id` (UNIQUE, Long)
  - `nickname` (VARCHAR)
  - `profile_image_url` (VARCHAR, nullable)
  - `refresh_token` (VARCHAR, nullable)
  - `created_at` (TIMESTAMP)
- 챗봇 연동: [[chatbot]] 도메인이 `botUserKey ↔ user_id` 매핑을 별도 테이블에 저장. auth는 user_id 식별까지만 책임

## 주제 문서

| 주제 | 설명 |
|------|------|
