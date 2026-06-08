## 코드 맵: 그룹별 봇 DM 백엔드 (GM-2 — 그룹별 봇방 + DM 목록/읽음 + 릴스 저장 그룹)

### 핵심 파일 (변경 대상)
- backend/apps/wherewego-api/src/main/resources/db/migration/V015__create_chat_tables.sql → 기준. 신규 V020에서 chat_room BOT에 group_id 추가 + 읽음 추적(last_read_message_id) + 부분 UNIQUE 재정의
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chat/ChatRoom.java → BOT 방을 그룹별로(createBotRoom(userId, groupId)), last_read 갱신 메서드
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chat/ChatRoomRepository.java → findActiveBotRoom(userId, groupId), 사용자 활성 그룹 봇방 목록 조회
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chat/BotChatService.java → 그룹별 봇방 보장/전송/조회(postMessage(userId, groupId, text)), getBotRooms 목록 + unread 계산, 방 조회 시 읽음 처리
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/chat/ChatV1Controller.java → GET /chat/bot/rooms(목록), 그룹별 봇방 메시지 엔드포인트(/chat/bot/{groupId}/messages)
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/chat/ChatV1Dto.java → BotRoomSummary(roomId/groupId/groupName/lastMessagePreview/lastSenderType/unread)

### 참조 파일
- backend/.../domain/chat/ChatMessage.java, ChatMessageRepository.java, ChatMessageAppender.java, ChatMessagePageResult.java, ChatMessageFrame.java → 메시지 저장/조회/페이징
- backend/.../domain/chat/BotChatProcessor.java → 봇 1턴 처리(그룹 컨텍스트 영향 확인 — PLACE_CARDS는 그룹 무관, 저장은 iOS가 groupId로)
- backend/.../domain/chat/ChatRoomType.java, MessageKind.java, SenderType.java
- backend/.../domain/group/GroupMemberService.java → 사용자의 활성 그룹 목록(findActiveGroups), 멤버십 검증
- backend/.../domain/group/Group.java, GroupMember.java, GroupSummary.java → 그룹명
- backend/.../domain/chat/CoupleChatService.java → 그룹별 방 패턴 참고(group_id 부분 UNIQUE + optimistic insert 폴백)
- backend/.../domain/bot/BotUserMappingService.java → 카카오 봇 매핑(이번 범위 무관 — 카카오는 별개, 영향 없는지만 확인)
- backend/.../interfaces/api/chat/ChatV1ApiSpec.java → OpenAPI 스펙

### 설정/마이그레이션 이력
- V015(chat_room/chat_message), V018(다중그룹 unique 해제), V019(초대링크) → 최신 V019, 신규는 V020

### 비고
- 봇방은 owner 1명 전용 → 읽음(last_read)은 chat_room 단일 컬럼으로 충분(커플방 멤버별 읽음과 다름, 이번 범위 아님)
- 릴스 저장 그룹: 백엔드 봇방에 group_id 부여 → iOS(A 단계)가 방의 groupId로 pinAPI.create. 백엔드 봇방은 메시지 담당, 저장 자체는 iOS
- 카카오 챗봇(domain/chatbot/*) 무변경. iOS UI(A)·맵(C)·관리/알림(D)은 후속 PR
- references/ 없음
