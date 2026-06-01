-- ============================================================
-- V017__partial_unique_users_for_rejoin.sql
-- P2 PR-3: 계정 삭제 후 재가입(FR-24) 지원.
--
-- 정책: 계정 삭제는 soft-delete(deleted_at 마킹)이고 식별자(oauth_id/kakao_user_id)는 변경하지 않는다.
--       기존 두 UNIQUE 제약(uq_users_oauth, uq_users_kakao_user_id)은 전체 행 기준이라
--       soft-delete 행이 남아 있으면 동일 oauthId 재INSERT(재가입)가 UNIQUE 위반으로 실패한다.
--       → partial unique index(WHERE deleted_at IS NULL)로 전환해 soft-delete 행을 제약에서 제외한다.
--       활성 행(deleted_at IS NULL)은 여전히 1개만 강제되어 충돌 없음.
--
-- 제약 형태(코드 검증): 둘 다 CONSTRAINT 로 추가됨.
--   uq_users_kakao_user_id → V001:35 `CONSTRAINT uq_users_kakao_user_id UNIQUE (kakao_user_id)`
--   uq_users_oauth         → V014:23 `ALTER TABLE users ADD CONSTRAINT uq_users_oauth UNIQUE (oauth_provider, oauth_id)`
--   따라서 DROP CONSTRAINT 가 정확. (DROP INDEX 아님 — 제약은 시스템 인덱스를 동반하나 이름은 제약 기준으로 DROP CONSTRAINT 로 제거)
--
-- 조회 보정(필수, 코드): UserRepository.findByOauthProviderAndOauthId/findByKakaoUserId 를
--   ...AndDeletedAtIsNull(활성만)로 보정해야 삭제 행이 재로그인을 막지 않는다(미스→신규 생성).
--
-- 라이브 안전성: users 소규모(V014 주석). DROP CONSTRAINT + CREATE INDEX 짧은 락, 데이터 무변형 → 실질 additive.
--               대규모 환경이면 CREATE UNIQUE INDEX CONCURRENTLY 분리 검토.
-- 컨벤션: 기존 부분 UNIQUE(uq_group_members_active_user, uq_chat_room_bot_owner 등)와 동일한
--         `CREATE UNIQUE INDEX ... WHERE deleted_at IS NULL` 스타일을 따른다.
-- ============================================================

ALTER TABLE users DROP CONSTRAINT IF EXISTS uq_users_oauth;
ALTER TABLE users DROP CONSTRAINT IF EXISTS uq_users_kakao_user_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_oauth
    ON users (oauth_provider, oauth_id)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_kakao_user_id
    ON users (kakao_user_id)
    WHERE deleted_at IS NULL;
