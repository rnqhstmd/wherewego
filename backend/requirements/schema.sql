-- ============================================================
-- 우리가갈지도 (MayGo) — ERD 레퍼런스 문서
-- 실제 마이그레이션: apps/wherewego-api/src/main/resources/db/migration/V001__init_schema.sql
-- 이 파일은 V001 과 항상 동기화된 상태를 유지한다.
-- ============================================================

-- 1. users
--    JWT refresh_token 직접 보관. botUserKey 매핑은 bot_user_mappings 테이블.
CREATE TABLE users (
    id                BIGSERIAL    PRIMARY KEY,
    kakao_user_id     BIGINT       NOT NULL,
    nickname          VARCHAR(100) NOT NULL,
    profile_image_url TEXT,
    refresh_token     TEXT,                        -- JWT Refresh Token (14일 TTL)
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT uq_users_kakao_user_id UNIQUE (kakao_user_id)
);

-- 2. groups
--    N인 그룹. MVP = 2인 커플.
CREATE TABLE groups (
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

-- 3. group_members
--    탈퇴: left_at soft delete. 활성 유저 1그룹 제약은 Partial UNIQUE INDEX.
CREATE TABLE group_members (
    id         BIGSERIAL   PRIMARY KEY,
    group_id   BIGINT      NOT NULL REFERENCES groups(id),
    user_id    BIGINT      NOT NULL REFERENCES users(id),
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_group_members_pair UNIQUE (group_id, user_id)
);
-- 활성 유저 1그룹 DB 레벨 강제
CREATE UNIQUE INDEX uq_group_members_active_user ON group_members(user_id) WHERE left_at IS NULL;
CREATE INDEX idx_group_members_group_id ON group_members(group_id);

-- 4. invite_links
--    UUID 토큰. TTL 24h. 수락 시 group_members 추가 + accepted_at 기록.
CREATE TABLE invite_links (
    id          BIGSERIAL    PRIMARY KEY,
    group_id    BIGINT       NOT NULL REFERENCES groups(id),
    inviter_id  BIGINT       NOT NULL REFERENCES users(id),
    token       VARCHAR(100) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    accepted_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_invite_links_token UNIQUE (token)
);
CREATE INDEX idx_invite_links_group_id ON invite_links(group_id);

-- 5. bot_link_codes
--    6자리 숫자 연동 코드. TTL 10분. 활성 코드 유저당 1개.
CREATE TABLE bot_link_codes (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id),
    code       CHAR(6)     NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_bot_link_codes_active_user ON bot_link_codes(user_id) WHERE used_at IS NULL;
CREATE INDEX idx_bot_link_codes_code ON bot_link_codes(code) WHERE used_at IS NULL;

-- 6. bot_user_mappings
--    botUserKey ↔ user_id 영구 매핑. 유저당 1개 봇 계정.
CREATE TABLE bot_user_mappings (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users(id),
    bot_user_key VARCHAR(100) NOT NULL,
    linked_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_bot_user_mappings_user    UNIQUE (user_id),
    CONSTRAINT uq_bot_user_mappings_bot_key UNIQUE (bot_user_key)
);

-- 7. pins
--    visited 제거. tag(PLACE/MEMORY)로 핀 의미 구분. Haversine 앱 레벨 거리 계산.
CREATE TABLE pins (
    id            BIGSERIAL      PRIMARY KEY,
    group_id      BIGINT         NOT NULL REFERENCES groups(id),
    created_by    BIGINT         NOT NULL REFERENCES users(id),
    place_name    VARCHAR(200)   NOT NULL,
    address       TEXT,
    latitude      DECIMAL(10, 7) NOT NULL,
    longitude     DECIMAL(10, 7) NOT NULL,
    instagram_url TEXT,
    memo          TEXT,
    memo_source   VARCHAR(10),                    -- AUTO | MANUAL | NULL
    tag           VARCHAR(10)    NOT NULL,        -- PLACE | MEMORY
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ,
    CONSTRAINT chk_pins_tag         CHECK (tag IN ('PLACE', 'MEMORY')),
    CONSTRAINT chk_pins_memo_source CHECK (memo_source IN ('AUTO', 'MANUAL')),
    CONSTRAINT uq_pins_group_instagram UNIQUE (group_id, instagram_url)
);
CREATE INDEX idx_pins_group_id_deleted_at ON pins(group_id, deleted_at);
CREATE INDEX idx_pins_group_tag      ON pins(group_id, tag)                    WHERE deleted_at IS NULL;
CREATE INDEX idx_pins_group_location ON pins(group_id, latitude, longitude)    WHERE deleted_at IS NULL;
