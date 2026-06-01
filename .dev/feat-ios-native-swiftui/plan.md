# iOS 네이티브 전환 계획 — SwiftUI 전용 + 봇 방 + 1:1 커플방

> 작성일 2026-05-31. 개정 2026-06-01. 결정: **SwiftUI (iOS 앱스토어 전용, 영구)** · **단일 공유 백엔드(웹은 앱 게시 시 종료, 그전까지 병행)** · **채팅 = 봇 방(유저별·저장 전용) + 1:1 커플방(사람 전용) 분리, 크로스포스트 없음**.
> 백엔드는 유지·확장(additive). 카카오 채널 스킬 봇은 인앱 봇 방으로 대체하며 봇 연동 레이어는 **앱 게시 컷오버 때** 폐기.
> 구현 Phase 배치: `roadmap.md`. **사용자 직접 발급물(Apple/Kakao/Mapbox 키·계정·SDK 설정)**: `prerequisites.md`.

## 0. 핵심 결정 요약

| 항목 | 결정 |
|---|---|
| 클라이언트 | SwiftUI 네이티브, iOS 전용 (Android 계획 없음) |
| 백엔드 | **단일 공유**(웹과 동일). 포크/복사 안 함. 변경은 전부 additive |
| 지도 | Mapbox Maps SDK for iOS (mapbox-gl-js 대체, 네이티브 클러스터링) |
| 인증 | **Kakao + Apple** 네이티브 로그인 → 백엔드 JWT → Keychain, Bearer 헤더. (Apple은 Guideline 4.8 필수) |
| 채팅 | **봇 방(유저별, 릴스→저장 전용)** + **1:1 커플방(두 파트너 사람 대화 전용, 봇 미개입)** 분리. **크로스포스트 없음**(파트너 통지는 푸시로). 인증 REST + 실시간 |
| 봇 연동 레이어 | **앱 게시 컷오버 때 폐기** (`domain/bot`, BotLinkCode, BotUserMapping, /bot/connect, KakaoSkillSecretFilter, LINK_CODE, **쿠키 auth**) |
| 푸시 | APNs(.p8 토큰 기반) + 기기 토큰 등록 (폴링 알림 격상) |
| 계정 삭제 | `DELETE /users/me` — 개인 데이터 purge + **Apple 토큰 revoke** (Guideline 5.1.1v 필수) |
| 방문 감지 | **포그라운드** CoreLocation(앱 활성 중) + 저장 핀 근접 컨페티 |
| 앱스토어 자산 | Privacy Manifest(앱+SDK), 권한 문구, **리뷰어 데모 계정 시드 + 숨은 로그인**, 인스타 방어 노트 |
| 웹 | **앱 앱스토어 게시 시 종료.** 그전까지 병행(쿠키+Bearer additive). 게시 후 쿠키 auth 제거 |

## 1. 백엔드: 재사용 / 폐기 / 신규

### 재사용 (전송 무관 코어 — 거의 그대로)
- `domain/pin`, `domain/place`(parser, Gemini 추출), `domain/group`, `domain/user`, `domain/notification`, `domain/auth/jwt`
- 챗봇 **코어**: `MessageClassifier`, `MessageHandler` 체인(InstagramLink, ReelMultiSelection, ReelMemoWaiting, PlaceSelection, TwoSecondMemo, Unknown), `PlaceCardBuilder`, 세션류(`ReelSavedSelectionSession` 등)
- → 핸들러 인터페이스 유지, **입출력 어댑터만 교체**. 봇 방은 유저별이라 세션 키(`userId`)도 기존과 동형.

### 폐기 (앱 게시 컷오버 때)
- `domain/bot` 전체: `BotLinkCode*`, `BotUserMapping*`, `LinkCodeGenerator`
- `handler/LinkCodeHandler`, `MessageType.LINK_CODE`
- `KakaoSkillSecretFilter`, Kakao Skill DTO(`ChatbotV1Dto.SkillRequest/Response`, `BasicCard/QuickReply`, `useCallback`)
- `ChatbotV1Controller`(/chatbot/webhook) → 신규 chat controller로 대체
- 프론트 `/bot/connect`, **쿠키 인증**(웹 종료 시)

### 신규
1. **Bearer 헤더 인증** — `JwtAuthenticationFilter`에 `Authorization: Bearer` 분기 추가(헤더 우선, 쿠키 병행).
2. **Kakao 네이티브 로그인** — Kakao access token 검증 → 우리 JWT. `POST /api/v1/auth/kakao/native`, 응답 `{accessToken, refreshToken, expiresIn}`. `POST /api/v1/auth/refresh`.
3. **Apple 로그인** — `POST /api/v1/auth/apple/native`. Apple `identityToken`을 JWKS로 서명 검증 + `iss`/`aud`(번들ID)/`nonce`/`exp` 검증 → `sub`로 find-or-create → JWT. **private relay 이메일·이름 최초 1회** 보존. User에 `oauth_provider`+`oauth_id` 일반화. (Guideline 4.8)
4. **채팅 모델** — `chat_room(id, group_id, type[BOT|COUPLE], owner_user_id NULL허용)` + `chat_message(id, room_id, sender_type[USER|BOT|SYSTEM], sender_user_id, kind[TEXT|PLACE_CARDS|MEMO_PROMPT|PROCESSING|SYSTEM], payload_json, created_at)`.
5. **BotChatService (봇 방, 유저별)** — `ChatbotWebhookService` 리팩터: 입력 `(userId, text, actionPayload?)`, 출력 `ChatMessage[]`. **세션 키 userId 유지**(동시성 문제 없음). 핸들러 체인·Gemini·PlaceCardBuilder 재사용. 봇 응답은 봇 방에 BOT 메시지로 적재.
6. **CoupleChatService (1:1 방, 사람 전용)** — 분류기/봇 미개입. 텍스트 저장 + 상대에게 브로드캐스트만. 크로스포스트 없음.
7. **Chat REST** — `POST/GET /api/v1/chat/bot/messages`, `POST/GET /api/v1/chat/couple/{groupId}/messages?cursor=`.
8. **실시간 전달** — WebSocket(STOMP)로 봇 방·커플방 신규 메시지 push. 비동기 장소추출 결과도 동일 채널.
9. **APNs 푸시 + 기기 등록** — `POST/DELETE /api/v1/devices`(.p8 토큰 기반), `NotificationService` 확장. 트리거: 파트너 핀 저장, 커플방 새 메시지, 봇 방 처리 완료.
10. **계정 삭제** — `DELETE /api/v1/users/me`: 개인 데이터(oauth·refresh·device·본인 메시지·멤버십) 삭제, 마지막 1인까지 삭제 시 그룹+핀 삭제, **Apple 토큰 revoke**. (Guideline 5.1.1v)
11. **CORS** — 네이티브는 origin 없음 → 앱엔 불필요. 웹 병행 시 기존 유지.

### 비동기 흐름 변화 (개선)
```
[기존 카카오] 릴스 → "처리중"(useCallback) → 카카오 callbackUrl로 결과 push (5초 제약 회피)
[인앱 봇 방]  릴스 → BOT "처리중" 메시지 즉시 게시 → Gemini 추출(@Async)
             → 완료 시 BOT "장소 카드" 메시지 append + WebSocket/APNs push
```
카카오 5초 동기 제약과 `useCallback` dance가 사라짐.

### 세션 동시성 (해소)
봇 방을 **유저별(per-user)** 로 두고 세션 키를 `userId`로 유지하므로(기존 카카오 1인↔봇과 동형) 두 파트너가 동시에 릴스를 던져도 **충돌 없음**. 인메모리 세션은 재시작/수평확장에 취약 → DB 영속화는 차기 과제(이번 범위 밖).

## 2. iOS 앱 아키텍처 (SwiftUI)

- 패턴: SwiftUI + MVVM(`ObservableObject` ViewModel), `async/await`
- 네트워킹: `URLSession` 기반 `APIClient` — Bearer 자동 부착, 401→refresh→재시도. 응답 envelope(`{meta,data}`) 디코딩 공통화.
- 인증: **Kakao iOS SDK + Apple(`AuthenticationServices`)** 로그인 → 백엔드 JWT → **Keychain** 저장. 플래그(locationAsked/nicknameSet 등)는 `UserDefaults`(웹 local-flags 대체).
- 지도: **MapboxMaps** iOS SDK. 클러스터링은 GeoJSON source `cluster:true`(웹 supercluster 대체). 카메라 이동·핀 마커·롤렛 등 `MapClient.tsx` 로직 포팅.
- 카메라/사진: `PhotosUI`(PHPicker) + `AVFoundation`, 압축은 ImageIO. 멀티파트 업로드(기존 4MB 정책 유지).
- 채팅: **봇 방**(`BotChatView`) + **1:1 커플방**(`CoupleChatView`) 분리, 각 `ViewModel`. 실시간 `URLSessionWebSocketTask`. 봇 방 장소 카드 = 선택 버튼 커스텀 버블(텍스트 파싱 대신 버튼 actionPayload).
- 푸시: `UNUserNotificationCenter` + APNs. 알림 탭 → 해당 핀/방으로 딥링크.
- 내비게이션: `NavigationStack`. 온보딩 위저드 플로우 포팅.

### 화면 매핑 (기존 Next 라우트 → SwiftUI View)
| Next 라우트 | SwiftUI | 비고 |
|---|---|---|
| `/login` | `LoginView` | Kakao + Apple 네이티브 |
| `/onboarding/*` | 온보딩 플로우 | welcome/nickname/location/invite-code/notification/group-start |
| `/map` (`MapClient.tsx` 최대 파일) | `MapView` | Mapbox iOS, 핀/클러스터/롤렛 |
| `/pins` | `PinListView` | 핀 CRUD, 사진 |
| `/groups`, `/groups/new`, `/groups/invite`, `/invite/[slug]` | 그룹/초대 Views | 초대 링크 딥링크 처리 |
| `/settings`, `/settings/nickname` | `SettingsView` | (서브메뉴엔 ← 뒤로가기 필수) + **계정 삭제** |
| `/bot/connect` | **삭제** | 연동 불필요(컷오버 때) |
| — | `BotChatView` (신규) | 릴스→장소 저장 봇 방(유저별) |
| — | `CoupleChatView` (신규) | 1:1 커플 대화방(사람 전용) |

## 3. PR / Phase 시퀀스

세부 Phase 배치·의존성은 `roadmap.md` 참조. **큼직한 6 Phase + 런치 후 컷오버**로 정리(2026-06-01 개정):

- **P1 백엔드 인증 확장** — Bearer 헤더 + Kakao/Apple 네이티브 로그인 + refresh + 계정 모델 일반화
- **P2 백엔드 앱 서비스** — 봇 방 + 1:1 커플방 + 실시간 + APNs 푸시 + 계정 삭제
- **P3 iOS 골격+인증+온보딩** — Xcode/SPM/폰트/Keychain, Kakao+Apple 로그인, 온보딩
- **P4 iOS 지도+핀+사진+방문감지(포그라운드)** — 최대 공수(`MapClient.tsx` 포팅)
- **P5 iOS 채팅(봇 방+1:1 방)+푸시+제출 자산** — Privacy Manifest·데모 계정·인스타 방어
- **P6 디자인 정합성 최종 QA(웹↔앱) → 앱스토어 제출**
- **컷오버(런치 후)**: 웹 종료 + 봇 레이어/쿠키 auth 제거

## 4. 리스크 / 열린 질문

**해소된 결정(2026-06-01)**: 웹=앱 게시 시 종료(그전 병행) / 채팅=봇 방+1:1 방 분리, 크로스포스트 없음 / 방문감지=포그라운드 / 카카오 봇 마이그레이션=컷오버 시 그룹 자연 승계.

남은 리스크:
- **인메모리 세션**: 봇 방 유저별이라 동시성은 해소. 재시작/수평확장 대비 DB 영속화는 차기.
- **Mapbox 비용/SDK**: iOS SDK MAU 과금 모델·무료 한도 확인 필요.
- **Kakao/Apple 토큰 검증**: 네이티브 로그인은 토큰 검증 신규(Kakao access token, Apple identityToken JWKS).
- **리뷰어 진입**: Kakao+초대 게이트라 데모 계정 시드 + 숨은 로그인 필수(P5).
- **인스타 콘텐츠(5.2.2)**: 미디어 미저장·사용자 자발 입력 방어 논리 유지.

## 5. 기능 전환 가능성 (채팅 외 전 기능)

전제: 애니메이션 라이브러리 **0개**. 전부 표준 CSS transform/opacity, SVG, Mapbox 카메라. → SwiftUI 네이티브로 거의 1:1.

### 애니메이션 → SwiftUI 매핑
| 웹 애니메이션 | 정의 | SwiftUI 대응 | 난이도 |
|---|---|---|---|
| `spin` / `roulette-spin` | 360° 회전 무한 | `.rotationEffect` + `.repeatForever` | 자명 |
| `user-location-pulse` | scale 0.6→3 + opacity, 2.4s | scale+opacity `repeatForever` | 자명 |
| `maygo-preview-pin-drop` | 위에서 떨어지며 1.04 오버슈트 | `.spring()` (오버슈트 자연 발생) | 쉬움 |
| `maygo-bubble-pop` | translateX + opacity | `.transition(.move+.opacity)` | 자명 |
| `maygo-marker-bounce` | scale 1→1.3→1 | keyframe scale | 쉬움 |
| `maygo-confetti-heart-0/1/2` | 하트 파티클 --dx/--dy 발산 600ms | N개 하트 뷰 spawn + 랜덤 offset/opacity | 쉬움~보통 |
| `maygo-visit-toast-fade-in` / `kbd-fadein` / `splash-dot` | fade/scale | 기본 transition | 자명 |
| Mapbox `flyTo(duration 700~1500)`, `fitBounds(padding)` | 카메라 이동 | **Mapbox iOS SDK 동일 API** (`ease/fly(to:)`, `CameraAnimator`) | 1:1 |
| `GlobeBg` (로그인 배경) | 순수 SVG (circle/ellipse/gradient) | `Canvas`/`Shape` 또는 PDF 에셋 export | 쉬움 |

→ "동일한 애니메이션" 목표 달성 가능. 단 **정확히 동일**하려면 duration·easing(cubic-bezier↔SwiftUI timing curve)·design token을 의도적으로 맞추고 화면별 시각 QA 필요.

### 기능 전환 판정
| 기능 | 판정 | 메모 |
|---|---|---|
| 지도/핀/클러스터 | ✅ | Mapbox iOS SDK + GeoJSON `cluster:true` (supercluster 대체). **동일 style URL → 지도 룩 1:1** |
| 핀 CRUD / 태그 / 메모 | ✅ | REST 그대로 |
| 사진 업로드 + 압축 | ✅ | PHPicker + ImageIO (browser-image-compression 대체), 멀티파트 유지 |
| **사진 크롭** (react-easy-crop) | 🟡 | 직접 대응 없음 → SwiftUI 크롭 뷰 자작/라이브러리 |
| 룰렛 (반경 추첨 + 스핀) | ✅ | 로직(`roulette.ts`) 포팅 + 스핀 애니 자명 |
| 방문 감지 + 컨페티 축하 | ✅ | **포그라운드** CoreLocation + 파티클 |
| 알림함 (현재 폴링) | ✅↑ | APNs 푸시로 격상 |
| 온보딩 위저드 | ✅ | NavigationStack 플로우 |
| 카카오 로그인 | ✅ | Kakao iOS SDK (웹 리다이렉트 대체) |
| 초대 링크 / 딥링크(`?pinId`, `/invite/[slug]`) | ✅ | Universal Links |
| 키보드 인셋 보정 (`useKeyboardInsets`) | ✅↑ | SwiftUI 키보드 회피 기본 제공 |
| 공유 시트 (`PinShareSheet`) | ✅ | `ShareLink`/`UIActivityViewController` |
| `MapClient.tsx` (1500+줄 상호작용) | ⚠️ | 기능 아닌 **포팅 분량**이 큼 — 최대 공수 화면 |

### 시각/애니 충실도 보장 방법
- `lib/design/tokens`(colors/fonts/spacing) → SwiftUI `Theme`/`Color`/`Font` 상수로 1:1 이식. 커스텀 폰트(serif/mono/sans) 번들 필수.
- **토큰 단일 소스화**: `Theme.swift`는 `tokens.ts`의 수동 사본이라 드리프트 위험 → 코드 생성 또는 CI diff 가드로 단일 소스 유지.
- 기존 작업이 디자인 번들(`screens-mobile.jsx` 등) **1:1 변환** 방식이었음 → 동일 워크플로를 SwiftUI에 적용(화면별 레퍼런스 대조 + 시각 QA).
- ⚠️ 주의: CSS flow/absolute 레이아웃 ≠ SwiftUI 레이아웃. 픽셀 정합은 화면별로 의도적 보정 필요(자동 변환 아님).
- **P6(디자인 정합성 최종 QA)**: 각 Phase가 화면별로 디자인을 이관하되, P6에서 전 화면을 웹과 한 번에 대조·보정(letter-spacing→`.tracking`, line-height→`.lineSpacing`, cubic-bezier→`.timingCurve`)하는 **별도 게이트**.

## 6. 즉시 시작점

**P1(백엔드 인증 확장)** 이 위험이 가장 낮고 iOS 인증 전체를 잠금 해제 — 여기부터 시작. 그다음 **P2**(봇 방+1:1 방+푸시+계정 삭제). iOS는 **P3**(골격+인증)부터 쌓고, 마지막 **P6**에서 웹과 디자인 정합성 최종 대조 후 제출.
