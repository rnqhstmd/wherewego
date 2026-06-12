-- ============================================================
-- V023__pin_visits_and_drop_visit_detected.sql
-- 방문 체크인·추억 전환 정책 v2 (B1).
--
-- 변경:
--   1) pin_visits 신설 — 핀별 방문 기록(누가·언제·SELF/TAGGED). (pin_id, user_id) UNIQUE 로
--      재방문은 visited_at 갱신, TAGGED→SELF 승격을 서비스 비관 락 안에서 upsert 한다(FR-B1).
--   2) 과거 VISIT_DETECTED 알림 행 DELETE — 정책 v2 는 알림 fan-out 을 폐기하고 채팅 카드로 대체한다(FR-B6).
--      enum 값(NotificationType.VISIT_DETECTED) 제거가 안전하도록 잔존 행을 먼저 지운다.
--   3) uq_notifications_visit / visit_pin_id 제약 정리 — VISIT_DETECTED 전용이라 폐기한다.
--
-- 컨벤션: V001 = BIGSERIAL PK + TIMESTAMPTZ NOT NULL DEFAULT now(). pins(V001)는 FK 를 걸지 않으므로
--   동일하게 pin_visits 도 FK 제약을 생략한다(핀 soft-delete 정책상 row 잔존 — 탈퇴/삭제 시 행 보존).
-- 라이브: CREATE TABLE/INDEX + 소량 DELETE(데모 외 VISIT_DETECTED 행 소수)라 짧은 락.
-- 롤백(수동 — Flyway Community 자동롤백 없음):
--   1) DROP TABLE pin_visits;
--   2) (notifications 제약 복구는 V009 정의를 재적용 — VISIT_DETECTED 재도입은 정책상 없음)
-- ============================================================

-- 1) 핀 방문 기록.
CREATE TABLE IF NOT EXISTS pin_visits
(
    id         BIGSERIAL   PRIMARY KEY,
    pin_id     BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    visited_at TIMESTAMPTZ NOT NULL,
    source     VARCHAR(10) NOT NULL,   -- SELF | TAGGED
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_pin_visits UNIQUE (pin_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_pin_visits_pin ON pin_visits (pin_id);

-- 2) 과거 VISIT_DETECTED 알림 행 폐기(FR-B6, AC-6). enum 제거 전에 선행한다.
DELETE FROM notifications WHERE type = 'VISIT_DETECTED';

-- 3) VISIT_DETECTED 전용 제약/컬럼 정리.
DROP INDEX IF EXISTS uq_notifications_visit;

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_type;
ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_type
    CHECK (type IN ('MANUAL_PIN', 'CHATBOT_PINS'));
