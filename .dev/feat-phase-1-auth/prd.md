# PRD: 카카오 소셜 로그인 및 JWT 인증 구현 (Phase 1 — auth 도메인)

## 배경

- wherewego는 현재 인증 체계가 없는 상태(Phase 0 기반 인프라만 구축됨)로, 사용자 식별이 필요한 모든 기능(핀 등록, 그룹 관리 등)을 제공하기 위해 로그인 기능이 필요하다.
- DB에 `users` 테이블(`kakao_user_id`, `nickname`, `profile_image_url`, `refresh_token` 컬럼)이 이미 정의되어 있고, JWT 설정(`secret`, `accessTtlSeconds`, `refreshTtlSeconds`)과 카카오 OAuth 설정(`clientId`, `clientSecret`, `redirectUri`)이 환경변수로 관리될 구조가 갖춰져 있다.
- 프론트엔드(Vercel)와 백엔드(EC2)가 별도 도메인으로 분리된 **Cross-domain** 구성이며, 카카오 인가 코드 처리는 프론트엔드가 수행하고 백엔드로 전달하는 방식이다.
- 토큰은 **httpOnly 쿠키**(`SameSite=None`; `Secure`)로 전달하여 XSS로부터 보호한다.

## 목표

- 카카오 계정으로 로그인하면 서비스 이용이 가능하다.
- 토큰이 만료되어도 사용자가 재로그인 없이 서비스를 계속 이용한다.
- 카카오 `client_id` 등 민감 정보가 프론트엔드에 노출되지 않는다.

## 요구사항

### 기능 요구사항

- **[Must] FR-1**: 백엔드는 카카오 인가 페이지 URL을 생성하여 프론트엔드에 제공한다. (`client_id`는 백엔드에서만 보관)
- **[Must] FR-2**: 프론트엔드가 카카오로부터 받은 인가 코드를 백엔드에 전달하면, 백엔드가 카카오 토큰 교환 및 사용자 정보 조회를 처리한다.
- **[Must] FR-3**: 최초 로그인 시 `users` 테이블에 신규 사용자를 생성(`kakao_user_id`, `nickname`, `profile_image_url`)하고, 재로그인 시 `nickname`과 `profile_image_url`을 최신 카카오 정보로 갱신한다.
- **[Must] FR-4**: 로그인 성공 시 `access_token`과 `refresh_token`을 httpOnly 쿠키(`Set-Cookie` 헤더)로 응답하고, 사용자 정보(`id`, `nickname`, `profileImageUrl`)를 JSON 바디로 함께 반환한다.
- **[Must] FR-5**: 보호된 API 엔드포인트는 쿠키의 `access_token`을 검증하여 인증된 요청만 처리한다.
- **[Must] FR-6**: `refresh_token` 쿠키로 재발급 요청 시 `access_token`과 `refresh_token`을 모두 신규 발급(**Rotation**)하여 쿠키로 응답하고, DB의 `refresh_token`을 신규 값으로 교체한다.
- **[Must] FR-7**: 로그아웃 요청 시 쿠키를 만료(`Max-Age=0`)시키고 DB의 `refresh_token`을 NULL로 초기화한다.

### 비즈니스 규칙

- **[Must] BR-1**: 동일 카카오 계정의 중복 가입은 허용하지 않는다. `kakao_user_id` 기준으로 기존 사용자를 식별한다.
- **[Must] BR-2**: `access_token` 유효 기간은 1시간(3600초), `refresh_token` 유효 기간은 14일(1,209,600초)이다.
- **[Must] BR-3**: DB에 저장된 `refresh_token`과 일치하지 않는 `refresh_token`으로 재발급 요청 시 거부한다. (탈취된 토큰 재사용 방지)
- **[Must] BR-4**: 모든 토큰 쿠키는 `HttpOnly; Secure; SameSite=None; Path=/` 속성을 가진다. (개발 환경 localhost는 `Secure` 예외 처리 가능)
- **[Must] BR-5**: CORS는 허용된 프론트엔드 도메인(환경변수)만 허용하며, `allow-credentials=true`를 설정한다.
- **[Should] BR-6**: 탈퇴(`deleted_at`이 NULL이 아닌) 사용자의 로그인 시도는 거부한다.

### 품질 기대

- **[Should] QE-1**: 카카오 서버 장애 시 사용자에게 "카카오 로그인을 일시적으로 사용할 수 없습니다" 수준의 오류 응답이 제공된다.
- **[Should] QE-2**: 만료된 `access_token`으로 API 호출 시 클라이언트가 재발급 흐름으로 전환할 수 있도록 401 응답이 반환된다.

## 사용자 시나리오

### 정상 흐름 — 신규 사용자 첫 로그인

1. 프론트엔드가 `GET /api/v1/auth/kakao/login-url` 호출 → 카카오 인가 페이지 URL 수신
2. 사용자가 카카오 로그인 동의 → 프론트엔드가 인가 코드(`code`) 수신
3. 프론트엔드가 `POST /api/v1/auth/kakao/callback`에 `{code}` 전달
4. 백엔드가 카카오 토큰 교환 → 사용자 정보 조회 → `users` 테이블에 신규 생성
5. `Set-Cookie`(`access_token`, `refresh_token`) + 사용자 정보 JSON 응답
6. 이후 API 요청 시 쿠키 자동 포함 → 인증 통과

### 정상 흐름 — 토큰 재발급

1. `access_token` 만료로 보호 API에서 401 응답
2. 프론트엔드가 `POST /api/v1/auth/token/refresh` 호출 (`refresh_token` 쿠키 자동 포함)
3. 신규 `access_token` + `refresh_token` `Set-Cookie` 응답
4. 원래 요청 재시도

### 예외 흐름 — 유효하지 않은 refresh_token

1. 재발급 요청 시 DB와 불일치하는 `refresh_token` → 401 응답
2. 프론트엔드가 로그인 화면으로 이동

### 엣지 케이스

- 카카오 측에서 `nickname`/`profile_image_url` 변경 시 → 재로그인 시 최신 정보로 갱신
- 이미 로그인된 상태에서 재로그인 시 → `refresh_token` 교체, 기존 세션 무효화
- 허용되지 않은 origin에서 요청 시 → CORS preflight 거부

## API 엔드포인트

| 메서드 | 경로 | 인증 | 본문 / 응답 |
|--------|------|------|------------|
| GET | `/api/v1/auth/kakao/login-url` | 없음 | 응답: `{loginUrl: string}` (카카오 인가 페이지 URL) |
| POST | `/api/v1/auth/kakao/callback` | 없음 | 본문: `{code: string}` / 응답: `Set-Cookie` 2건 + `{id, nickname, profileImageUrl}` JSON |
| POST | `/api/v1/auth/token/refresh` | `refresh_token` 쿠키 | 응답: `Set-Cookie` 2건 (Rotation) |
| POST | `/api/v1/auth/logout` | `access_token` 쿠키 | 응답: `Set-Cookie Max-Age=0` 2건 |

### 쿠키 속성 (모든 토큰 쿠키 공통)

| 속성 | 값 |
|------|----|
| HttpOnly | 필수 |
| Secure | 필수 (dev localhost 예외 가능) |
| SameSite | None |
| Path | `/` |
| Domain | 환경별 설정 (`.wherewego.com` 또는 백엔드 도메인) |
| Max-Age | `access_token`: 3600 / `refresh_token`: 1,209,600 |

### CORS 설정

| 항목 | 값 |
|------|----|
| allowed-origins | 프론트엔드 도메인 (환경변수) |
| allow-credentials | true |
| allowed-methods | GET, POST, PUT, DELETE, OPTIONS |
| allowed-headers | Authorization, Content-Type |

## 영향 범위

- 현재 인증 없이 동작하는 `ExampleV1Controller`를 제외한 모든 보호 엔드포인트에 JWT 쿠키 검증이 적용된다.
- Spring Security 필터가 Authorization 헤더가 아닌 쿠키에서 `access_token`을 추출한다.
- Cross-domain 환경에서 `withCredentials=true` 없이 호출하는 기존 클라이언트 코드는 인증 실패한다.

## 수용 기준

- **AC-1**: 카카오 로그인 성공 응답에 `Set-Cookie` 헤더로 `access_token`(`HttpOnly; Secure; SameSite=None; Max-Age=3600`)과 `refresh_token`(`HttpOnly; Secure; SameSite=None; Max-Age=1,209,600`)이 포함된다. → [FR-4, BR-4]
- **AC-2**: 신규 사용자 로그인 후 `users` 테이블에 해당 `kakao_user_id`로 레코드가 생성된다. → [FR-3, BR-1]
- **AC-3**: 동일 `kakao_user_id`로 재로그인 시 `users` 테이블 레코드가 중복 생성되지 않고 `nickname`, `profile_image_url`이 갱신된다. → [FR-3, BR-1]
- **AC-4**: 쿠키의 `access_token`으로 보호 API 요청 시 200 응답을 받는다. → [FR-5]
- **AC-5**: 유효하지 않은 `access_token`(변조 또는 만료)으로 보호 API 요청 시 401 응답을 받는다. → [FR-5, QE-2]
- **AC-6**: `GET /api/v1/auth/kakao/login-url` 응답에 카카오 인가 페이지 URL이 포함되고, 응답 바디 또는 헤더에 `client_id`가 노출되지 않는다. → [FR-1]
- **AC-7**: 쿠키의 `refresh_token`으로 재발급 요청 시, 신규 토큰 쿠키 2건이 `Set-Cookie`로 전달되고 DB의 `refresh_token`이 신규 값으로 교체된다. → [FR-6, BR-2, BR-3]
- **AC-8**: DB에 저장된 값과 다른 `refresh_token`으로 재발급 요청 시 401 응답을 받는다. → [BR-3]
- **AC-9**: 로그아웃 요청 후 응답에 `Max-Age=0`인 `access_token`, `refresh_token` `Set-Cookie`가 포함되고, DB의 `refresh_token`이 NULL이 된다. → [FR-7]
- **AC-10**: 카카오 서버 오류 시 500이 아닌 적절한 오류 응답(4xx/5xx)과 메시지가 반환된다. → [QE-1]
- **AC-11**: `access_token` 유효 기간이 1시간(3600초)임이 쿠키 `Max-Age` 속성으로 확인된다. → [BR-2]
- **AC-12**: `refresh_token` 유효 기간이 14일(1,209,600초)임이 쿠키 `Max-Age` 속성으로 확인된다. → [BR-2]
- **AC-13**: `deleted_at`이 설정된 사용자로 로그인 시도 시 인증이 거부된다. → [BR-6]
- **AC-14**: `POST /api/v1/auth/kakao/callback` 요청 본문에 `code`가 없거나 빈 값이면 400 응답을 반환한다. → [FR-2]
- **AC-15**: Cross-origin 요청에서 `withCredentials=true`로 호출 시 쿠키가 정상 전달되어 인증된다. → [BR-5, FR-5]
- **AC-16**: 허용되지 않은 origin에서 호출 시 CORS preflight가 거부된다. → [BR-5]

## 제외 범위

- 카카오 외 다른 소셜 로그인 (Google, Naver 등)
- 이메일/패스워드 기반 로그인
- 2단계 인증(MFA)
- 관리자 전용 인증 체계
- 토큰 블랙리스트(Redis 기반 즉시 무효화) — `refresh_token` DB 저장 방식으로 대체
- 프론트엔드 클라이언트 구현 (이 PRD는 백엔드 API 범위만 다룸)
