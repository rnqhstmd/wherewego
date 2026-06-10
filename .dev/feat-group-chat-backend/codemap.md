# 코드 맵: GC-1 — 백엔드 그룹 채팅 + 릴스 등록 기반

> 경로 접두사: `backend/apps/wherewego-api/src/main/java/com/wherewego/`

## 핵심 파일
- `domain/chat/CoupleChatService.java` → COUPLE 방 전송/조회/커서 페이징 + `broadcastToOthersAfterCommit`(발신자 제외 활성 멤버 APNs, :113-128). GROUP 일반화의 기반
- `domain/chat/MessageKind.java` → TEXT/PLACE_CARDS/MEMO_PROMPT/PROCESSING/SYSTEM — `REEL_LINK` 추가 지점
- `domain/chat/ChatRoom.java` + `ChatRoomType.java` → 방 엔티티(BOT/COUPLE, 그룹당 활성 1방 부분 UNIQUE, V020 last_read_message_id=봇 owner 전용)
- `domain/chat/BotChatProcessor.java` → `extractHits`(스크래핑→Gemini→Kakao Local, stateless) — 온디맨드 추출 API가 재사용. `INSTAGRAM_URL` 정규식 보유
- `interfaces/api/chat/ChatV1Controller.java` + `ChatV1Dto.java` + `ChatV1ApiSpec.java` → 채팅 REST 진입점(couple/bot 엔드포인트, deprecated `/bot/messages` 포함)

## 참조 파일
- `domain/chat/ChatMessage.java` → 메시지 엔티티(sender_type, kind, payload_json JSONB, sender_user_id)
- `domain/chat/ChatMessageRepository.java` + `infrastructure/chat/ChatMessageRepositoryAdapter.java` → 커서 페이징(room_id, id DESC)
- `domain/chat/BotRoomSummary.java` → 방 목록 요약 선례(미생성 그룹=가상항목) — FR-GC1-7 모델
- `domain/chat/BotChatService.java` → postMessage→PROCESSING→afterCommit processAsync, getBotRooms, markRead — 무변경(BR-GC1-1), 패턴 참조만
- `domain/pin/Pin.java` → `instagram_url`, `validateInstagramUrl`(https 가드), `UNIQUE(group_id, instagram_url)` — registered 파생의 기반
- `backend/apps/wherewego-api/src/main/resources/db/migration/V015__create_chat_tables.sql` → chat_room/chat_message 스키마 원본
- `backend/apps/wherewego-api/src/main/resources/db/migration/V020__bot_room_per_group.sql` → 방 분리·읽음 포인터·soft delete 선례. **다음 번호 V021**

## 신규 (GC-1 구현 산출물)
- `domain/chat/GroupChatService.java` → 그룹 채팅 본체: kind 분기 전송·registered 배치 파생·멤버별 읽음·방 목록·온디맨드 추출 진입(검증 체인 후 ReelPlaceExtractor 위임)
- `domain/place/ReelPlaceExtractor.java` → 추출 파이프라인 공용 진입(supports/extract/hitsFromParsed) — BotChatProcessor 가 위임, GC-3 봇 제거 후에도 보존
- `domain/chat/ChatRoomRead.java` + port + `infrastructure/chat/ChatRoomReadJpaRepository·Adapter` → V021 chat_room_reads 멤버별 읽음(markRead 역행 방지, optimistic insert 충돌 폴백)
- `domain/chat/GroupRoomSummary.java`·`GroupChatMessageFrame.java`·`GroupMessagesPage.java` → 목록/프레임/페이지 record (기존 ChatMessageFrame 무수정 — BR-GC1-1)
- `db/migration/V021__group_chat.sql` → chat_room_reads + COUPLE→GROUP UPDATE/인덱스 + 활성 그룹 백필
- `test/domain/chat/GroupChatServiceIT.java` → AC-1~8 통합 테스트(Testcontainers, ReelPlaceExtractor/Push mock)

## 설정
- `backend/apps/wherewego-api/src/main/resources/application.yml` → `place.instagram.scraping-enabled` feature flag, 추출 deadline 관련 설정 위치
- `.claude/config.json` → java-spring 빌드 `./gradlew build`(멀티모듈, 검증은 `./gradlew :apps:wherewego-api:test` 계열)
