# 설계서 — GC-1: 백엔드 그룹 채팅 + 릴스 등록 기반

> 확정: 2026-06-10. PRD: `.dev/feat-group-chat-backend/prd.md`
> 설계 Q&A 확정: Q1 COUPLE→GROUP 전면 리네임 / Q2 couple 엔드포인트 즉시 제거
> 설계 규모: 중형 (신규 9, 수정 15)

## 1. 핵심 설계 결정

| # | 결정 | 근거 |
|---|------|------|
| D1 | **`ChatRoomType.COUPLE` → `GROUP` 전면 리네임** — V021 `UPDATE chat_room SET type='GROUP'` + `uq_chat_room_couple_group` → `uq_chat_room_group_group` 재생성 + Java 전체 정합 | COUPLE 방은 이미 그룹 공용 방 구조(groupId만 보유, `ensureCoupleRoom`·`softDeleteByGroup` 완비). 클라 미사용인 지금이 리네임 마지막 기회 |
| D2 | **`CoupleChatService` → `GroupChatService` 확장 리네임** — kind 분기·registered 파생·멤버별 읽음·방 목록 흡수 | 전송/조회/방확보(optimistic insert+충돌 폴백)/푸시(afterCommit) 골격 재사용. 목록·preview·unread·읽음은 `BotChatService` 패턴 이식 |
| D3 | **추출 로직 `ReelPlaceExtractor`(domain/place 신규) 분리**, `BotChatProcessor.extractHits`는 위임 | 봇은 GC-3 제거 예정 — 파이프라인 선분리. 봇 호출부=예외 swallow(현행 보존), API 호출부=CoreException 전파(PLC_* 502) |
| D4 | **새 프레임 `GroupChatMessageFrame`** `(messageId, senderUserId, senderNickname, kind, payload, registered, createdAt)` — 기존 `ChatMessageFrame` 무수정 | 봇 응답 JSON 불변(BR-GC1-1). senderNickname 서버 배치 조회(탈퇴 멤버는 멤버 목록에 없어 클라 해석 불가) |
| D5 | **읽음 전진=메시지 조회 시 자동**(별도 endpoint 없음) — `chat_room_reads` upsert(optimistic insert+`DataIntegrityViolationException` 폴백 재조회), `markRead` 역행 방지 | 봇 `markRoomRead` 패턴 동일. unread 판정: `latest.senderUserId != me && (lastRead==null \|\| lastRead < latest.id)` (sender NULL=타인 취급) |
| D6 | **deadline 15s = `place.search.extract-deadline-ms` 신설**(`PlaceProperties.Search.extractDeadlineMs`). 기존 `sync-deadline-ms: 4500` 무변경 | 카카오 웹훅 SLA와 독립 노브 |

## 2. 데이터 모델 — V021__group_chat.sql

```sql
-- 1) 멤버별 읽음 (FR-GC1-2)
CREATE TABLE IF NOT EXISTS chat_room_reads (
    id                   BIGSERIAL   PRIMARY KEY,
    room_id              BIGINT      NOT NULL REFERENCES chat_room (id),
    user_id              BIGINT      NOT NULL,
    last_read_message_id BIGINT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at           TIMESTAMPTZ,
    CONSTRAINT uq_chat_room_reads_room_user UNIQUE (room_id, user_id)
);
-- 2) COUPLE → GROUP 일반화 (D1; couple 행은 데모 시더 외 사실상 0건)
UPDATE chat_room SET type='GROUP', updated_at=now() WHERE type='COUPLE';
DROP INDEX IF EXISTS uq_chat_room_couple_group;
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_room_group_group
    ON chat_room (group_id) WHERE type='GROUP' AND deleted_at IS NULL;
-- 3) 활성 그룹 백필 (PRD Q1: 그룹 생성 시 자동 + 백필)
INSERT INTO chat_room (type, group_id)
SELECT 'GROUP', g.id FROM groups g
WHERE g.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM chat_room r
                  WHERE r.type='GROUP' AND r.group_id=g.id AND r.deleted_at IS NULL);
```
- user_id FK는 users 테이블 참조 — V001 기존 FK 컨벤션 확인 후 동일하게(없으면 생략 일관성 유지)
- `MessageKind.REEL_LINK` 추가. payload `{"url": "...", "thumbnailKey": null}`
- registered 컬럼 없음 — `PinRepository.findActiveInstagramUrlsIn(groupId, urls)` IN 쿼리 1회
  (`uq_pins_group_instagram_place(group_id, instagram_url, place_name)` prefix 인덱스 커버)
- 롤백 노트(V020 선례 따름): DROP TABLE chat_room_reads; UPDATE 역방향 + 인덱스 원복. UPDATE 는 couple 행 0건 가정이라 사실상 no-op

## 3. API 명세

### 신규 4종 (`/api/v1/chat/groups`)

1. **`GET /api/v1/chat/groups`** — 방 목록(FR-GC1-7)
   - 응답: `[{roomId, groupId, groupName, lastPreview, lastSenderUserId, hasUnread, lastAt}]`
   - `listMyGroups` 순회, 방 없으면 가상항목(roomId/preview/lastAt=null, hasUnread=false — BotRoomSummary AC-7 선례)
   - preview 규칙: TEXT=text 앞 40자, REEL_LINK=「릴스 링크」, 레거시 kind=봇 previewOf 규칙 재사용
2. **`POST /api/v1/chat/groups/{groupId}/messages`** — 전송(FR-GC1-1/3/8)
   - body `{kind: "TEXT"|"REEL_LINK", text?, url?}` → `{messageId, kind}`
   - TEXT: 1~2000자(`CHAT_TEXT_INVALID` 400) / REEL_LINK: `https://` 필수 + `INSTAGRAM_URL` 패턴(`CHAT_REEL_URL_INVALID` 400) / kind 그 외(`CHAT_KIND_INVALID` 400)
   - 멤버십 강제(403 `GROUP_NOT_MEMBER`) → `ensureGroupRoom`(get-or-create 안전망) → append → afterCommit `pushGroupMessage`(발신자 제외 전 활성 멤버, kind별 문구, 1인 그룹 생략, best-effort)
3. **`GET /api/v1/chat/groups/{groupId}/messages?cursor&limit`** — 조회(FR-GC1-4)
   - 응답: `{groupId, messages: [GroupChatMessageFrame], hasMore, nextCursor}` (limit 1~50 클램프, 기존 동일)
   - registered: 페이지 내 REEL_LINK url 수집 → IN 쿼리 1회 → REEL_LINK만 Bool, 그 외 null
   - senderNickname: senderUserId 배치 조회(UserRepository), NULL sender → null
   - **내 읽음 포인터 전진**(D5). 방 없으면 빈 페이지
4. **`POST /api/v1/chat/groups/{groupId}/messages/{messageId}/extract`** — 온디맨드 추출(FR-GC1-5/6)
   - 검증 체인: 멤버십(403 `GROUP_NOT_MEMBER`) → 메시지 존재+그 그룹의 활성 GROUP 방 소속(404 `CHAT_MESSAGE_NOT_FOUND`) → kind=REEL_LINK(400 `CHAT_NOT_REEL_LINK`) → `sender_user_id == userId`·NULL 거부(403 `CHAT_EXTRACT_FORBIDDEN`)
   - `ReelPlaceExtractor.extract(url, extractDeadlineMs=15000)` — 트랜잭션 밖 외부 호출, 메시지 append 없음
   - 응답: `{cards: [{kakaoPlaceId, name, address, latitude, longitude}], sourceInstagramUrl}` (기존 `PlaceCardsPayload` 모양 = iOS ReelSaveWizard 디코더 호환)
   - 0곳=200 빈 cards(Should) / 파서·검색 실패=PLC_* CoreException 전파(502, 재시도 가능)

### ErrorType 신설 (6)
`CHAT_KIND_INVALID`(400) · `CHAT_TEXT_INVALID`(400) · `CHAT_REEL_URL_INVALID`(400) · `CHAT_MESSAGE_NOT_FOUND`(404) · `CHAT_NOT_REEL_LINK`(400) · `CHAT_EXTRACT_FORBIDDEN`(403, "발신자만 장소를 등록할 수 있어요.")

### 기존 표면 처리 (Q2 확정)
- `POST/GET /api/v1/chat/couple/{groupId}/messages` **즉시 제거** + `ChatV1Dto.CoupleMessageRequest` 삭제
- `PushPayload.coupleMessage`/`pushCoupleMessage`/`TYPE_COUPLE_MESSAGE` 삭제 → `groupMessage(roomId, kind)`/`pushGroupMessage`/`TYPE_GROUP_MESSAGE` 대체 (문구: TEXT="멤버가 메시지를 보냈어요." / REEL_LINK="멤버가 릴스를 공유했어요.")
- 봇 표면(`/chat/bot/*`·BotChatService·웹훅) **무변경** (BR-GC1-1)

## 4. 변경 범위

**신규 (9)**
| 파일 | 역할 |
|---|---|
| `db/migration/V021__group_chat.sql` | §2 |
| `domain/chat/ChatRoomRead.java` | 엔티티(roomId, userId, lastReadMessageId, markRead 역행 방지 — ChatRoom.markRead 동형) |
| `domain/chat/ChatRoomReadRepository.java` | port: save / findByRoomIdAndUserId |
| `infrastructure/chat/ChatRoomReadJpaRepository.java` + `ChatRoomReadRepositoryAdapter.java` | JPA 어댑터 |
| `domain/chat/GroupChatService.java` | 전송(kind 분기)·조회(registered+읽음)·목록(unread) — CoupleChatService 흡수 |
| `domain/chat/GroupRoomSummary.java` | 목록 record |
| `domain/chat/GroupChatMessageFrame.java` | D4 프레임(payload 재파싱은 ChatMessageFrame.parsePayload 패턴) |
| `domain/place/ReelPlaceExtractor.java` | INSTAGRAM_URL 판정+parser resolve+candidate 루프(BotChatProcessor에서 이동), `extract(url, deadlineMs)` → `List<PlaceSearchHit>`, CoreException 전파 |

**수정 (15)**: `ChatRoomType`(COUPLE→GROUP) · `ChatRoom`(createGroupRoom, guard) · `ChatRoomRepository`+JPA+Adapter(findActiveGroupRoom, softDeleteByGroup type 조건) · `MessageKind`(+REEL_LINK) · `ChatMessageAppender`(+appendReelLink: payload `{url, thumbnailKey:null}`, appendCoupleText→appendGroupText) · `CoupleChatService` **삭제** · `BotChatProcessor`(extractHits→Extractor 위임, swallow 유지) · `PlaceProperties`+`application.yml`(extract-deadline-ms: 15000) · `PushNotificationService`+`PushPayload`(D 대체) · `ChatV1Controller`+`ChatV1ApiSpec`+`ChatV1Dto`(group 4종 추가, couple 제거) · `ErrorType`(+6) · `PinRepository`+JPA+Adapter(findActiveInstagramUrlsIn) · `GroupMemberService.createGroup`(ChatRoomRepository 주입 — repo port라 서비스 순환 없음, 방 생성) · `DemoSeedRunner`(seedCoupleRoom→seedGroupRoom) · `UserRepository`(닉네임 배치 조회 — 기존 메서드 확인 후 필요 시 추가)

## 5. 구현 순서 (5단계)

1. **스키마+타입 정합**: V021 + GROUP 리네임 전파(엔티티/리포지토리/시더) + ChatRoomRead → 컴파일 그린
2. **추출 분리**: ReelPlaceExtractor + PlaceProperties/yml + BotChatProcessor 위임(동작 무변경)
3. **서비스**: GroupChatService + Appender.appendReelLink + Pin/User 배치 조회 + pushGroupMessage + 그룹 생성 훅
4. **인터페이스**: 컨트롤러 4종 + DTO + ErrorType + couple 표면 제거
5. **테스트+검증**: GroupChatServiceTest(전송/조회/registered/읽음/권한/1인 그룹) + extract 검증 체인 + 봇 회귀 + `./gradlew` 빌드 — 선행 실패는 베이스 대조(메모리 local-test-baseline)

## 6. GC-2 의존 계약 (이번 PR 산출물의 소비 명세)

- 푸시 type `GROUP_MESSAGE`(roomId 포함) — iOS willPresent/배경 처리 GC-2에서 배선
- `GroupChatMessageFrame` 필드 — iOS 디코더 기준
- extract 응답 = PlaceCardsPayload 모양 — ReelSaveWizard 재사용 전제

## 7. 자기 비판 검토 결과 (design-critic 직접 수행)

- [CHALLENGE] 리네임 BR-GC1-1 충돌 없음(봇·웹훅 경로 무관) / [RISK] 봇 회귀 → 위임부 try-catch 의미 보존+봇 테스트 / [SIMPLIFY] senderNickname 서버 포함 유지 / [ROOT-CAUSE] 목록 N+1 베타 규모 수용(봇 목록 동형)
