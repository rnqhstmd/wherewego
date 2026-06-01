# 전환 로드맵 — 구현 Phase 배치 (개정 2026-06-01)

> 설계 본문: `plan.md`. **사용자 직접 발급물(키·계정·SDK 설정)**: `prerequisites.md` (Phase별 발급 타이밍 포함). 이 문서는 전환을 **큼직한 소수 Phase**로 묶어 의존성·순서·배포 영향과 함께 배치한다.
> 확정 결정(2026-06-01): 단일 공유 백엔드 / 웹은 **앱 앱스토어 게시 시점에 종료**(그전까지 병행) / 채팅 = **봇 방(유저별·저장 전용) + 1:1 커플방(사람 전용) 분리, 크로스포스트 없음** / 방문 감지 **포그라운드** / Apple 로그인·계정 삭제·푸시·Privacy Manifest·리뷰어 데모 계정·인스타 방어 추가 / **디자인 정합성 최종 Phase 별도**.

## 원칙

- **큼직한 6 Phase + 런치 후 컷오버 1개.** 과분할 지양.
- 백엔드(P1·P2)는 `backend/**` → main 머지 시 **라이브 웹에 즉시 배포**되므로 전부 **additive**(신규 엔드포인트 / 쿠키 병행)로만. 라이브 배포 안전성 때문에 인증/서비스 **2개로만** 분리(더 합치지 않음).
- iOS(P3~P5)는 `ios/**` → 배포 무관, 자유롭게 쌓기.
- 디자인은 각 Phase가 화면 단위로 이관·적용하되, **P6에서 전체를 웹과 한 번에 대조·보정**.

## Phase 목록

### P1 — 백엔드 인증 확장 (additive, 웹 무중단)
- **범위**:
  - `JwtAuthenticationFilter` **Bearer 헤더 분기**(헤더 우선, 없으면 기존 쿠키 — 웹 무변화)
  - `POST /api/v1/auth/kakao/native` (Kakao access token 검증 → 우리 JWT)
  - `POST /api/v1/auth/apple/native` (Apple `identityToken` JWKS 서명 + `iss`/`aud`(번들ID)/`nonce`/`exp` 검증 → JWT. **private relay 이메일·이름 최초 1회** 보존) ★Guideline 4.8
  - `POST /api/v1/auth/refresh`
  - User에 `oauth_provider`+`oauth_id` 일반화(기존 Kakao 매핑 보존)
- **의존**: 없음. 위험 낮음. **시작점.**
- **완료 기준**: 쿠키/Bearer 동일 JWT 인증, Kakao·Apple 네이티브 로그인 → 우리 JWT, refresh 동작, 웹 회귀 0.

### P2 — 백엔드 앱 서비스 (채팅·실시간·푸시·계정 삭제)
- **범위**:
  - 채팅 모델: `chat_room(id, group_id, type[BOT|COUPLE], owner_user_id NULL허용)` + `chat_message(id, room_id, sender_type[USER|BOT|SYSTEM], sender_user_id, kind[TEXT|PLACE_CARDS|MEMO_PROMPT|PROCESSING|SYSTEM], payload_json, created_at)`
  - **봇 방(유저별)**: `ChatbotWebhookService` → `BotChatService` 리팩터(입력 `(userId, text, actionPayload?)`, **세션 키 userId 유지 → 동시성 문제 없음**). 핸들러 체인·Gemini·PlaceCardBuilder 재사용. `useCallback` 폐기 → "처리 중" 메시지 즉시 게시 + `@Async` 후 카드 append.
  - **1:1 커플방(사람 전용)**: 분류기/봇 미개입. 텍스트 저장 + 상대에게 브로드캐스트만.
  - REST: `POST/GET /api/v1/chat/bot/messages`, `POST/GET /api/v1/chat/couple/{groupId}/messages?cursor=`
  - 실시간: WebSocket(STOMP) — 봇 방·커플방 토픽
  - 푸시: APNs(**.p8 토큰 기반**) + `POST/DELETE /api/v1/devices`. 폴링 알림 → push 격상. 트리거: 파트너 핀 저장, 커플방 새 메시지, 봇 방 처리 완료.
  - 계정 삭제: `DELETE /api/v1/users/me` — 개인 데이터(oauth·refresh·device·본인 메시지·멤버십) 삭제, **마지막 1인까지 삭제 시 그룹+핀 삭제**, **Apple 토큰 revoke** ★Guideline 5.1.1(v)
- **의존**: P1
- **완료 기준**: 두 방 적재·조회·실시간, 푸시 도달, 계정 삭제+Apple revoke 동작.

### P3 — iOS 골격 + 인증 + 온보딩
- **범위**: Xcode 프로젝트(+XcodeGen/Tuist 검토), SPM(Mapbox·Kakao; Apple은 내장 `AuthenticationServices`), 폰트 번들+Info.plist, xcconfig(API_BASE_URL), **Keychain `TokenStore` 구현**. **Kakao + Apple 로그인**, 온보딩 플로우(welcome/nickname/location/invite-code/notification/group-start), 라우트 가드. (B0 스캐폴드 흡수)
- **의존**: P1
- **완료 기준**: 로그인(2종) → 온보딩 → 그룹 진입까지 네이티브 동작.

### P4 — iOS 지도·핀·사진·방문감지 (최대 공수)
- **범위**: Mapbox iOS(동일 style URL, GeoJSON `cluster:true`), 핀 CRUD/태그/메모, 사진(PHPicker+ImageIO 압축 + **크롭 뷰 자작**), 룰렛, flyTo/fitBounds 카메라, **방문 감지(포그라운드)** — 앱 활성 중 CoreLocation, 저장 핀 근접 시 컨페티, 알림함. `MapClient.tsx` 포팅.
- **의존**: P1, P3
- **완료 기준**: 지도/핀/사진/룰렛/방문감지 동작.

### P5 — iOS 채팅 + 푸시 + 제출 자산
- **범위**: **봇 채팅방 UI**(릴스 → 버튼 다중선택 → 저장, 실시간) + **1:1 커플방 UI**(사람 대화, 실시간) 분리. APNs 등록·딥링크. Privacy Manifest(앱+SDK), 권한 문구, **리뷰어 데모 계정 시드 + 숨은 데모 로그인**, **인스타 방어 노트**(미디어 미저장 확인), 아이콘/스플래시.
- **의존**: P2, P3
- **완료 기준**: 두 방·푸시·딥링크 동작, 제출 자산 완비.

### P6 — 디자인 정합성 최종 QA (웹↔앱) → 제출
- **범위**: 전 화면 웹 레퍼런스 대조 + 보정 — 토큰 / 폰트 / letter-spacing(`.tracking`) / line-height(`.lineSpacing`) / easing(cubic-bezier→`.timingCurve`) / 레이아웃 픽셀. **디자인 토큰 단일 소스 가드**(`tokens.ts`↔`Theme.swift` drift 체크 또는 코드 생성). 통과 후 **TestFlight → 앱스토어 제출**.
- **의존**: P3, P4, P5
- **완료 기준**: 화면별 시각 동등성 확인, 심사 제출.
- **비고**: 각 Phase가 디자인을 이관하지만, 이 Phase는 **전체를 한 번에 대조·보정하는 별도 게이트**(사용자 요청).

### 컷오버 (런치 후 — dev Phase 아님)
- 앱 **앱스토어 게시 확정 → 웹 종료** + **봇 레이어/쿠키 auth 제거**: `domain/bot`, `BotLinkCode/Mapping`, `LinkCodeHandler`, `MessageType.LINK_CODE`, `KakaoSkillSecretFilter`, `/chatbot/webhook`, 프론트 `/bot/connect`, **쿠키 인증** 삭제. 기존 사용자 그룹 자연 승계 확인.

## 의존성

```
P1 ──┬─► P3 ─► P4 ─┐
     │              ├─► P6 ─► 제출 ─► (앱 게시/런치) ─► 컷오버: 웹 종료 + 봇·쿠키 제거
     └─► P2 ─► P5 ─┘
```

- 임계 경로: P1 → P2 → P5 → P6 (또는 P1 → P3 → P4 → P6).
- P1·P2(백엔드)는 웹 운영 중 라이브 배포되므로 작은 additive 커밋으로.
- 각 Phase = 1 PR 권장. 착수 시 `.dev/feat-ios-...-Px/` 에 PRD/설계 분리 가능.

## 열린 결정 (남은 것)
- XcodeGen/Tuist 도입 여부(모노레포 `.xcodeproj` 충돌 회피).
- 인메모리 세션 DB 영속화 시점(봇 방은 유저별이라 동시성 해소 → 영속화는 재시작/스케일 대비 차기).
- Mapbox iOS MAU 과금 한도 확인.

> 해소됨: 웹 운명=앱 게시 시 종료 / 채팅=분리·크로스포스트 없음 / 방문감지=포그라운드 / 카카오 봇 마이그레이션=컷오버 시 그룹 자연 승계.
