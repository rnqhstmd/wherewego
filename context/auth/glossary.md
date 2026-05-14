# auth 용어 사전

| 용어 | 설명 |
|------|------|
| 카카오 OAuth2 | 카카오 계정으로 인증하는 OAuth 2.0 프로토콜 구현 |
| kakao_user_id | 카카오가 발급하는 사용자 고유 식별자 (Long). users 테이블의 UNIQUE 키 |
| access_token (Kakao) | 카카오 API 호출용 토큰. 서비스에서는 로그인 시점에만 사용 |
| Access Token (JWT) | 서비스 자체 발급 JWT. TTL 1시간. API 호출 인증에 사용 |
| Refresh Token (JWT) | JWT 갱신용 토큰. TTL 14일. `users.refresh_token`에 저장 |
| Stateless 세션 | 서버 메모리/Redis에 세션 상태를 두지 않고 JWT만으로 인증하는 방식 |
| users 최소 세트 | `kakao_user_id`, `nickname`, `profile_image_url`, `created_at` (개인정보 최소화) |
