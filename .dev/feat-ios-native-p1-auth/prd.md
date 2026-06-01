# PRD: P1 — 백엔드 인증 확장 (additive, 웹 무중단)

> 작성: product-owner / 확정: 2026-06-01. iOS 네이티브 전환 로드맵 Phase P1.
> 베이스 develop. 전부 **additive**(라이브 웹 무중단). 설계 골격: `.dev/feat-ios-native-swiftui/{roadmap,plan}.md`.

## 확정된 결정 (Q&A)
- **Q1 refresh 경로**: `POST /api/v1/auth/refresh`(body 방식) **신규 추가**, 기존 `/api/v1/auth/token/refresh`(쿠키) 유지.
- **Q2 계정 통합**: **하지 않음.** 같은 사람이 Kakao·Apple로 가입하면 **별도 계정 2개**. (현재 email 미수집 + 단일 아이덴티티 모델. 통합은 후속 Phase 별도 판단.)
- **Q3 Apple 닉네임**: 최초 로그인 시 `fullName` 있으면 닉네임으로 저장, 없으면 **임시 닉네임**(예: "Apple 사용자") 생성 후 P3 온보딩에서 변경 유도.
- **Q4 oauth_id 타입**: `VARCHAR(255)`.

## 배경
현재 인증은 **Kakao 단일 OAuth + 인가코드 직접 처리**, JWT를 httpOnly 쿠키로만 전달. `JwtAuthenticationFilter`는 쿠키 외 전달 수단을 못 읽고, `users`는 `kakao_user_id BIGINT UNIQUE NOT NULL` 단독 식별 구조. iOS 네이티브 앱은 `Authorization: Bearer` 헤더 + Kakao SDK access token + Apple `identityToken`을 사용하므로 현재 구조로는 인증을 시작할 수 없다. 이 Phase는 Bearer 헤더 인증·Kakao/Apple 네이티브 로그인·앱용 갱신·계정 모델 일반화를 **additive**로 추가한다(기존 쿠키 웹 플로우 무변화·병행).

## 목표
- 앱이 Kakao 또는 Apple로 로그인해 우리 JWT 발급.
- Bearer 헤더 JWT가 쿠키와 동일하게 인증.
- 웹의 기존 쿠키 인증·로그인·갱신 회귀 0.
- 신규 엔드포인트·컬럼 전부 additive → 라이브 배포 즉시 안전.

## 요구사항

### 기능 [Must]
- **FR-1** `JwtAuthenticationFilter`에 `Authorization: Bearer <token>` 헤더 분기 추가. 헤더 존재 시 헤더 우선, 없으면 기존 `access_token` 쿠키. 쿠키-only 동작은 현행 동일.
- **FR-2** `POST /api/v1/auth/kakao/native` — 요청 `{"kakaoAccessToken":"..."}`. Kakao `/v2/user/me` 검증 → `(KAKAO, kakaoId)` find-or-create → JWT 발급. 응답 `{"accessToken","refreshToken","expiresIn"}`. **Set-Cookie 미설정.**
- **FR-3** `POST /api/v1/auth/apple/native` — 요청 `{"identityToken","nonce","authorizationCode","fullName":{givenName,familyName},"email"}`. Apple JWKS 서명 검증 + `iss`(https://appleid.apple.com)/`aud`(번들ID)/`nonce`/`exp` 검증 → `sub`로 `(APPLE, sub)` find-or-create → JWT. **Set-Cookie 미설정.**
- **FR-4** Apple private relay 이메일·이름(`fullName`)은 **최초 1회만**(신규 계정 생성 시) 저장. 재로그인 시 클라이언트가 null 전송해도 기존 값 미덮어쓰기. (Apple Guideline 4.8)
- **FR-5** `POST /api/v1/auth/refresh` — 요청 body `{"refreshToken":"..."}`. Refresh Token Rotation: 유효 시 새 access/refresh 쌍 발급 + DB 해시 갱신. 응답 `{"accessToken","refreshToken","expiresIn"}`. **Set-Cookie 미설정.** 기존 `/api/v1/auth/token/refresh`(쿠키) 무변경 유지.
- **FR-6** Flyway **V014**: `users`에 `oauth_provider VARCHAR(20) NOT NULL DEFAULT 'KAKAO'` + `oauth_id VARCHAR(255) NOT NULL` 추가. 기존 행 백필 `oauth_provider='KAKAO'`, `oauth_id=kakao_user_id::text`. `(oauth_provider, oauth_id)` UNIQUE 제약 추가. **`kakao_user_id` 컬럼·기존 UNIQUE 제약은 유지**(하위호환).
- **FR-7** `UserModel`에 `oauthProvider`, `oauthId` 필드 추가 + `UserRepository.findByOauthProviderAndOauthId(provider, id)`. 네이티브 로그인 find-or-create는 이 메서드 사용. 기존 `findByKakaoUserId` 유지.

### 기능 [Should]
- **FR-8** Apple JWKS는 `https://appleid.apple.com/auth/keys`에서 조회, 캐싱 + TTL 만료 시 재조회(키 교체 대비).
- **FR-9** Apple 번들 ID(aud)·JWKS URL을 설정(`AppleAuthProperties` 등)으로 외부화.

### 비즈니스 규칙 [Must]
- **BR-1** 헤더·쿠키 동시 존재 시 헤더 우선. 둘 다 없으면 인증 없이 통과(현행).
- **BR-2** Bearer 검증 실패(만료/위변조/타입 불일치) 시 `SecurityContextHolder` 클리어 후 체인 계속. 401은 인증 필요 엔드포인트에서 EntryPoint가 반환(현행).
- **BR-3** Kakao 네이티브 로그인은 `kakaoAccessToken`으로 `/v2/user/me` 직접 검증. Kakao API 실패 시 `AUTH_KAKAO_API_FAILED`(502).
- **BR-4** Apple `identityToken`은 JWKS 서명 검증. `iss/aud/nonce/exp` 중 하나라도 불일치·만료 시 `AUTH_APPLE_TOKEN_INVALID`(401).
- **BR-5** nonce 검증: 클라이언트 전송 `nonce` 평문의 `SHA-256`이 토큰 `nonce` 클레임과 일치해야 함.
- **BR-6** 탈퇴자(`deleted_at IS NOT NULL`) 네이티브 로그인 시 `AUTH_USER_DEACTIVATED`(401).
- **BR-7** `/auth/refresh`의 refresh token은 body 수신. `typ=refresh` 불일치/만료/위변조/DB 해시 불일치 시 `AUTH_REFRESH_TOKEN_INVALID`(401).
- **BR-8** Rotation: refresh 성공 시 기존 refresh 즉시 무효화 + 새 쌍 발급. 사용된 토큰 재사용 시 `AUTH_REFRESH_TOKEN_INVALID`(401).
- **BR-9** Apple 이메일·이름은 신규 계정 생성 시에만 저장. 재로그인 시 전송돼도 저장 값 불변.
- **BR-10** V014는 기존 `kakao_user_id`를 무손실로 `oauth_id` 복사. `kakao_user_id` 컬럼 유지.
- **BR-11** 동시 refresh(동일 토큰 중복) 시 DB 해시 불일치로 두 번째는 `AUTH_REFRESH_TOKEN_INVALID`. 분산 락 없이 last-writer-wins(현행 동일).
- **BR-12** Apple 최초 로그인 닉네임: `fullName` 있으면 `givenName+familyName`으로 닉네임, 없으면 임시 닉네임(예: "Apple 사용자") 생성. P3 온보딩에서 변경. (Q3)

### 품질 기대 [Should]
- **QE-1** 기존 쿠키 웹 클라이언트는 배포 후 로그인·갱신·인증 체감 변화 없음.
- **QE-2** Apple JWKS 네트워크 오류는 `AUTH_APPLE_TOKEN_INVALID`(401)와 구분해 502/503으로 응답(클라이언트 재시도 판단 가능).

## 수용 기준
- **AC-1** 유효 Bearer access → 인증 필요 엔드포인트 200 [FR-1,BR-1]
- **AC-2** 쿠키-only 기존 웹 요청 동일 인증 [FR-1,BR-1,QE-1]
- **AC-3** 헤더·쿠키 동시 시 헤더 토큰으로 인증 [BR-1]
- **AC-4** 만료 Bearer → 401 [BR-2]
- **AC-5** 위변조 Bearer → 401 [BR-2]
- **AC-6** 유효 Kakao access token → `/auth/kakao/native` 응답 + Set-Cookie 없음 [FR-2]
- **AC-7** 동일 Kakao 2회 호출 → 계정 중복 생성 없음, 각각 새 JWT [FR-2]
- **AC-8** 위변조 Kakao token → 502 `AUTH_KAKAO_API_FAILED` [BR-3]
- **AC-9** 유효 Apple token + 올바른 nonce → 응답 + Set-Cookie 없음 [FR-3]
- **AC-10** Apple 최초 로그인 시 email·fullName 저장 [FR-4,BR-9]
- **AC-11** Apple 재로그인 email/fullName null 전송 → 기존 값 유지 [FR-4,BR-9]
- **AC-12** Apple `aud` 불일치 → 401 `AUTH_APPLE_TOKEN_INVALID` [BR-4]
- **AC-13** Apple 만료 → 401 [BR-4]
- **AC-14** nonce 불일치 → 401 [BR-5]
- **AC-15** 탈퇴자 Kakao/Apple 로그인 → 401 `AUTH_USER_DEACTIVATED` [BR-6]
- **AC-16** 유효 refresh → `/auth/refresh` 새 쌍 + Set-Cookie 없음 [FR-5]
- **AC-17** 사용된 refresh 재호출 → 401 `AUTH_REFRESH_TOKEN_INVALID` [BR-7,BR-8]
- **AC-18** 기존 `/auth/token/refresh`(쿠키) 배포 후 동일 동작 [QE-1]
- **AC-19** V014 후 기존 Kakao 유저 `oauth_provider='KAKAO'`, `oauth_id=kakao_user_id::text` 정확 백필 [FR-6,BR-10]
- **AC-20** V014 후 `kakao_user_id` 컬럼·UNIQUE 제약 유지 [FR-6,BR-10]
- **AC-21** Apple 유저를 `findByOauthProviderAndOauthId('APPLE', sub)`로 조회 가능 [FR-7]
- **AC-22** Apple 최초 로그인 fullName null이어도 임시 닉네임으로 계정 생성(NOT NULL 위반 없음) [BR-12]

## 제외 범위 (Out of scope)
- Kakao·Apple 동일인 **계정 통합**(별도 계정 유지). 후속 Phase 별도 판단.
- `kakao_user_id` 컬럼 제거·레거시 Kakao 조회 정리(컷오버/후속).
- Apple 계정 삭제 + 토큰 revoke (`DELETE /users/me`) → P2.
- APNs 기기 토큰 등록 → P2.
- iOS 클라이언트 구현 → P3.
- Bearer 기반 logout 확장은 기존 logout 재사용 가능성만 확인(필요 시 처리).

## 가정·의존
- Kakao SDK access token이 `/v2/user/me`로 검증 가능(`KakaoOAuthClient.fetchUserInfo` 재사용).
- Apple JWKS(`/auth/keys`) 외부 접근 가능.
- Apple 번들 ID는 배포 전 prerequisites에서 발급 완료.
- nickname은 Kakao=프로필 닉네임, Apple=fullName 또는 임시→P3 온보딩 수집.
