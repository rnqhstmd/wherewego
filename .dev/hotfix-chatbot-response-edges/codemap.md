## 코드 맵: 챗봇 응답 엣지 케이스 긴급 수정 (P0-1/P0-2/P0-3/P1)

### 핵심 파일
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/UnknownHandler.java → 미분류 발화의 fallback 응답. **P0-1: 연동/pending 상태별 분기 필요.** 현재 모든 사용자에게 "🔗 그룹 연동하기" QuickReply가 표시되어 오해 유발.
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/InstagramLinkHandler.java:311 → `processWithMemoAsync` 메모 수신 후 비동기 처리 + callback push. **P0-2: push 실패 시 빈 body 가드(:337)로 인해 silent failure 발생.**
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/InstagramPendingMemoHandler.java:53 → pending 사라진 경우(`pendingOpt.isEmpty()`) **P0-3: 사용자 메모 silent drop.** :67 utterance 처리에서 **P1: "그룹 연동하기" 텍스트가 메모로 저장되는 버그.**
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/ChatbotWebhookService.java:60 → 라우터 진입점. UnknownHandler 호출 시 botUserKey 함께 전달해야 P0-1 분기 가능.
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/MessageClassifier.java → 분류 우선순위. 본 수정에서는 변경 없음, 참조용.

### 참조 파일
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/bot/BotUserMappingService.java → `resolveUserId(botUserKey)`로 연동 여부 체크 (UnknownHandler 분기에 사용).
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/PendingInstagramSession.java → pending URL 보관 (UnknownHandler에서 peek으로 "처리 중" 분기 판단).
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/PendingNotificationSession.java → 비동기 결과 다음 발화 prepend. P0-2의 fallback 적재 대상.
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/RecentlyAutoSavedSession.java → URL 단위 RESEND-1 가드. P0-3에서 사용자 echo back 시 직전 URL 노출에 사용.
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/chatbot/callback/KakaoCallbackClient.java → callback push 클라이언트.
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/chatbot/ChatbotV1Dto.java → SkillResponse DTO. QuickReply.message 헬퍼.
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/LinkCodeHandler.java → 연동 코드 처리. UnknownHandler 분기와 일관된 응답 톤 참조.

### 설정
- backend/apps/wherewego-api/src/main/java/com/wherewego/config/cache/CacheConfig.java → INSTAGRAM_PENDING(60s×5 TTL), INSTAGRAM_PENDING_NOTIFICATION(7일), INSTAGRAM_RECENTLY_SAVED(600s).
- backend/apps/wherewego-api/src/main/resources/application.yml → `chatbot.instagram.pending-ttl-seconds:60`, `chatbot.instagram.recently-saved-ttl-seconds:600`.
