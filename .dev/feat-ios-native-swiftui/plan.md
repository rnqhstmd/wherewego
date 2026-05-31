# iOS 네이티브 전환 계획 — SwiftUI 전용 + 인앱 커플 봇 채팅방

> 작성일 2026-05-31. 결정: **SwiftUI (iOS 앱스토어 전용, 영구)** · **챗봇 = 커플당 사람↔봇 채팅방 1개(두 파트너 공유)**.
> 백엔드는 유지·확장. 카카오 채널 스킬 봇은 인앱 채팅으로 대체하며 봇 연동 레이어는 폐기.

## 0. 핵심 결정 요약

| 항목 | 결정 |
|---|---|
| 클라이언트 | SwiftUI 네이티브, iOS 전용 (Android 계획 없음) |
| 지도 | Mapbox Maps SDK for iOS (mapbox-gl-js 대체, 네이티브 클러스터링) |
| 인증 | Kakao iOS SDK 네이티브 로그인 → 백엔드 JWT 발급 → Keychain 저장, Bearer 헤더 |
| 챗봇 | 카카오 웹훅 폐기 → 커플(그룹)당 사람↔봇 채팅방, 인증 REST + 실시간 |
| 봇 연동 레이어 | **전체 폐기** (`domain/bot`, BotLinkCode, BotUserMapping, /bot/connect, KakaoSkillSecretFilter, LINK_CODE) |
| 푸시 | APNs + 기기 토큰 등록 (폴링 알림 격상) |
| 웹 | (열린 질문) 코드 변경은 모두 additive라 웹/앱 병행 가능. 웹 은퇴 여부는 별도 결정 |

## 1. 백엔드: 재사용 / 폐기 / 신규

### 재사용 (전송 무관 코어 — 거의 그대로)
- `domain/pin`, `domain/place`(parser, Gemini 추출), `domain/group`, `domain/user`, `domain/notification`, `domain/auth/jwt`
- 챗봇 **코어**: `MessageClassifier`, `MessageHandler` 체인(InstagramLink, ReelMultiSelection, ReelMemoWaiting, PlaceSelection, TwoSecondMemo, Unknown), `PlaceCardBuilder`, 세션류(`ReelSavedSelectionSession` 등)
- → 핸들러 인터페이스 유지, **입출력 어댑터만 교체**

### 폐기
- `domain/bot` 전체: `BotLinkCode*`, `BotUserMapping*`, `LinkCodeGenerator`
- `handler/LinkCodeHandler`, `MessageType.LINK_CODE`
- `KakaoSkillSecretFilter`, Kakao Skill DTO(`ChatbotV1Dto.SkillRequest/Response`, `BasicCard/QuickReply`, `useCallback`)
- `ChatbotV1Controller`(/chatbot/webhook) → 신규 chat controller로 대체
- 프론트 `/bot/connect`

### 신규
1. **Bearer 헤더 인증** — `JwtAuthenticationFilter.extractAccessTokenFromCookie`에 `Authorization: Bearer` 분기 추가(헤더 우선).
2. **Kakao 네이티브 로그인** — iOS SDK가 받은 Kakao access token을 백엔드가 검증 → 우리 JWT 발급. 웹 리다이렉트 흐름과 별도 엔드포인트(`POST /api/v1/auth/kakao/native`). 응답은 `{accessToken, refreshToken, expiresIn}` JSON. `POST /api/v1/auth/refresh` 추가.
3. **ChatMessage 영속화** — `chat_message(id, group_id, sender_type[USER|BOT], sender_user_id NULL허용, kind[TEXT|PLACE_CARDS|MEMO_PROMPT|SYSTEM], payload_json, created_at)` + repository.
4. **ChatService** — `ChatbotWebhookService`를 리팩터: 입력을 `(groupId, senderUserId, text)`로, 출력을 `ChatMessage`(들)로. **세션 키를 botUserKey → groupId**로 변경(커플 공유 방). 봇 응답은 방에 BOT 메시지로 적재.
5. **Chat REST** — `POST /api/v1/groups/{groupId}/chat/messages`(전송, 사용자 메시지 저장 + 봇 처리 트리거), `GET .../messages?cursor=`(히스토리).
6. **실시간 전달** — WebSocket(STOMP) 또는 SSE 채널로 방 신규 메시지 push. 비동기 장소추출 결과도 동일 채널로 방에 추가.
7. **APNs 푸시 + 기기 등록** — `POST /api/v1/devices`(APNs token 등록), `NotificationService`에서 APNs 디스패치. 앱 백그라운드 시 채팅/알림 푸시.
8. **CORS** — 네이티브는 origin 없음 → 앱엔 불필요. 웹 병행 시 기존 유지.

### 비동기 흐름 변화 (개선)
```
[기존 카카오] 릴스 → "처리중"(useCallback) → 카카오 callbackUrl로 결과 push (5초 제약 회피)
[인앱]       릴스 → BOT "처리중" 메시지 방에 즉시 게시 → Gemini 추출(@Async) 
             → 완료 시 BOT "장소 카드" 메시지 방에 append + WebSocket/APNs push
```
카카오 5초 동기 제약과 `useCallback` dance가 사라짐.

### 주의: 공유 세션 동시성
세션을 groupId로 키하면 두 파트너가 동시에 릴스를 던질 때 충돌 가능. 규칙: **방당 활성 릴스 세션 1개**(현재 유저당 1개 동작과 동형). 인메모리 세션은 재시작/수평확장에 취약 → 차기 과제로 DB 영속화 검토(이번 범위 밖).

## 2. iOS 앱 아키텍처 (SwiftUI)

- 패턴: SwiftUI + MVVM(`ObservableObject` ViewModel), `async/await`
- 네트워킹: `URLSession` 기반 `APIClient` — Bearer 자동 부착, 401→refresh→재시도. 응답 envelope(`{meta,data}`) 디코딩 공통화.
- 인증: Kakao iOS SDK 로그인 → 백엔드 JWT → **Keychain** 저장. 플래그(locationAsked/nicknameSet 등)는 `UserDefaults`(웹 local-flags 대체).
- 지도: **MapboxMaps** iOS SDK. 클러스터링은 GeoJSON source `cluster:true`(웹 supercluster 대체). 카메라 이동·핀 마커·롤렛 등 `MapClient.tsx` 로직 포팅.
- 카메라/사진: `PhotosUI`(PHPicker) + `AVFoundation`, 압축은 ImageIO. 멀티파트 업로드(기존 4MB 정책 유지).
- 채팅: `ChatView`(메시지 버블 List) + `ChatViewModel`. 실시간 `URLSessionWebSocketTask`(또는 SSE). 장소 카드 = 선택 버튼 있는 커스텀 버블.
- 푸시: `UNUserNotificationCenter` + APNs. 알림 탭 → 해당 핀/방으로 딥링크.
- 내비게이션: `NavigationStack`. 온보딩 위저드 플로우 포팅.

### 화면 매핑 (기존 Next 라우트 → SwiftUI View)
| Next 라우트 | SwiftUI | 비고 |
|---|---|---|
| `/login` | `LoginView` | Kakao 네이티브 |
| `/onboarding/*` | 온보딩 플로우 | welcome/nickname/location/invite-code/notification/group-start |
| `/map` (`MapClient.tsx` 최대 파일) | `MapView` | Mapbox iOS, 핀/클러스터/롤렛 |
| `/pins` | `PinListView` | 핀 CRUD, 사진 |
| `/groups`, `/groups/new`, `/groups/invite`, `/invite/[slug]` | 그룹/초대 Views | 초대 링크 딥링크 처리 |
| `/settings`, `/settings/nickname` | `SettingsView` | (서브메뉴엔 ← 뒤로가기 필수) |
| `/bot/connect` | **삭제** | 연동 불필요 |
| — | `ChatRoomView` (신규) | 커플 봇 채팅방 |

## 3. PR / Phase 시퀀스

### Phase A — 백엔드 (additive, 웹 무중단)
- **A1**: `JwtAuthenticationFilter` Bearer 헤더 지원. (위험 0, 시작점)
- **A2**: Kakao 네이티브 로그인 엔드포인트 + 토큰 JSON 발급 + refresh.
- **A3**: `ChatMessage` 모델 + `ChatService`(핸들러 코어 재사용, 세션 groupId 키) + Chat REST.
- **A4**: 실시간(WebSocket/SSE) + APNs + `POST /devices`.
- **A5**: 봇 연동 레이어 폐기 — iOS 앱 출시 후 카카오 봇 병행 운영하다 하드 컷오버.

### Phase B — iOS 앱
- **B1**: 프로젝트 스캐폴드(SwiftUI, APIClient, Keychain, Kakao SDK, 환경설정).
- **B2**: 인증 + 온보딩 플로우.
- **B3**: 지도(Mapbox iOS) + 핀 CRUD + 사진.
- **B4**: 채팅방(신규) + 장소 카드 + 실시간.
- **B5**: 푸시 + 폴리시 + 앱스토어 제출(아이콘/권한 문구/개인정보).

## 4. 리스크 / 열린 질문

- **웹 운명**: 웹 서비스를 은퇴할지 병행할지. 병행이면 백엔드는 쿠키+Bearer 둘 다 지원(현 계획 그대로 가능).
- **카카오 봇 전환 기간**: 기존 사용자 데이터(BotUserMapping) 마이그레이션 — 이미 그룹 소속이면 자연 승계, 미연동 사용자 안내 필요.
- **인메모리 세션**: 수평 확장/재시작 취약 → 채팅 영속화와 함께 세션 DB 이전 검토(차기).
- **Mapbox 비용/SDK**: iOS SDK는 MAU 과금 모델 확인 필요.
- **Kakao 토큰 검증**: 네이티브 로그인은 Kakao access token 검증 로직 신규(웹 code 교환과 다름).

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
| 방문 감지 + 컨페티 축하 | ✅ | CoreLocation + 파티클 |
| 알림함 (현재 폴링) | ✅↑ | APNs 푸시로 격상 |
| 온보딩 위저드 | ✅ | NavigationStack 플로우 |
| 카카오 로그인 | ✅ | Kakao iOS SDK (웹 리다이렉트 대체) |
| 초대 링크 / 딥링크(`?pinId`, `/invite/[slug]`) | ✅ | Universal Links |
| 키보드 인셋 보정 (`useKeyboardInsets`) | ✅↑ | SwiftUI 키보드 회피 기본 제공 |
| 공유 시트 (`PinShareSheet`) | ✅ | `ShareLink`/`UIActivityViewController` |
| `MapClient.tsx` (1500+줄 상호작용) | ⚠️ | 기능 아닌 **포팅 분량**이 큼 — 최대 공수 화면 |

### 시각/애니 충실도 보장 방법
- `lib/design/tokens`(colors/fonts/spacing) → SwiftUI `Theme`/`Color`/`Font` 상수로 1:1 이식. 커스텀 폰트(serif/mono/sans) 번들 필수.
- 기존 작업이 디자인 번들(`screens-mobile.jsx` 등) **1:1 변환** 방식이었음 → 동일 워크플로를 SwiftUI에 적용(화면별 레퍼런스 대조 + 시각 QA).
- ⚠️ 주의: CSS flow/absolute 레이아웃 ≠ SwiftUI 레이아웃. 픽셀 정합은 화면별로 의도적 보정 필요(자동 변환 아님).

## 6. 즉시 시작점

**Phase A1 + A2**(Bearer 헤더 + Kakao 네이티브 로그인/토큰 발급)가 위험이 가장 낮고 iOS 앱의 모든 인증을 잠금 해제. 그다음 A3(ChatMessage + ChatService) — 챗봇 코어 재사용이 핵심.
