-- ============================================================
-- V014__generalize_oauth_provider.sql
-- P1: 인증 계정 모델 일반화 (Kakao 단일 → OAuth provider + oauth_id).
--
-- additive: kakao_user_id 컬럼·UNIQUE(uq_users_kakao_user_id) 무손실 유지(BR-10).
--           Apple 은 kakao_user_id 없음 → DROP NOT NULL.
-- 백필: 전 행 (KAKAO, kakao_user_id::text)(AC-19). email nullable(Apple 최초만).
--
-- ⚠ 운영 주의: UPDATE + SET NOT NULL 은 풀스캔/락 가능. 현재 users 소규모라 수용.
--             대규모면 배치 분리 검토.
-- ============================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS oauth_provider VARCHAR(20) NOT NULL DEFAULT 'KAKAO',
    ADD COLUMN IF NOT EXISTS oauth_id       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS email          VARCHAR(255);

UPDATE users SET oauth_id = kakao_user_id::text WHERE oauth_id IS NULL;

ALTER TABLE users ALTER COLUMN oauth_id SET NOT NULL;
ALTER TABLE users ALTER COLUMN kakao_user_id DROP NOT NULL;

ALTER TABLE users ADD CONSTRAINT uq_users_oauth UNIQUE (oauth_provider, oauth_id);

COMMENT ON COLUMN users.oauth_provider IS 'P1: OAuth 공급자(KAKAO/APPLE). 기존 백필 KAKAO.';
COMMENT ON COLUMN users.oauth_id IS 'P1: 공급자별 식별자. Kakao=kakao_user_id::text, Apple=sub.';
COMMENT ON COLUMN users.email IS 'P1: Apple 최초 로그인 1회 저장. Kakao 미수집(NULL).';
