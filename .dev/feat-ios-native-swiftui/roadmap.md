# 전환 로드맵 — 구현 Phase 배치

> 설계 본문: `plan.md`. 이 문서는 전환을 **독립 구현 단위(Phase)** 로 쪼개 의존성·순서·배포 영향과 함께 배치한다.
> 트랙: **A = 백엔드**(`backend/**` 변경 → main 머지 시 자동 배포), **B = iOS**(`ios/**` 변경 → 배포 무관).

## 트랙 A — 백엔드 (additive, 웹 무중단)

### A1. Bearer 헤더 인증
- **목표**: 앱이 `Authorization: Bearer` 로 인증.
- **범위**: `JwtAuthenticationFilter.extractAccessTokenFromCookie` 에 헤더 분기 추가(헤더 우선, 없으면 쿠키 유지).
- **의존**: 없음. **위험 0. 첫 작업.**
- **완료 기준**: 동일 JWT 를 쿠키/헤더 둘 다로 인증 통과. 웹 회귀 없음.

### A2. Kakao 네이티브 로그인 + 토큰 발급
- **목표**: iOS SDK 로그인 → 우리 JWT 발급.
- **범위**: `POST /api/v1/auth/kakao/native`(Kakao access token 검증 → JWT), 응답 `{accessToken, refreshToken, expiresIn}` JSON, `POST /api/v1/auth/refresh`.
- **의존**: A1.
- **완료 기준**: 유효 Kakao 토큰 → 우리 JWT. refresh 동작. 웹 리다이렉트 흐름 영향 없음.

### A3. 채팅방 백엔드 (코어 재사용)
- **목표**: 카카오 웹훅 → 인증 REST 커플 채팅방.
- **범위**: `chat_message` 테이블/엔티티, `ChatService`(`ChatbotWebhookService` 리팩터 — 입력 `(groupId, senderUserId, text)`, **세션 키 groupId**), `POST/GET /api/v1/groups/{groupId}/chat/messages`. 핸들러 체인·Gemini 추출·PlaceCardBuilder 재사용.
- **의존**: A1.
- **완료 기준**: 텍스트/릴스 메시지 → 봇 응답이 방에 적재·조회. 핀 저장까지 동작.

### A4. 실시간 + 푸시
- **목표**: 방 메시지 실시간 수신 + 백그라운드 푸시.
- **범위**: WebSocket(STOMP)/SSE 채널, APNs 디스패치(`NotificationService` 확장), `POST /api/v1/devices`(APNs 토큰 등록). 비동기 장소추출 결과를 방에 push.
- **의존**: A3.
- **완료 기준**: 한쪽이 보낸 메시지가 상대 기기에 실시간/푸시 도달.

### A5. 봇 연동 레이어 폐기 (컷오버)
- **목표**: 카카오 채널 봇 은퇴.
- **범위**: `domain/bot`, `BotLinkCode/Mapping`, `LinkCodeHandler`, `MessageType.LINK_CODE`, `KakaoSkillSecretFilter`, `/chatbot/webhook`, 프론트 `/bot/connect` 삭제. 기존 사용자 그룹 승계 확인.
- **의존**: B4(앱 출시) 이후. 출시 전까지 **카카오 봇 병행 운영**.
- **완료 기준**: 인앱 채팅으로 100% 대체, 죽은 코드 제거.

## 트랙 B — iOS 앱

### B0. 스캐폴드 ✅ (진행됨)
- `ios/` 생성, `.gitignore`, `README`, `Theme.swift`(토큰 이식), `APIClient.swift`(스켈레톤), 폰트 자리.
- **남은 것**: Xcode 프로젝트 생성, SPM(Mapbox·Kakao) 추가, 폰트 번들+Info.plist 등록, xcconfig(API_BASE_URL).

### B1. 인증 + 온보딩
- **범위**: Kakao 로그인(SDK) → JWT → **Keychain `TokenStore` 구현**, 온보딩 플로우(welcome/nickname/location/invite-code/notification/group-start), 라우트 가드.
- **의존**: A2, B0.
- **완료 기준**: 로그인→온보딩→그룹 진입까지 네이티브 동작.

### B2. 지도 + 핀 + 사진
- **범위**: Mapbox iOS(동일 style URL, GeoJSON 클러스터), 핀 CRUD/태그/메모, 카메라/사진(PHPicker+압축), **사진 크롭 뷰(신규)**, flyTo/fitBounds 카메라 애니, 룰렛, 방문 감지+컨페티. (최대 공수 — `MapClient.tsx` 포팅)
- **의존**: A1, B1.
- **완료 기준**: 지도/핀/사진/룰렛이 웹과 시각·동작 동등.

### B3. 채팅방
- **범위**: `ChatView`/`ChatViewModel`, 메시지 버블, 장소 카드 버블(선택 버튼), WebSocket 실시간 수신, 릴스 링크 → 봇 흐름.
- **의존**: A3, A4, B1.
- **완료 기준**: 커플 봇 채팅방 동작, 장소 카드→핀 저장.

### B4. 푸시 + 폴리시 + 제출
- **범위**: APNs 등록/수신·딥링크, 시각 QA(애니 duration/easing 정합), 아이콘/스플래시/권한 문구/개인정보, TestFlight→앱스토어 제출.
- **의존**: A4, B2, B3.
- **완료 기준**: 스토어 심사 통과.

## 의존성·마일스톤 배치

```
M0  ┌ A1 (Bearer)            B0 (스캐폴드)        ← 병렬, 위험 0
M1  ├ A2 (Kakao native) ───────────────► B1 (인증+온보딩)
M2  │                                    B2 (지도+핀+사진)   ← A1 위에서 가능
M3  ├ A3 (채팅 백엔드) ─► A4 (실시간+푸시) ─► B3 (채팅) + B4(푸시)
M4  └ A5 (봇 폐기, 출시 후)               B4 → 앱스토어 제출
```

- **임계 경로**: A1 → A2 → B1 → B2 → (A3→A4) → B3 → B4.
- A 트랙은 머지 즉시 배포되니 작은 PR 로. B 트랙은 배포 무관이라 자유롭게 쌓기.
- 각 Phase = 1 PR 단위 권장. 착수 시 해당 Phase 폴더(`.dev/feat-ios-...-Ax/`)에 PRD/설계 분리 가능.

## 열린 결정
- 웹 은퇴 vs 병행 (병행이면 백엔드 쿠키+Bearer 양립 — 현 계획 그대로).
- 카카오 봇 사용자 마이그레이션 안내 방식.
- XcodeGen/Tuist 도입 여부(모노레포 `.xcodeproj` 충돌 회피).
