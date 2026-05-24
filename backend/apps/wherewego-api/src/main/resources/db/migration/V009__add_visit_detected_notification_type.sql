-- ============================================================
-- V009__add_visit_detected_notification_type.sql
-- Phase 10: VISIT_DETECTED 알림 유형 도입.
--
-- 1) chk_notifications_type 확장: VISIT_DETECTED 허용.
-- 2) notifications.visit_pin_id (nullable) — VISIT_DETECTED 타입에서만 채워짐.
--    기존 MANUAL_PIN/CHATBOT_PINS는 NULL 유지(notification_pins로 연결).
--    FK → pins(id). pin이 soft-delete 되어도 row는 살아 있어 무결성 유지.
-- 3) 부분 UNIQUE 인덱스 uq_notifications_visit:
--    동일 (group_id, receiver_id, registered_by, visit_pin_id) 조합으로
--    VISIT_DETECTED 알림은 1회만 생성되도록 race-free 보장.
-- ============================================================

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_type;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_type
    CHECK (type IN ('MANUAL_PIN', 'CHATBOT_PINS', 'VISIT_DETECTED'));

-- ON DELETE RESTRICT: pins 는 soft-delete(deleted_at) 정책이라 row 가 삭제되지 않으므로
-- 평상시 영향 없음. 운영 DB 에서 hard DELETE 실수가 발생해도 FK 위반으로 차단하여 알림 무결성 보장.
ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS visit_pin_id BIGINT NULL REFERENCES pins (id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_visit
    ON notifications (group_id, receiver_id, registered_by, visit_pin_id)
    WHERE type = 'VISIT_DETECTED' AND visit_pin_id IS NOT NULL;
