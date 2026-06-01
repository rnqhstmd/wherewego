-- ============================================================
-- V015__create_chat_tables.sql
-- P2 PR-1: 앱 채팅 — chat_room + chat_message.
--
-- 방 유형(type): BOT(개인 봇 1:1) / COUPLE(그룹 공유). 둘 중 하나만 식별자 보유:
--   BOT    → owner_user_id non-null, group_id null
--   COUPLE → group_id non-null, owner_user_id null
-- 부분 UNIQUE 인덱스로 활성 방(deleted_at IS NULL) 1개만 강제(BR-2). 봇 방 재생성/방 soft delete 후 재발급 대비.
--
-- 메시지 payload_json: JSONB. Hibernate 6 내장 @JdbcTypeCode(SqlTypes.JSON) 매핑(라이브러리 불필요).
--   도메인은 직렬화된 String 보유, 컨트롤러/STOMP 프레임에서만 JSON 노드 재파싱.
--
-- 커서 페이징(FR-5/9, AC-3): idx_chat_message_room_id_desc(room_id, id DESC) 로 최신순 + id<cursor 스캔.
--
-- 컨벤션: V001 = BIGSERIAL PK + TIMESTAMPTZ NOT NULL DEFAULT now() + deleted_at. BaseEntity IDENTITY(BIGSERIAL).
-- ============================================================

CREATE TABLE IF NOT EXISTS chat_room
(
    id            BIGSERIAL   PRIMARY KEY,
    type          VARCHAR(20) NOT NULL,
    group_id      BIGINT,
    owner_user_id BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);

-- 활성 봇 방: 사용자당 1개(BR-2). type='BOT' AND deleted_at IS NULL 부분 UNIQUE.
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_room_bot_owner
    ON chat_room (owner_user_id)
    WHERE type = 'BOT' AND deleted_at IS NULL;

-- 활성 커플 방: 그룹당 1개(BR-2). type='COUPLE' AND deleted_at IS NULL 부분 UNIQUE.
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_room_couple_group
    ON chat_room (group_id)
    WHERE type = 'COUPLE' AND deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS chat_message
(
    id             BIGSERIAL   PRIMARY KEY,
    room_id        BIGINT      NOT NULL REFERENCES chat_room (id),
    sender_type    VARCHAR(20) NOT NULL,
    sender_user_id BIGINT,
    kind           VARCHAR(20) NOT NULL,
    payload_json   JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ
);

-- 커서 페이징(FR-5/9, AC-3): room_id 필터 + id DESC + id<cursor.
CREATE INDEX IF NOT EXISTS idx_chat_message_room_id_desc
    ON chat_message (room_id, id DESC);
