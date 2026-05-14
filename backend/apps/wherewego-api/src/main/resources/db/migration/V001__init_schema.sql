-- ============================================================
-- V001__init_schema.sql
-- Phase 0: 도메인 테이블 5종 초기 생성
-- 대상 DB: PostgreSQL 17 (Supabase wherewego-dev/wherewego-prod, local postgres:17-alpine)
-- 최소 PostgreSQL 버전: 15 (UNIQUE NULLS NOT DISTINCT 문법 필요)
--
-- 재실행 정책: Flyway Community Edition 자동 rollback 미지원.
--             신규 환경 가정: 빈 DB 또는 baseline-on-migrate 처리 가능 상태.
--             local 실패 시 `./gradlew flywayClean flywayMigrate` 또는 docker volume 재생성.
-- 데이터 백업: V001 신규 생성, 백업 불필요. V002+ 파괴적 변경 시 본 주석에 백업 절차.
--
-- ID 전략 노트: PostgreSQL 10+ 권장은 GENERATED ALWAYS AS IDENTITY이지만 Phase 0은
--   BIGSERIAL 유지. Phase 1 도메인 엔티티 매핑 시 BaseEntity의 GenerationType.IDENTITY가
--   BIGSERIAL의 sequence를 정상 사용함을 검증.
--
-- RLS 정책: Phase 0은 GRANT/RLS POLICY 미포함. Supabase 사용 시 RLS 기본 활성화 가능하나
--   본 서비스는 Spring 서버 인증/인가 처리. Phase 1 진입 전 Supabase RLS 사용 여부 결정.
-- ============================================================

CREATE TABLE IF NOT EXISTS users (
    id                BIGSERIAL PRIMARY KEY,
    kakao_user_id     BIGINT NOT NULL,
    nickname          VARCHAR(50) NOT NULL,
    profile_image_url TEXT,
    refresh_token     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT uq_users_kakao_user_id UNIQUE (kakao_user_id)
);

CREATE TABLE IF NOT EXISTS groups (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS group_members (
    id         BIGSERIAL PRIMARY KEY,
    group_id   BIGINT NOT NULL REFERENCES groups(id),
    user_id    BIGINT NOT NULL REFERENCES users(id),
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_group_members_user_id_left_at ON group_members(user_id, left_at);
CREATE INDEX IF NOT EXISTS idx_group_members_group_id ON group_members(group_id);

CREATE TABLE IF NOT EXISTS pins (
    id            BIGSERIAL PRIMARY KEY,
    group_id      BIGINT NOT NULL REFERENCES groups(id),
    place_name    VARCHAR(200) NOT NULL,
    address       TEXT,
    latitude      DECIMAL(10, 7) NOT NULL,
    longitude     DECIMAL(10, 7) NOT NULL,
    instagram_url TEXT,
    tag           VARCHAR(10) NOT NULL,
    memo          TEXT,
    memo_source   VARCHAR(10),
    created_by    BIGINT NOT NULL REFERENCES users(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ,
    CONSTRAINT chk_pins_tag CHECK (tag IN ('PLACE', 'MEMORY')),
    CONSTRAINT chk_pins_memo_source CHECK (memo_source IN ('AUTO', 'MANUAL')),
    CONSTRAINT uq_pins_group_instagram UNIQUE NULLS NOT DISTINCT (group_id, instagram_url)
);

CREATE INDEX IF NOT EXISTS idx_pins_group_id_deleted_at ON pins(group_id, deleted_at);

CREATE TABLE IF NOT EXISTS bot_user_mappings (
    id           BIGSERIAL PRIMARY KEY,
    bot_user_key VARCHAR(100) NOT NULL,
    user_id      BIGINT NOT NULL REFERENCES users(id),
    linked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_bot_user_mappings_key UNIQUE (bot_user_key)
);
