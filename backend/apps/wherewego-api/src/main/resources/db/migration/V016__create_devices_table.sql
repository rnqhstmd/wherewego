-- ============================================================
-- V016__create_devices_table.sql
-- P2 PR-2: APNs 푸시 — devices.
--
-- 디바이스 토큰 등록 테이블(FR-15). platform 은 현재 IOS 만(APNs). device_token 은 APNs 디바이스 토큰.
-- DeviceService.upsert(userId, platform, token): (user_id, device_token) 활성 UNIQUE, 존재 시 updated_at 갱신(AC-7).
-- BR-9: 동일 token 이 다른 userId 로 재등록되면 reassign — device_token 단독 조회(idx_devices_token)로 기존 행 탐색.
-- APNs 거부(BadDeviceToken/Unregistered/410) 시 토큰 정리(FR-19, AC-9), 계정 삭제 시 user 별 정리(FR-21).
--
-- 부분 UNIQUE: BaseEntity 가 soft delete(deleted_at) 를 가지므로 활성 행만 UNIQUE 로 강제한다(WHERE deleted_at IS NULL).
--   재등록 시나리오(soft delete 후 동일 user_id/token 재발급) 대비 — chat_room(V015) 부분 UNIQUE 컨벤션과 일관.
--
-- 컨벤션: V001 = BIGSERIAL PK + TIMESTAMPTZ NOT NULL DEFAULT now() + deleted_at. BaseEntity IDENTITY(BIGSERIAL).
-- ============================================================

CREATE TABLE IF NOT EXISTS devices
(
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    platform     VARCHAR(20)  NOT NULL,
    device_token VARCHAR(500) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ
);

-- 활성 디바이스: (user_id, device_token) 당 1개(AC-7). deleted_at IS NULL 부분 UNIQUE 로 재등록 대비.
CREATE UNIQUE INDEX IF NOT EXISTS uq_devices_user_token
    ON devices (user_id, device_token)
    WHERE deleted_at IS NULL;

-- 사용자별 활성 디바이스 fan-out 조회(FR-20)/계정 삭제 정리(FR-21).
CREATE INDEX IF NOT EXISTS idx_devices_user_id
    ON devices (user_id);

-- 토큰 단독 조회: BR-9 reassign(다른 userId 재등록 탐색) + FR-19 거부 토큰 정리.
CREATE INDEX IF NOT EXISTS idx_devices_token
    ON devices (device_token);
