-- ============================================================
-- V022__group_image_and_user_avatar.sql
-- GP-1: 그룹 대표 이미지 + 사용자 프로필 사진 업로드.
--
-- 핀 사진(V013)과 동일하게 DB 에는 S3 객체 키만 저장한다
-- (공개 URL 은 wherewego.s3.public-base-url 과 조합 — PinService.toPublicUrl).
--
-- NULL 허용: 기존 그룹/사용자(이미지 미지정)는 신규 4컬럼 모두 NULL. 하위 호환.
-- users.profile_image_url(카카오 URL, 기존)은 보존한다 — 업로드 키가 없을 때의 폴백(BR/AC-8).
-- 인덱스 불필요 (조회 필터/정렬 미사용).
-- ============================================================

ALTER TABLE groups
    ADD COLUMN IF NOT EXISTS image_key VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS image_thumb_key VARCHAR(255) NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS profile_image_key VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS profile_image_thumb_key VARCHAR(255) NULL;

COMMENT ON COLUMN groups.image_key IS
    'GP-1: 그룹 대표 이미지 원본 S3 객체 키 (groups/{groupId}/avatar/{uuid}.jpg). NULL = 이미지 없음.';
COMMENT ON COLUMN groups.image_thumb_key IS
    'GP-1: 그룹 대표 이미지 썸네일 S3 객체 키 (groups/{groupId}/avatar/{uuid}_thumb.webp). 원본과 uuid 공유.';
COMMENT ON COLUMN users.profile_image_key IS
    'GP-1: 사용자 프로필 사진 원본 S3 객체 키 (users/{userId}/avatar/{uuid}.jpg). NULL = 업로드 사진 없음(profile_image_url 폴백).';
COMMENT ON COLUMN users.profile_image_thumb_key IS
    'GP-1: 사용자 프로필 사진 썸네일 S3 객체 키 (users/{userId}/avatar/{uuid}_thumb.webp). 유효 프사 URL 의 1순위 소스.';
