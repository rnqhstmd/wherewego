-- ============================================================
-- V007__create_notifications.sql
-- Phase 8: 인앱 알림함 — notifications + notification_pins.
--
-- 데이터 모델 결정: receiver_id 단일 컬럼 + 행 fan-out.
--   알림 1건 = (group_id, receiver_id, registered_by, type, created_at).
--   그룹 N인 → 등록자 제외 (N-1)배 행. MVP 2인은 상대방 1명에 1행.
--   미읽음 인덱스가 receiver_id 단일로 단순화됨.
--
-- 보관 정책: 영구 보관(BR-2). 만료/정리 정책 없음.
-- 삭제된 핀: notification_pins 행 유지(BR-4). pins은 soft delete이므로 row 자체는 살아 있음.
-- ============================================================

CREATE TABLE IF NOT EXISTS notifications
(
    id            BIGSERIAL    PRIMARY KEY,
    group_id      BIGINT       NOT NULL REFERENCES groups (id),
    receiver_id   BIGINT       NOT NULL REFERENCES users (id),
    registered_by BIGINT       NOT NULL REFERENCES users (id),
    type          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ,
    read_at       TIMESTAMPTZ,
    CONSTRAINT chk_notifications_type
        CHECK (type IN ('MANUAL_PIN', 'CHATBOT_PINS'))
);

-- 최신순 목록 조회 (GET /notifications): receiver_id 필터 + created_at DESC + LIMIT 50.
CREATE INDEX IF NOT EXISTS idx_notifications_receiver_created
    ON notifications (receiver_id, created_at DESC);

-- 미읽음 카운트/read-all: receiver_id 필터 + read_at IS NULL.
CREATE INDEX IF NOT EXISTS idx_notifications_receiver_unread
    ON notifications (receiver_id)
    WHERE read_at IS NULL;

-- ============================================================
-- notification_pins — 알림과 포함된 핀의 N:M 조인.
-- ON DELETE CASCADE는 notifications 행 삭제 시에만 동작 (현재는 미사용).
-- pins은 soft delete이므로 pins 행은 그대로 살아 있다.
-- ============================================================
CREATE TABLE IF NOT EXISTS notification_pins
(
    id              BIGSERIAL   PRIMARY KEY,
    notification_id BIGINT      NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    pin_id          BIGINT      NOT NULL REFERENCES pins (id),
    sort_order      INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT uq_notification_pins_pair UNIQUE (notification_id, pin_id)
);

CREATE INDEX IF NOT EXISTS idx_notification_pins_notification_id
    ON notification_pins (notification_id);
