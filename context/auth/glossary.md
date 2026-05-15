# auth 용어 사전

| 용어 | 설명 |
|------|------|
| 카카오 OAuth2 | 카카오 계정으로 인증하는 OAuth 2.0 프로토콜. **인가 코드 직접 처리 방식** (Spring Security OAuth2 Client 미사용) |
| kakao_user_id | 카카오가 발급하는 사용자 고유 식별자 (Long). users 테이블의 UNIQUE 키 |
| access_token (Kakao) | 카카오 API 호출용 토큰. 서비스에서는 로그인 시점 사용자 정보 조회에만 사용 |
| Access Token (JWT) | 서비스 자체 발급 JWT. TTL 1시간. payload: `{sub, typ:"access", jti, iat, exp}`. 보호 API 인증에 사용 |
| Refresh Token (JWT) | JWT 갱신용 토큰. TTL 14일. payload: `{sub, typ:"refresh", jti, iat, exp}`. `users.refresh_token`에 **SHA-256 해시(hex)**로 저장 |
| typ claim | JWT payload의 토큰 타입 식별자 (`access` 또는 `refresh`). 토큰 혼용 공격(refresh를 access 쿠키에 넣는 시도) 방어용 |
| jti claim | JWT ID (UUID). 동일 second 내 재발급 시에도 토큰을 unique하게 만들어 Rotation 정합성을 보장 |
| Refresh Token Rotation | 재발급 시마다 access + refresh를 모두 신규 발급하고 DB의 refresh_token 해시를 교체하는 정책. 탈취된 refresh의 재사용을 1회로 제한 |
| Stateless 세션 | 서버 메모리/Redis에 세션 상태를 두지 않고 JWT만으로 인증하는 방식 |
| users 최소 세트 | `kakao_user_id`, `nickname`, `profile_image_url`, `created_at` (개인정보 최소화) |
| httpOnly 쿠키 | JavaScript에서 접근 불가한 쿠키. XSS 방어 |
| SameSite=None + Secure | Cross-domain(Vercel↔EC2) 환경에서 쿠키가 cross-site 요청에 포함되도록 하는 조합. Chrome ≥80 정책에 따라 Secure=true와 함께 사용 필수 |
| CORS allow-credentials | `Access-Control-Allow-Credentials: true`. FE의 `withCredentials=true` 요청에 쿠키 포함을 서버가 허용 |
| AUTH_KAKAO_API_FAILED | 카카오 API 4xx/5xx 응답을 502 Bad Gateway로 일관 매핑하는 에러 코드 |
| FE 직렬화 계약 | FE가 refresh 요청을 단일 in-flight + 대기 큐 패턴으로 직렬화한다는 백엔드의 가정. 계약 위반 시 강제 로그아웃 가능 |
