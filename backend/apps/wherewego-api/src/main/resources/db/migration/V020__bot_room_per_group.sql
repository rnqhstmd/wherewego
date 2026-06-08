-- ============================================================
-- V020__bot_room_per_group.sql
-- GM-2 (B단계): 봇 방을 그룹별로(owner_user_id, group_id) 재정의 + 읽음 추적.
--
-- 변경 (DB는 컬럼 추가 + 인덱스 재정의만, group_id 컬럼은 V015 에 이미 존재):
--   1) chat_room.last_read_message_id 추가 — 봇 방 owner 전용 단일 읽음 포인터(멤버별 읽음 불필요).
--   2) 레거시 BOT 방(group_id 없음) soft delete — 베타 규모라 이력 손실 수용(설계 §1·AC-8).
--      사용자가 다시 보내면 ensureBotRoom 이 그룹별 새 방을 생성한다.
--   3) BOT 활성 1개 강제를 (owner_user_id) → (owner_user_id, group_id) 별로 재정의(FR-1/AC-1).
--      V015 의 uq_chat_room_bot_owner 를 DROP 후 owner+group 부분 UNIQUE 로 대체.
--
-- 라이브: ADD COLUMN(default 없음)/DROP·CREATE INDEX 짧은 락. 대규모면 CREATE INDEX CONCURRENTLY 검토.
-- 롤백(수동 — Flyway Community 자동롤백 없음):
--   1) DROP INDEX uq_chat_room_bot_owner_group;
--   2) 구 index 재생성: CREATE UNIQUE INDEX uq_chat_room_bot_owner ON chat_room (owner_user_id)
--        WHERE type = 'BOT' AND deleted_at IS NULL;  (단, group 별 다중 활성 BOT 행이 있으면 UNIQUE 위반 실패)
--   3) ALTER TABLE chat_room DROP COLUMN last_read_message_id;
--   ⚠️ 레거시 BOT 방 soft delete 는 비가역(이력 복원 불가) → 적용 전 스냅샷 권장.
-- 무영향: COUPLE 방(uq_chat_room_couple_group)·카카오 챗봇은 건드리지 않음(AC-9).
-- ============================================================

ALTER TABLE chat_room ADD COLUMN last_read_message_id BIGINT;

-- 레거시 BOT 방(group_id 없음) soft delete (베타 규모, 이력 손실 수용 — AC-8).
UPDATE chat_room SET deleted_at = now(), updated_at = now()
    WHERE type = 'BOT' AND group_id IS NULL AND deleted_at IS NULL;

-- BOT 활성 1개 강제를 (owner, group)별로 재정의(FR-1/AC-1).
DROP INDEX IF EXISTS uq_chat_room_bot_owner;
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_room_bot_owner_group
    ON chat_room (owner_user_id, group_id)
    WHERE type = 'BOT' AND deleted_at IS NULL;
