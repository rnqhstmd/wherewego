-- ============================================================
-- V001__init_schema.sql
-- Phase 0: 도메인 테이블 전체 초기 생성
-- 대상 DB: PostgreSQL 17 (Supabase wherewego-dev/wherewego-prod, local postgres:17-alpine)
-- 최소 PostgreSQL 버전: 12
--
-- 재실행 정책: Flyway Community Edition 자동 rollback 미지원.
--             신규 환경 가정: 빈 DB 또는 baseline-on-migrate 처리 가능 상태.
--             local 실패 시 `./gradlew flywayClean flywayMigrate` 또는 docker volume 재생성.
-- 데이터 백업: V001 신규 생성, 백업 불필요. V002+ 파괴적 변경 시 본 주석에 백업 절차.
--
-- ID 전략 노트: BIGSERIAL 사용. Phase 1 도메인 엔티티 매핑 시 BaseEntity의
--   GenerationType.IDENTITY가 BIGSERIAL의 sequence를 정상 사용함을 검증.
--
-- RLS 정책: Phase 0은 GRANT/RLS POLICY 미포함. Spring 서버에서 인증/인가 처리.
-- ============================================================


-- ============================================================
-- 1. users
--    카카오 OAuth2 로그인 유저.
--    JWT refresh_token 을 컬럼으로 직접 보관 (단일 Provider, oauth_tokens 테이블 불필요).
--    챗봇 botUserKey 매핑은 bot_user_mappings 테이블이 별도 관리.
-- ============================================================
CREATE TABLE IF NOT EXISTS users
(
    id                BIGSERIAL    PRIMARY KEY,
    kakao_user_id     BIGINT       NOT NULL,
    nickname          VARCHAR(100) NOT NULL,
    profile_image_url TEXT,
    refresh_token     TEXT,                              -- JWT Refresh Token의 SHA-256 해시 hex (14일 TTL)
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT uq_users_kakao_user_id UNIQUE (kakao_user_id)
);


-- ============================================================
-- 2. groups
--    핀/메모/태그를 공유하는 사용자 묶음.
--    MVP: 2인 커플. 스키마 레벨에서 N인 확장 가능 구조.
--    그룹 해체 개념 없음 — group_members.left_at 으로 탈퇴 표현.
-- ============================================================
CREATE TABLE IF NOT EXISTS groups
(
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);


-- ============================================================
-- 3. group_members
--    User ↔ Group N:M 조인 테이블.
--    탈퇴: left_at 타임스탬프 soft delete (행 삭제 없음).
--    탈퇴한 유저가 등록한 핀은 그룹에 잔류 (추억의 맥락 보존).
--    비즈니스 제약 (MVP): 한 유저는 활성 그룹 1개만.
--    DB 레벨 강제: Partial UNIQUE INDEX (left_at IS NULL).
-- ============================================================
CREATE TABLE IF NOT EXISTS group_members
(
    id         BIGSERIAL   PRIMARY KEY,
    group_id   BIGINT      NOT NULL REFERENCES groups (id),
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at    TIMESTAMPTZ,                              -- NULL = 활성, NOT NULL = 탈퇴
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_group_members_pair UNIQUE (group_id, user_id)
);

-- 활성 멤버 중복 방지: 한 유저가 동시에 2개 이상 활성 그룹에 속할 수 없음
CREATE UNIQUE INDEX IF NOT EXISTS uq_group_members_active_user
    ON group_members (user_id)
    WHERE left_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_group_members_group_id
    ON group_members (group_id);


-- ============================================================
-- 4. invite_links
--    UUID 기반 단방향 초대 토큰. TTL 24h.
--    그룹이 먼저 생성된 후 초대 링크 발급 → chicken-egg 없음.
--    수락 시 group_members 행 추가 후 accepted_at 기록.
--    재발급 시 기존 미수락 토큰은 서비스 레이어에서 만료 처리.
-- ============================================================
CREATE TABLE IF NOT EXISTS invite_links
(
    id          BIGSERIAL    PRIMARY KEY,
    group_id    BIGINT       NOT NULL REFERENCES groups (id),
    inviter_id  BIGINT       NOT NULL REFERENCES users (id),
    token       VARCHAR(100) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,                   -- 발급 + 24시간
    accepted_at TIMESTAMPTZ,                             -- NULL = 미수락
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_invite_links_token UNIQUE (token)
);

CREATE INDEX IF NOT EXISTS idx_invite_links_group_id
    ON invite_links (group_id);


-- ============================================================
-- 5. bot_link_codes
--    웹에서 발급하는 6자리 숫자 연동 코드. TTL 10분.
--    챗봇이 코드 수신 → 조회 → 유효 시 bot_user_mappings 에 영구 매핑.
--    Partial UNIQUE INDEX: 활성(미사용) 코드는 유저당 1개.
-- ============================================================
CREATE TABLE IF NOT EXISTS bot_link_codes
(
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    code       CHAR(6)     NOT NULL,                     -- 6자리 숫자 코드
    expires_at TIMESTAMPTZ NOT NULL,                     -- 발급 + 10분
    used_at    TIMESTAMPTZ,                              -- NULL = 미사용
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 활성(미사용) 코드는 유저당 1개
CREATE UNIQUE INDEX IF NOT EXISTS uq_bot_link_codes_active_user
    ON bot_link_codes (user_id)
    WHERE used_at IS NULL;

-- 챗봇 수신 코드 → 유저 조회용
CREATE INDEX IF NOT EXISTS idx_bot_link_codes_code
    ON bot_link_codes (code)
    WHERE used_at IS NULL;


-- ============================================================
-- 6. bot_user_mappings
--    챗봇 연동 완료 후 botUserKey ↔ user_id 영구 매핑.
--    auth 도메인은 user_id 식별까지만 책임. 이 테이블은 chatbot 도메인 소유.
--    한 유저는 하나의 봇 계정만 연동 가능 (user_id UNIQUE 강제).
-- ============================================================
CREATE TABLE IF NOT EXISTS bot_user_mappings
(
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users (id),
    bot_user_key VARCHAR(100) NOT NULL,
    linked_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_bot_user_mappings_user    UNIQUE (user_id),
    CONSTRAINT uq_bot_user_mappings_bot_key UNIQUE (bot_user_key)
);


-- ============================================================
-- 7. pins
--    그룹 공유 장소 핀. 핵심 컬럼.
--    visited 기능 제거 — tag(PLACE/MEMORY)로 핀 의미 구분.
--    탈퇴 후 핀은 그룹에 잔류 (group_id NOT NULL 유지).
--    memo_source: AUTO(챗봇 2초 룰) / MANUAL(웹 수동). 수동 우선 정책.
--    거리 계산: Haversine 애플리케이션 레벨. PostGIS 미사용.
--    UNIQUE(group_id, instagram_url): PostgreSQL 표준 동작으로 NULL을 distinct 취급.
--    → instagram_url이 NULL인 행(직접 등록)은 중복 허용, 비NULL인 경우만 중복 차단.
-- ============================================================
CREATE TABLE IF NOT EXISTS pins
(
    id            BIGSERIAL      PRIMARY KEY,
    group_id      BIGINT         NOT NULL REFERENCES groups (id),
    created_by    BIGINT         NOT NULL REFERENCES users (id),
    place_name    VARCHAR(200)   NOT NULL,
    address       TEXT,
    latitude      DECIMAL(10, 7) NOT NULL,
    longitude     DECIMAL(10, 7) NOT NULL,
    instagram_url TEXT,                                  -- NULLABLE (직접 등록 시 없을 수 있음)
    memo          TEXT,
    memo_source   VARCHAR(10),                           -- AUTO | MANUAL | NULL(메모 없음)
    tag           VARCHAR(10)    NOT NULL,               -- PLACE | MEMORY
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ,
    CONSTRAINT chk_pins_tag         CHECK (tag IN ('PLACE', 'MEMORY')),
    CONSTRAINT chk_pins_memo_source CHECK (memo_source IN ('AUTO', 'MANUAL')),
    -- 챗봇 중복 방지: 같은 그룹 내 동일 instagram_url (NULL 제외)
    CONSTRAINT uq_pins_group_instagram UNIQUE (group_id, instagram_url)
);

-- 기본 그룹 조회 (삭제 여부 포함)
CREATE INDEX IF NOT EXISTS idx_pins_group_id_deleted_at
    ON pins (group_id, deleted_at);

-- 지도 렌더링: 그룹별 + 태그별 필터
CREATE INDEX IF NOT EXISTS idx_pins_group_tag
    ON pins (group_id, tag)
    WHERE deleted_at IS NULL;

-- 룰렛 Bounding Box 1차 필터 (Haversine 전 범위 축소)
CREATE INDEX IF NOT EXISTS idx_pins_group_location
    ON pins (group_id, latitude, longitude)
    WHERE deleted_at IS NULL;
