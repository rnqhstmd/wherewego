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

## iOS 네이티브 인증 (P1)

> iOS 네이티브 전환 P1. 전부 **additive**(쿠키 웹 무중단). PRD/설계: `.dev/feat-ios-native-p1-auth/`.

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| P1-FR-1 | Bearer 헤더 분기 (헤더 우선, 빈 Bearer는 쿠키 폴백) | ✅ | [#86](https://github.com/rnqhstmd/wherewego/pull/86) |
| P1-FR-2 | `POST /auth/kakao/native` (Kakao access token + app_id 앱 귀속 검증) | ✅ | [#86](https://github.com/rnqhstmd/wherewego/pull/86) |
| P1-FR-3 | `POST /auth/apple/native` (identityToken JWKS 서명 + iss/aud/exp/nonce) | ✅ | [#86](https://github.com/rnqhstmd/wherewego/pull/86) |
| P1-FR-4 | Apple private relay 이메일·이름 최초 1회 저장 (재로그인 불변) | ✅ | [#86](https://github.com/rnqhstmd/wherewego/pull/86) |
| P1-FR-5 | `POST /auth/refresh` (body 기반, 기존 쿠키 `/token/refresh` 유지) | ✅ | [#86](https://github.com/rnqhstmd/wherewego/pull/86) |
| P1-FR-6 | users `oauth_provider`/`oauth_id`/`email` 일반화 (V014, 기존 kakao_user_id 백필·UNIQUE 유지) | ✅ | [#86](https://github.com/rnqhstmd/wherewego/pull/86) |

> 후속(P2 이월): 기존 웹 콜백 경로 Bulkhead/@Recover 에러코드 503 통일, 탈퇴 시 refresh hash 폐기, Apple `authorizationCode` 저장(revoke 대비).

## iOS 클라이언트 인증·온보딩 (P3)

> iOS 네이티브 전환 P3. P1 엔드포인트를 Bearer로 소비하는 SwiftUI 클라이언트. 키·계정 미보유로 **배선 + 시뮬레이터 빌드(iOS 26.5)·XCTest 60종**까지 검증(실로그인은 발급물 후). PRD/설계: `.dev/feat-ios-native-p3-shell-auth-onboarding/`.

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| P3-FR-1~4 | XcodeGen 프로젝트(iOS 17)·xcconfig(API_BASE_URL/KAKAO key)·Kakao SDK SPM·폰트 번들 구조 | ✅ | [#90](https://github.com/rnqhstmd/wherewego/pull/90) |
| P3-FR-5 | `KeychainTokenStore` (actor SecItem 저장, 401→refresh→재시도, inFlight 직렬화, 네트워크오류 토큰 보존) | ✅ | [#90](https://github.com/rnqhstmd/wherewego/pull/90) |
| P3-FR-7 | 카카오 네이티브 로그인 배선 (앱 우선/계정 폴백, placeholder graceful) | ✅(배선) | [#90](https://github.com/rnqhstmd/wherewego/pull/90) |
| P3-FR-8 | Apple 네이티브 로그인 배선 (rawNonce→요청엔 SHA-256 hex, 서버엔 평문 BR-2, capability 방어) | ✅(배선) | [#90](https://github.com/rnqhstmd/wherewego/pull/90) |
| P3-FR-9/10 | refresh·로그아웃(LogoutHandlerBox 전파) + 라우트 가드(토큰→location→nickname→groups/me) | ✅ | [#90](https://github.com/rnqhstmd/wherewego/pull/90) |
| P3-FR-11~16 | 온보딩 6화면(위치/닉네임/그룹시작/초대코드/알림/Welcome 위저드 2스텝) | ✅ | [#90](https://github.com/rnqhstmd/wherewego/pull/90) |

> 발급물 후 검증: 실기기 카카오/Apple 로그인(Native Key·Apple 계정+capability), 폰트 렌더링(파일+PostScript 보정). 후속(P4): Mapbox SDK·지도/핀, 기존 사용자 `wizardShown` 직행 플래그.

## iOS 클라이언트 지도·핀·사진·방문감지 (P4)

> iOS 네이티브 전환 P4(최대 공수). 웹 `MapClient.tsx` 를 SwiftUI 로 포팅. **Mapbox 배선 우선·토큰 나중**(`#if canImport(MapboxMaps)` 단일 파일 격리 + 플레이스홀더 stub) → token 없이 빌드·XCTest 170개 통과(DoD-A), 실렌더링은 secret download token(.netrc)+public token 발급 후(DoD-B). PRD/설계: `.dev/feat-ios-native-p4-map-pin-photo/`.

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| P4-FR-1~7 | Mapbox 추상화(MapRenderer 프로토콜+#if 격리+플레이스홀더)·MapView·태그별 마커(REEL/WISH/MEMORY)·클러스터·태그 필터·빈 상태 | ✅(배선) | [#91](https://github.com/rnqhstmd/wherewego/pull/91) |
| P4-FR-8~12 | 정보창(PinDetailSheet): 태그 변경·메모·장소명 편집·삭제(낙관적+롤백)·Instagram https 가드 | ✅ | [#91](https://github.com/rnqhstmd/wherewego/pull/91) |
| P4-FR-13~15 | 장소 검색(PlaceAPI)→태그 선택→추가, 크로스헤어 임의좌표(7자리 반올림) | ✅ | [#91](https://github.com/rnqhstmd/wherewego/pull/91) |
| P4-FR-16~19 | 사진(MEMORY 핀): PHPicker+SwiftUI 자작 1:1 크롭+1600px JPEG 압축, multipart 업로드/삭제, 카메라·사진 권한 문구 | ✅ | [#91](https://github.com/rnqhstmd/wherewego/pull/91) |
| P4-FR-20~26 | 룰렛(반경 확장 추첨·RNG 주입·MEMORY 토글), 카메라 flyTo/fitBounds | ✅ | [#91](https://github.com/rnqhstmd/wherewego/pull/91) |
| P4-FR-27~32 | 방문감지(포그라운드 CoreLocation, 100m·30초·정확도≤50m·속도1.4 게이트·Haversine)→MEMORY 전환+confetti+메모 시트, 세션 중복 차단 | ✅ | [#91](https://github.com/rnqhstmd/wherewego/pull/91) |
| P4-인프라 | APIClient `performAuthorized` 추출(401 refresh 공유)+multipart upload+query 인코딩 보정, UpdatePinRequest 부분 인코딩, 5분 캐시+append-only 폴링 | ✅ | [#91](https://github.com/rnqhstmd/wherewego/pull/91) |

> DoD-B(token 후 검증): Mapbox 실렌더링(마커 GeoJSON·클러스터·camera)·핀 CRUD/사진/방문감지 실기기 E2E. project.yml Mapbox SPM 주석 해제(`from: "11.0.0"`)+xcodegen generate 후.

## 운영 버그 수정

| 항목 | 상태 | 상세 |
|------|------|------|
| 카카오 로그인 JPA 커넥션 풀 고갈 | ✅ | `KakaoOAuth2UserService`의 DB 조회 로직을 별도 `UserLoginPersistence` Spring Bean으로 분리 — 트랜잭션 경계 명확화로 커넥션 조기 반환. HikariCP `maximum-pool-size: 10` 명시. [PR #54](https://github.com/rnqhstmd/wherewego/pull/54) |
| 카카오 로그인 간헐적 502 (Neon 콜드 스타트) | ⬜ | 유휴 후 첫 로그인이 Neon suspend 콜드 스타트로 재시도 예산(~10.5s) 초과 → `AUTH_KAKAO_API_FAILED`(502). 잠재 버그: `KakaoOAuthClient` 타임아웃 미배선. **다음 phase 구현** → [phase-14-login-cold-start.md](phase-14-login-cold-start.md) |

## 다음 phase

| Phase | 범위 | 상태 |
|-------|------|------|
| [Phase 14 — 로그인 콜드 스타트 안정화](phase-14-login-cold-start.md) | Neon keep-warm 스케줄러 + 재시도 예산 확대 + 카카오 OAuth 타임아웃 배선 (전체 3종) | ⬜ 미시작 (원인 분석 완료) |
