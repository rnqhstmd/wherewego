-- ============================================================
-- V012__pin_experience_v2.sql
-- Phase 12 → Phase 13 단순화: 오래된 핀 정리 기능만 남긴다.
--
-- Phase 13(WANT 폐기 · WISH 일원화)에서 WANT 시스템 전체를 제거하여 V012 를 직접 수정했다.
-- (V012 는 운영 미반영 · 로컬 도커 PG 만 적용 상태이므로 V013 신설 대신 V012 재작성.)
--
-- 남기는 것:
--  1) pins 정리 대상 조회 가속 인덱스 idx_pins_cleanup (FR-PIN-12-23)
--  2) users.cleanup_snoozed_until — 오래된 핀 정리 배너 snooze 다기기 일관성 (D-11)
--
-- 제거한 것 (Phase 13):
--  - pin_events 테이블 + 인덱스 전체 (WANT 이력)
--  - pins.want_count 컬럼 + idx_pins_group_want_count
--  - notifications WISH_CONVERTED CHECK 확장 · wish_pin_id 컬럼 · uq_notifications_wish_converted
-- ============================================================

-- ─── 1) 정리 대상 조회 가속 인덱스 ───────────────────────
-- chatbot AUTO 메모 REEL 핀 중 deleted_at 미설정 행만 (group_id, created_at) 으로 정렬/필터.
CREATE INDEX idx_pins_cleanup
    ON pins (group_id, created_at)
    WHERE tag = 'REEL'
      AND memo_source = 'AUTO'
      AND deleted_at IS NULL;

-- ─── 2) users.cleanup_snoozed_until ──────────────────────
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS cleanup_snoozed_until TIMESTAMPTZ NULL;

COMMENT ON COLUMN users.cleanup_snoozed_until IS
    'Phase 12: 오래된 핀 정리 배너 snooze 만료 시각. NULL = snooze 없음. 다기기 일관성을 위해 user 단위 영속화 (D-11).';
