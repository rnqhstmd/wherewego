## 코드 맵: 봇 채팅 WebSocket(STOMP) → 이벤트(APNs+폴링) 전환

### 핵심 파일 (변경/제거 대상)
- ios/WhereWeGo/Features/Chat/Bot/BotChatViewModel.swift → 봇방 VM. STOMP 구독·realtimeState 제거, 전송 후 폴링 + scenePhase 재조회로 전환
- ios/WhereWeGo/Core/Realtime/ChatRealtimeService.swift → STOMP 실시간 서비스. 제거
- ios/WhereWeGo/Core/Realtime/StompClient.swift → STOMP 클라이언트(WebSocket). 제거
- ios/WhereWeGo/Core/Realtime/StompFrame.swift → STOMP 프레임 파싱. 제거
- ios/WhereWeGo/Core/Realtime/ConnectionState.swift → 연결상태(배너용). 제거
- backend/apps/wherewego-api/.../domain/chat/BotChatProcessor.java:221,225 → 결과 STOMP 발행 + APNs 푸시. publishBot 호출 제거(pushBotResult 유지)
- backend/apps/wherewego-api/.../domain/chat/ChatStompPublisher.java → STOMP 발행기. 제거
- backend/apps/wherewego-api/.../config/websocket/WebSocketStompConfig.java → STOMP 엔드포인트 설정. 제거
- backend/apps/wherewego-api/.../config/websocket/StompAuthChannelInterceptor.java → STOMP 인증 인터셉터. 제거

### 참조 파일 (유지/재사용)
- ios/WhereWeGo/App/AppNotificationDelegate.swift → APNs 수신 → DeepLinkRouter.handlePush. 재사용
- ios/WhereWeGo/App/DeepLinkRouter.swift → .chat 라우팅(P7). 재사용
- ios/WhereWeGo/Features/Chat/ChatAPI.swift → REST 송수신(botMessages/sendBotMessage). 유지
- ios/WhereWeGo/Features/Chat/ChatScrollContainer.swift:42-53 → "재연결중" 배너. 제거
- ios/WhereWeGo/App/AppDependencies.swift → realtime 조립. 제거
- ios/WhereWeGo/App/MainTabView.swift → realtime 주입. 제거
- backend/apps/wherewego-api/.../domain/push/PushNotificationService.java:83 → pushBotResult(APNs). 유지
- backend/apps/wherewego-api/.../interfaces/api/chat/ChatV1Controller.java → REST 송수신 컨트롤러. 유지
- backend/apps/wherewego-api/.../domain/chat/CoupleChatService.java → publishCouple 호출 제거
- ios/WhereWeGoTests/BotChatViewModelTests.swift → 폴링 기반 재작성
- ios/WhereWeGoTests/StompFrameTests.swift → 삭제

### 설계 / 설정
- .dev/feat-ios-nav-redesign/chat-event-migration.md → 전환 설계서(SSOT)
- backend/apps/wherewego-api/build.gradle → spring-boot-starter-websocket 의존성 제거 검토
