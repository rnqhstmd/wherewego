-- ============================================================
-- V012__pin_experience_v2.sql
-- Phase 12: Pin Experience v2 — WANT(관심 표현) 시스템 + 오래된 핀 정리 + WISH_CONVERTED 알림.
--
-- Flyway 단일 트랜잭션으로 다음을 원자적으로 적용한다:
--  1) pin_events 테이블 신규 — WANT 이력 (Phase 12.2에서 VIEW/SHARE/ROULETTE_SELECTED 확장 예정)
--     - 부분 UNIQUE 인덱스로 (pin_id, user_id) WHERE action='WANT' 영구 멱등 보장 (D-19)
--  2) pins.want_count 컬럼 + 정리/정렬 가속 인덱스 (FR-PIN-12-8, FR-PIN-12-23)
--  3) notifications WISH_CONVERTED 확장 — V009 visit_pin_id 선례 답습:
--     - chk_notifications_type CHECK 확장
--     - wish_pin_id (nullable) 컬럼 + 부분 UNIQUE 인덱스로 race-free 멱등 보장 (AC-12-10)
--  4) users.cleanup_snoozed_until — 오래된 핀 정리 배너 snooze 다기기 일관성 (D-11)
-- ============================================================

-- ─── 1) pin_events 테이블 ────────────────────────────────
CREATE TABLE pin_events (
    id         BIGSERIAL   PRIMARY KEY,
    pin_id     BIGINT      NOT NULL REFERENCES pins (id),
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    group_id   BIGINT      NOT NULL REFERENCES groups (id),
    action     VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_pin_events_action CHECK (action IN ('WANT'))
);

-- D-19 영구 멱등: 동일 (pin_id, user_id) 에 대해 WANT 1건만 허용.
-- 토글 취소는 row DELETE 로 처리하므로 partial UNIQUE 가 자연 멱등을 만든다.
CREATE UNIQUE INDEX uq_pin_events_pin_user_want
    ON pin_events (pin_id, user_id)
    WHERE action = 'WANT';

CREATE INDEX idx_pin_events_pin_id
    ON pin_events (pin_id);

CREATE INDEX idx_pin_events_group_created
    ON pin_events (group_id, created_at DESC);

COMMENT ON TABLE pin_events IS 'Phase 12: 핀 관심 표현(WANT) 이력. 후속 Phase 12.2에서 VIEW/SHARE/ROULETTE_SELECTED 확장.';
COMMENT ON COLUMN pin_events.action IS 'P0=WANT only. Phase 12.2 ALTER 예정 (D-17).';
COMMENT ON CONSTRAINT chk_pin_events_action ON pin_events IS 'P0=WANT only. Phase 12.2 ALTER 예정 (D-17).';

-- ─── 2) pins.want_count 컬럼 + 보조 인덱스 ───────────────
ALTER TABLE pins
    ADD COLUMN want_count INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN pins.want_count IS 'Phase 12: WANT 누적 카운트. WantService.toggle 이 pin_events 증감과 함께 단일 트랜잭션에서 갱신.';

-- 정리 대상 조회 가속 (FR-PIN-12-23):
-- chatbot AUTO 메모 REEL 핀 중 deleted_at 미설정 행만 (group_id, created_at) 으로 정렬/필터.
CREATE INDEX idx_pins_cleanup
    ON pins (group_id, created_at)
    WHERE tag = 'REEL'
      AND memo_source = 'AUTO'
      AND deleted_at IS NULL;

-- want_count DESC 정렬 가속 (FR-PIN-12-8):
-- ?sort=want_count 응답에서 페이지네이션 시 사용.
CREATE INDEX idx_pins_group_want_count
    ON pins (group_id, want_count DESC, created_at DESC)
    WHERE deleted_at IS NULL;

-- ─── 3) notifications WISH_CONVERTED 확장 ────────────────
-- V009 visit_pin_id 선례를 그대로 답습한다:
--   - 본 행에 타입 전용 nullable pin 컬럼을 두고
--   - 부분 UNIQUE 로 (group, receiver, registered_by, wish_pin_id) 1회 멱등을 강제하며
--   - NotificationPin 링크 테이블은 본 타입에서는 사용하지 않는다.

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_type;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_type
    CHECK (type IN ('MANUAL_PIN', 'CHATBOT_PINS', 'VISIT_DETECTED', 'WISH_CONVERTED'));

-- WISH_CONVERTED 전용 pin 참조 컬럼 (V009 visit_pin_id 와 동일한 정책)
--  - nullable: 기존 MANUAL_PIN/CHATBOT_PINS/VISIT_DETECTED 행은 NULL 유지.
--  - ON DELETE RESTRICT: pins 는 soft-delete 정책이라 평상시 영향 없음. hard DELETE 실수 차단.
ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS wish_pin_id BIGINT NULL
        REFERENCES pins (id) ON DELETE RESTRICT;

-- 멱등: 동일 (group_id, receiver_id, registered_by, wish_pin_id) 조합으로
-- WISH_CONVERTED 알림은 1회만 생성되도록 race-free 보장.
-- pin_id를 키에 포함해야만 "(triggerUser→receiver) 1회"가 아닌
-- "(triggerUser→receiver→해당 핀) 1회" 멱등이 되어 다른 핀 알림 누락이 발생하지 않는다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_wish_converted
    ON notifications (group_id, receiver_id, registered_by, wish_pin_id)
    WHERE type = 'WISH_CONVERTED' AND wish_pin_id IS NOT NULL;

COMMENT ON COLUMN notifications.wish_pin_id IS
    'Phase 12: WISH_CONVERTED 알림에서만 채워지는 핀 참조. 부분 UNIQUE 키.';

-- ─── 4) users.cleanup_snoozed_until ──────────────────────
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS cleanup_snoozed_until TIMESTAMPTZ NULL;

COMMENT ON COLUMN users.cleanup_snoozed_until IS
    'Phase 12: 오래된 핀 정리 배너 snooze 만료 시각. NULL = snooze 없음. 다기기 일관성을 위해 user 단위 영속화 (D-11).';
