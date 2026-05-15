-- ============================================================
-- V002. bot_link_codes 스키마를 Phase 2 엔티티(status enum 기반)와 정합.
--    V001은 used_at NULLABLE 패턴이었으나 Phase 2에서 status enum(ACTIVE/CONSUMED/EXPIRED)으로 모델링.
--    기존 데이터 보존: used_at IS NULL → ACTIVE / 그 외 → CONSUMED 로 백필.
--    Partial UNIQUE INDEX 도 status 기반으로 재구성.
-- ============================================================

ALTER TABLE bot_link_codes
    ADD COLUMN IF NOT EXISTS status      VARCHAR(10),
    ADD COLUMN IF NOT EXISTS issued_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS consumed_at TIMESTAMPTZ;

-- 백필: used_at + expires_at 기준으로 status/issued_at/consumed_at 복원
--    used_at NOT NULL → CONSUMED
--    used_at NULL + expires_at < now → EXPIRED (Partial UNIQUE 충돌 회피)
--    그 외 → ACTIVE
-- TODO(Phase 후속): EXPIRED/CONSUMED 상태 코드 N일 경과 자동 삭제 배치 도입.
--   현재는 베타 규모(~100명)에서 누적 데이터가 미미하므로 미구현.
UPDATE bot_link_codes
SET status      = CASE
                      WHEN used_at IS NOT NULL THEN 'CONSUMED'
                      WHEN expires_at < now() THEN 'EXPIRED'
                      ELSE 'ACTIVE'
                  END,
    issued_at   = COALESCE(issued_at, created_at),
    consumed_at = CASE WHEN used_at IS NULL THEN NULL ELSE used_at END
WHERE status IS NULL;

ALTER TABLE bot_link_codes
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN issued_at SET NOT NULL;

ALTER TABLE bot_link_codes
    ADD CONSTRAINT chk_bot_link_codes_status
        CHECK (status IN ('ACTIVE', 'CONSUMED', 'EXPIRED'));

-- 기존 used_at 기반 Partial UNIQUE INDEX 제거 + status='ACTIVE' 기반으로 재생성
DROP INDEX IF EXISTS uq_bot_link_codes_active_user;
DROP INDEX IF EXISTS idx_bot_link_codes_code;

CREATE UNIQUE INDEX IF NOT EXISTS uq_bot_link_codes_active_user
    ON bot_link_codes (user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_bot_link_codes_active_code
    ON bot_link_codes (code)
    WHERE status = 'ACTIVE';
