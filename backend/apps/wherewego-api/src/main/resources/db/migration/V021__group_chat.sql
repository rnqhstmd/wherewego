-- ============================================================
-- V021__group_chat.sql
-- GC-1: 모아보기(봇 티키타카) → 그룹 채팅 전환의 백엔드 기반.
--
-- 변경:
--   1) chat_room_reads 신설 — 그룹 방 멤버별 읽음 포인터(FR-GC1-2).
--      기존 chat_room.last_read_message_id(V020)는 봇 방 owner 전용 단일 포인터라 그룹 방에 부족.
--   2) chat_room.type COUPLE → GROUP 일반화(설계 D1). COUPLE 방은 구조적으로 이미 그룹 공용 방
--      (group_id 만 보유)이며 클라이언트 미사용이라 지금 리네임한다. 부분 UNIQUE 인덱스도 재정의.
--   3) 활성 그룹 백필 — 그룹 생성 시 자동 생성 정책(FR-GC1-1)에 맞춰 기존 활성 그룹에 GROUP 방을 채운다.
--
-- 라이브: CREATE TABLE/INDEX·소량 UPDATE(couple 행은 데모 시드 외 사실상 0건)라 짧은 락.
-- 롤백(수동 — Flyway Community 자동롤백 없음):
--   1) DROP TABLE chat_room_reads;
--   2) UPDATE chat_room SET type='COUPLE', updated_at=now() WHERE type='GROUP';
--      (백필로 생성된 행은 DELETE FROM chat_room WHERE type='GROUP' AND id NOT IN (메시지 보유 방) 검토)
--   3) DROP INDEX uq_chat_room_group_group;
--      CREATE UNIQUE INDEX uq_chat_room_couple_group ON chat_room (group_id)
--          WHERE type = 'COUPLE' AND deleted_at IS NULL;
-- 무영향: BOT 방(uq_chat_room_bot_owner_group)·카카오 웹훅 챗봇은 건드리지 않음(BR-GC1-1).
-- ============================================================

-- 1) 멤버별 읽음 포인터. (room_id, user_id)당 1행, 컨벤션은 V001/V015 동일
--    (BIGSERIAL PK + TIMESTAMPTZ DEFAULT now() + deleted_at, FK 는 chat_message.room_id 선례만 따름).
CREATE TABLE IF NOT EXISTS chat_room_reads
(
    id                   BIGSERIAL   PRIMARY KEY,
    room_id              BIGINT      NOT NULL REFERENCES chat_room (id),
    user_id              BIGINT      NOT NULL,
    last_read_message_id BIGINT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at           TIMESTAMPTZ,
    CONSTRAINT uq_chat_room_reads_room_user UNIQUE (room_id, user_id)
);

-- 2) COUPLE → GROUP 일반화.
UPDATE chat_room SET type = 'GROUP', updated_at = now() WHERE type = 'COUPLE';

DROP INDEX IF EXISTS uq_chat_room_couple_group;
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_room_group_group
    ON chat_room (group_id)
    WHERE type = 'GROUP' AND deleted_at IS NULL;

-- 3) 활성 그룹 백필 — 활성 GROUP 방이 없는 활성 그룹에 방을 생성한다(멱등).
INSERT INTO chat_room (type, group_id)
SELECT 'GROUP', g.id
FROM groups g
WHERE g.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1
                  FROM chat_room r
                  WHERE r.type = 'GROUP'
                    AND r.group_id = g.id
                    AND r.deleted_at IS NULL);
