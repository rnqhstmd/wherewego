# 설계: 그룹별 봇 DM 백엔드 (GM-2 · B단계)

## 설계 규모: 중형 (DB 마이그레이션 + 서비스/API 재구성 + 하위호환)

## 확정된 설계 결정
1. **봇방 생성 시점 = lazy**: 그룹 참여 시 자동 생성하지 않는다. DM 목록은 `listMyGroups`로 활성 그룹을 전부 표시하되, 봇방이 없는 그룹은 "가상 항목"(roomId=null, preview=null, unread=false)으로 내려준다. 실제 방은 첫 메시지 전송 시 `ensureBotRoom`이 생성한다.
2. **기존 BOT 방 마이그레이션 = soft delete**: 베타 규모이므로 group_id 없는 레거시 BOT 방은 V020에서 soft delete한다(이력 손실 수용). 사용자가 다시 보내면 그룹별 새 방이 생성된다.
3. **읽음 저장 = `chat_room.last_read_message_id` 단일 컬럼**: 봇방은 owner 1명 전용이라 멤버별 읽음이 불필요.
4. **기존 `/chat/bot/messages` 엔드포인트 = deprecated 유지**: develop의 현 iOS 봇이 groupId 없이 호출 중이므로, 내부적으로 "최신 활성 그룹" 봇방으로 폴백시켜 현행 동작을 보존한다. A단계에서 iOS가 신규 API로 전환한 뒤 제거한다.

## 변경 범위 (신규 1 · 수정 8)
- **신규**: `db/migration/V020__bot_room_per_group.sql`
- **수정**: `ChatRoom`, `ChatRoomRepository`(+adapter), `ChatMessageRepository`(+adapter, 필요 시), `BotChatService`, `ChatV1Controller`, `ChatV1Dto`, `ChatV1ApiSpec`

## 1. DB — V020
```sql
ALTER TABLE chat_room ADD COLUMN last_read_message_id BIGINT;

-- 레거시 BOT 방(group_id 없음) soft delete (베타 규모, 이력 손실 수용)
UPDATE chat_room SET deleted_at = now(), updated_at = now()
  WHERE type = 'BOT' AND group_id IS NULL AND deleted_at IS NULL;

-- BOT 활성 1개 강제를 (owner, group)별로 재정의
DROP INDEX IF EXISTS uq_chat_room_bot_owner;
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_room_bot_owner_group
  ON chat_room (owner_user_id, group_id)
  WHERE type = 'BOT' AND deleted_at IS NULL;
```
(group_id 컬럼은 V015에 이미 존재 — BOT도 채워 재사용)

## 2. ChatRoom (엔티티)
- `guard()`: BOT 분기를 "ownerUserId + groupId 모두 필수"로 변경(기존 "BOT은 groupId 불가" 제거). COUPLE은 그대로.
- `createBotRoom(Long ownerUserId, Long groupId)` — group_id 세팅.
- `lastReadMessageId` 필드 + `markRead(Long messageId)` — 더 큰 id로만 갱신(역행 방지).

## 3. Repository
- `ChatRoomRepository.findActiveBotRoom(Long ownerUserId, Long groupId)` — 시그니처 변경(어댑터 쿼리 owner+group).
- `findActiveBotRoom(Long ownerUserId)` 단일 인자 버전은 **deprecated 유지**(구 엔드포인트 폴백용, 최신활성그룹으로 위임하므로 실제론 BotChatService가 처리 → 어댑터에는 owner+group 단일 메서드만 두고 서비스가 groupId 결정).
- 방 최신 메시지: 기존 `findByRoomIdBefore(roomId, null, 1)` 재사용(신규 메서드 불필요).
- `save`로 `last_read_message_id` 갱신(markRead 후 save) — JPA dirty checking.

## 4. BotChatService
- `ensureBotRoom(userId, groupId)`: `findActiveBotRoom(userId, groupId)` → 없으면 `save(createBotRoom(userId, groupId))`. 동시성은 부분 UNIQUE + optimistic insert/conflict 재조회 폴백(CoupleChatService 패턴 그대로).
- `postMessage(userId, groupId, text)`: `groupMemberService.requireActiveMembership(userId, groupId)` → ensureBotRoom → appendUserText + appendBotProcessing → afterCommit `processAsync`(기존 흐름 유지).
- `getBotMessages(userId, groupId, cursor, limit)`: requireActiveMembership → findActiveBotRoom → 페이지 조회 → **읽음 처리**(방 최신 메시지 id로 `markRead` + save). 방 없으면 빈 페이지.
- `getBotRooms(userId)`: `listMyGroups(userId)` 순회 → 각 그룹 `findActiveBotRoom` → 있으면 최신 메시지로 preview/lastSenderType/unread/lastAt 계산, 없으면 가상 항목.
- **하위호환 래퍼**: `postMessage(userId, text)` / `getBotMessages(userId, cursor, limit)` → `findLatestActiveGroupIdByUserId(userId)`로 groupId 결정 후 그룹별 메서드에 위임(deprecated).

## 5. unread / preview 계산 규칙
- 방 최신 메시지 = `findByRoomIdBefore(roomId, null, 1)`의 첫 원소.
- `unread = latest != null && latest.senderType == BOT && (lastReadMessageId == null || lastReadMessageId < latest.id)`.
- `preview`: TEXT/SYSTEM/MEMO_PROMPT → payload text 앞 40자, PLACE_CARDS → "장소 N곳", PROCESSING → "답장을 준비하고 있어요". 메시지 없음 → null.

## 6. API (ChatV1Controller + ChatV1Dto + ApiSpec)
- `GET /chat/bot/rooms` → `List<BotRoomSummary>`
  - `BotRoomSummary(Long roomId?, Long groupId, String groupName, String lastPreview?, SenderType lastSenderType?, boolean unread, String lastAt?)`
- `POST /chat/bot/{groupId}/messages` → `SendMessageResponse`
- `GET /chat/bot/{groupId}/messages?cursor=&limit=` → `MessagesResponse`(groupId 포함)
- (유지/deprecated) `POST /chat/bot/messages`, `GET /chat/bot/messages` → 최신활성그룹 폴백.
- 모두 `@AuthUser Long userId`.

## 7. BotChatProcessor
- 변경 없음. PLACE_CARDS 반환만 담당(그룹 무관). 릴스 저장 자체는 iOS(A)가 방의 groupId로 `pinAPI.create`.

## 8. 구현 순서
1. V020 마이그레이션 (의존: 없음)
2. ChatRoom guard/팩토리/lastRead (의존: 없음)
3. Repository 시그니처 + 어댑터 쿼리 (의존: 2)
4. ChatV1Dto BotRoomSummary + MessagesResponse groupId (의존: 없음)
5. BotChatService 그룹별 메서드 + 호환 래퍼 (의존: 2,3)
6. ChatV1Controller + ApiSpec (의존: 4,5)
7. 테스트(IT/단위) (의존: 6)

## 9. 테스트
- 그룹별 봇방 생성/격리(다른 그룹 메시지 안 섞임)
- DM 목록: 봇방 있는 그룹 + 없는 그룹(가상 항목) 혼합
- unread 전이: 봇 메시지 후 unread=true → 조회 후 false, USER 마지막 → false
- 멤버십 403(비멤버 그룹 봇방 접근)
- 마이그레이션: 레거시 BOT 방 soft delete + 신규 UNIQUE

## 10. 호환 / 리스크
- develop 현 iOS 봇(/chat/bot/messages, groupId 없음): deprecated 엔드포인트로 보존(최신활성그룹 폴백). A단계서 신규 API 전환 후 제거.
- 기존 BOT 방 이력: soft delete(베타 수용).
- PR #104(미머지)와는 영역 분리(저쪽은 payload/iOS, 이쪽은 방 구조/DM). 머지 시 BotChatProcessor만 인접 — 충돌 경미 예상.
