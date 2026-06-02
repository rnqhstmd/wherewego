## 코드 맵: iOS P5 — 채팅(봇방+커플방) + 푸시(APNs)·딥링크 + 제출 자산

### 핵심 파일 (P5 신규 생성/주요 변경)
- ios/WhereWeGo/Features/Chat/ (신규) → 봇 채팅방 UI + 1:1 커플방 UI (MVVM, View+ViewModel)
- ios/WhereWeGo/Core/Networking/APIClient.swift → REST 호출 계층. chat/bot·chat/couple·devices 엔드포인트 추가 대상
- ios/WhereWeGo/App/WhereWeGoApp.swift → 앱 진입점. APNs 등록·딥링크(UNUserNotificationCenter/Universal Links) 배선 지점
- ios/WhereWeGo/App/AppDependencies.swift → DI 컨테이너. 채팅/푸시 서비스 등록 지점
- backend .../interfaces/api/chat/ChatV1Controller.java:27 → 소비 대상 REST 계약: POST/GET `/api/v1/chat/bot/messages`, POST/GET `/api/v1/chat/couple/{groupId}/messages?cursor=`

### 참조 파일
- backend .../interfaces/api/device/DeviceV1Controller.java → 디바이스 토큰 등록 `POST/DELETE /api/v1/devices` (APNs 등록 소비 대상)
- ios/WhereWeGo/Features/Map/MapView.swift + MapViewModel.swift → 기존 MVVM 패턴/네트워킹 사용 레퍼런스
- ios/WhereWeGo/Features/Auth/LoginView.swift + LoginViewModel.swift → 리뷰어 숨은 데모 로그인 진입점 후보
- ios/WhereWeGo/Core/DesignSystem/ → 디자인 토큰/테마 (채팅 UI 스타일 소스)
- ios/WhereWeGo/Core/Auth/ + Core/Keychain/ → 인증 토큰(Bearer) — 실시간(STOMP) 연결 인증에 사용
- context/design-chats/chat1.md + context/chatbot/ → 봇 채팅 도메인/디자인 레퍼런스
- context/notification/ → 푸시 알림 도메인 컨텍스트

### 설정
- ios/project.yml → XcodeGen 매니페스트. Push capability·Associated Domains entitlement·Info.plist 키 추가 지점
- ios/WhereWeGo/Info.plist → 권한 문구(이미 위치 P4), URL scheme, 푸시 관련
- ios/Config/ → xcconfig (API_BASE_URL 등 환경 설정)

### 백엔드 실시간/디바이스 계약 (PRD 단계 발견 — 소비 대상)
- backend .../config/websocket/WebSocketStompConfig.java → STOMP 엔드포인트·브로커 설정
- backend .../config/websocket/StompAuthChannelInterceptor.java → 토픽 구독 인가 (`/topic/chat/bot/{userId}`, `/topic/chat/couple/{groupId}`)
- backend .../domain/chat/ChatMessageFrame.java → STOMP 프레임 구조
- backend .../domain/chat/MessageKind.java → 메시지 종류 (TEXT/PLACE_CARDS/MEMO_PROMPT/PROCESSING/SYSTEM)
- backend .../domain/device/DeviceService.java → 디바이스 토큰 upsert·reassign 정책
- backend .../domain/chat/BotPlaceCardsPayloadBuilder.java → PLACE_CARDS payload 구조 (iOS 카드 렌더링 기준)

### 설계 단계 발견 (architect)
- backend .../domain/push/PushPayload.java → 푸시 type 상수(PIN_SAVED/COUPLE_MESSAGE/BOT_RESULT)+roomId → iOS DeepLinkRouter 라우팅 키
- backend .../infrastructure/push/apns/ApnsPushSender.java → custom property type/roomId 직렬화 (iOS userInfo 파싱 키)
- backend .../domain/chat/ChatMessageAppender.java → 메시지 append (데모 시드가 동일 메서드로 PLACE_CARDS 생성)
- backend .../domain/chat/CoupleChatService.java → 커플방 전송·푸시 트리거(pushCoupleMessage)
- ios/WhereWeGo/Features/Onboarding/NotificationView.swift → 알림 권한 온보딩 화면 (FR-17 권한 요청 타이밍 후보)
- ios/Config/Debug.xcconfig + Shared.xcconfig → 데모 자격증명·AASA 도메인 주입 위치 (BR-7)

### design-critic 검증 핵심 (구현 시 필수 주의)
- backend .../domain/pin/PinService.java → `addPin`은 UNIQUE 충돌 시 PLC_DUPLICATE_PIN(409) 던짐(흡수 안 함). 챗봇 `registerFromSelectionWithDedup`는 흡수 → iOS 우회 저장은 409를 "이미 저장됨"으로 흡수 처리
- backend .../domain/chat/ChatMessageRepository.java → `findByRoomIdBefore(roomId,cursor,limit)`: cursor=null→최신, non-null→`id<cursor`(과거만). AC-9 재연결 보완은 cursor=null 재조회+dedup
- backend .../config/security/SecurityConfig.java → `/api/v1/auth/**`·`/ws/chat` permitAll. STOMP 인증은 인터셉터 CONNECT에서 수행
- backend .../interfaces/api/pin/PinV1Controller.java → `POST /api/v1/groups/{groupId}/pins`가 pushPinSaved(파트너 PIN_SAVED) 트리거 — AC-3 푸시 경로
- backend .../domain/chat/BotChatProcessor.java → PLACE_CARDS append 후 pushBotResult. PROCESSING 별도 저장 → SendMessageResponse messageId로 추적·교체

### 데모 인증 검증 (architect 확정)
- backend .../domain/auth/AuthService.java:184-216 → refresh 토큰 1회용 회전(L212 replaceRefreshTokenHash). 데모 회전 예외 추가 지점
- backend .../domain/auth/jwt/RefreshTokenHasher.java → 회전 예외 구현 시 해시 비교 참조
- backend .../interfaces/api/user/UserV1Controller.java + UserV1Dto.java → GET /users/me가 id:Long 반환 (Q3 해소)
- backend .../domain/group/InviteLinkBackfillRunner.java → ApplicationRunner+멱등 시드 패턴 (DemoSeedRunner 모델)
- backend .../domain/chat/ChatMessagePageResult.java → hasMore/nextCursor 계산 (iOS 페이지네이션 정합)
- ios/WhereWeGo/Features/Auth/AuthAPI.swift:16 → 기존 UserResponse에 id:Int 보유 (CurrentUser 재사용)

### Privacy Manifest 확인
- PrivacyInfo.xcprivacy SDK 자체 내장 여부 (빌드 검증 후 누락 시 보강)
