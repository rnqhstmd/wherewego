## 코드 맵: P2 — 백엔드 앱 서비스 (채팅·실시간·푸시·계정 삭제)

> 기준: `backend/apps/wherewego-api/src/main/java/com/wherewego/`. 모든 신규는 **additive**(웹 무중단). WebSocket/STOMP·APNs·chat_room/message·device는 **신규**.

### 핵심 파일
- domain/chatbot/ChatbotWebhookService.java → 카카오 webhook 진입 봇 로직. P2에서 `BotChatService`로 리팩터(입력 `(userId, text, actionPayload?)`, 세션키 userId). 핸들러 체인/Gemini/카드 재사용
- domain/chatbot/handler/PlaceCardBuilder.java → 장소 카드(PLACE_CARDS) 빌더. 봇 방 응답에 재사용
- domain/notification/NotificationService.java → 폴링 알림. P2에서 APNs push로 격상(파트너 핀 저장/커플방 새 메시지/봇 처리 완료 트리거)
- domain/user/UserService.java → 사용자 도메인. `DELETE /api/v1/users/me`(개인데이터 삭제 + 마지막 1인 시 그룹+핀 삭제 + Apple revoke) 추가
- domain/auth/AuthService.java → oauth/refresh 인증(P1 일반화 완료). Apple 토큰 revoke 경로 추가 연계

### 참조 파일
- domain/chatbot/MessageClassifier.java → 메시지 분류기. 1:1 커플방은 분류기/봇 미개입(저장+브로드캐스트만)
- domain/chatbot/handler/MessageHandler.java → 핸들러 체인 인터페이스. BotChatService 리팩터 시 재사용
- infrastructure/gemini/GeminiPlaceClient.java → Gemini 장소 추론. 봇 방 파이프라인 재사용
- interfaces/api/me/MeV1Controller.java → 내 정보 API. `DELETE /users/me` 배치 후보
- interfaces/api/group/GroupV1Controller.java → 그룹 API. 커플방 group_id·멤버십·그룹 삭제 연계
- domain/auth/jwt/JwtTokenProvider.java + config/security/JwtAuthenticationFilter → JWT 인증. WebSocket(STOMP) CONNECT 인증 연계(Bearer)
- infrastructure/auth/apple/AppleIdentityTokenVerifier.java → P1 Apple 검증. revoke는 Apple token endpoint 별도 호출 필요
- domain/group/GroupMemberService.java → 탈퇴/그룹 삭제 흐름(leaveGroup). 계정 삭제가 내부 재사용
- domain/user/UserModel.java → isActive()=deletedAt==null soft-delete 구조. 계정 삭제 마킹 기준

### 설정
- backend/apps/wherewego-api/build.gradle(.kts) → 의존성 추가(spring-websocket/STOMP, APNs 클라이언트 e.g. pushy)
- backend/modules/jpa/src → JPA 엔티티 위치(chat_room, chat_message, device 신규)
- backend/apps/wherewego-api/src/main/resources/db/migration/ → Flyway. P1=V014. P2 신규 마이그레이션 V015~(chat_room/chat_message/device)

### 신규 (P2에서 생성)
- 채팅: chat_room(id, group_id, type[BOT|COUPLE], owner_user_id NULL허용), chat_message(id, room_id, sender_type, sender_user_id, kind, payload_json, created_at)
- REST: POST/GET /api/v1/chat/bot/messages, POST/GET /api/v1/chat/couple/{groupId}/messages?cursor=
- 실시간: WebSocket(STOMP) 봇 방·커플방 토픽
- 푸시: APNs(.p8 토큰 기반) + POST/DELETE /api/v1/devices
