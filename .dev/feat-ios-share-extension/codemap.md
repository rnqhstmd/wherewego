## 코드 맵: 인스타 공유 → 우리 앱 → 그룹 DM 다중선택 전송 (iOS Share Extension)

### 핵심 파일 (재사용/변경)
- ios/WhereWeGo/Core/Keychain/KeychainTokenStore.swift → actor 토큰 저장소. `service="com.wherewego.tokens"`, **access group 미사용**. `performRefresh`(baseURL+session+refreshToken만 의존, Bearer 불요)는 익스텐션 재사용 가능. **공유 keychain access group 추가가 핵심 전제**(익스텐션이 토큰 읽기).
- ios/WhereWeGo/Core/Networking/APIClient.swift → `request(path,method,body,type)` Bearer 부착 + `/api/v1` 프리픽스 + 401→TokenStore.refresh 재시도. APIError{code,status,message}. 익스텐션이 재사용하거나 슬림 복제.
- ios/WhereWeGo/Features/Chat/ChatAPI.swift → `botRooms()→[BotRoomSummary]`(GET /chat/bot/rooms) + `sendBotMessage(groupId,text)`(POST /chat/bot/{groupId}/messages). **익스텐션의 목록+전송 재사용 대상**.
- ios/WhereWeGo/App/WhereWeGo.entitlements → 현재 App Group/Keychain Sharing 없음. **App Group(group.com.wherewego.app) + keychain-access-group 추가 대상**.
- ios/project.yml → XcodeGen. WhereWeGo 단일 app 타겟. **Share Extension 타겟 신규 추가 대상**(type: app-extension, NSExtension share-services, App Group).

### 참조 파일
- ios/WhereWeGo/App/AppConfig*(Info.plist API_BASE_URL=$(API_BASE_URL)) → 익스텐션도 동일 baseURL 필요(공유 설정/xcconfig).
- ios/WhereWeGo/App/AppDependencies.swift → APIClient/ChatAPI/KeychainTokenStore 조립. 익스텐션은 경량 자체 조립.
- ios/WhereWeGo/Features/Chat/BotRoomSummary(ChatAPI 응답 모델) → 그룹 체크박스 목록 소스(그룹명/groupId).
- ios/WhereWeGo/Core/Session/CurrentUser.swift → (필요 시) 사용자 식별.

### 참조 파일 (백엔드 — 변경 없음, 재사용)
- backend/.../interfaces/api/chat/ChatV1Controller.java:40 GET /chat/bot/rooms · :49 POST /chat/bot/{groupId}/messages → 익스텐션이 호출(목록+전송).
- backend/.../domain/chat/BotChatProcessor.java + BotPlaceCardsPayloadBuilder.java → 봇이 메시지 URL에서 장소 추출(릴스 저장). 익스텐션이 URL을 봇 메시지로 보내면 동일 처리.

### 신규 (Share Extension 타겟)
- ios/ShareExtension/ShareViewController.swift → 진입점(NSExtension). 공유 URL 수신(NSItemProvider public.url/plain-text).
- ios/ShareExtension/(그룹 체크박스 멀티선택 UI) → 그룹 목록 + 선택 + 전송.
- ios/ShareExtension/Info.plist → NSExtensionActivationRule(공유 URL 1건 허용).
- ios/ShareExtension/ShareExtension.entitlements → App Group + keychain-access-group(메인 앱과 동일).
- (공유 코드) 토큰 읽기 + 최소 네트워킹 + ChatAPI 재사용 경로.

### 제약 (durable)
- iOS Windows 빌드 불가 → GitHub Actions(macOS 시뮬)가 빌드 검증. **인스타 실제 공유 동선은 시뮬에 인스타 없음 → 실기기(Mac DoD-B)에서만 검증**.
- App Group/Keychain Sharing: 시뮬 개발빌드는 portal 등록 불요, **기기/릴스는 Apple Developer portal App Group 컨테이너 등록 + provisioning 필요(Mac)**.
