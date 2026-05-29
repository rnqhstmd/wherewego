-- ============================================================
-- V013__add_pins_photo.sql
-- Phase 13: 추억핀(MEMORY) 사진 1장 첨부.
--
-- DB 에는 S3 객체 키만 저장한다 (공개 URL 은 wherewego.s3.public-base-url 과 조합).
-- 사진은 MEMORY 핀 전용이나 컬럼 자체는 tag 무관 nullable — MEMORY 게이트는 서비스/프론트 책임.
--
-- NULL 허용: 기존 핀(사진 미첨부)은 4컬럼 모두 NULL. 하위 호환 (AC-2).
-- photo_uploaded_by 는 기존 created_by / memo_updated_by 컨벤션대로 컬럼만 둔다 (명시 FK 없음).
-- 인덱스 불필요 (LIST/조회 필터 미사용, BR-8).
-- ============================================================

ALTER TABLE pins
    ADD COLUMN IF NOT EXISTS photo_key TEXT NULL,
    ADD COLUMN IF NOT EXISTS photo_thumbnail_key TEXT NULL,
    ADD COLUMN IF NOT EXISTS photo_uploaded_by BIGINT NULL,
    ADD COLUMN IF NOT EXISTS photo_uploaded_at TIMESTAMPTZ NULL;

COMMENT ON COLUMN pins.photo_key IS
    'Phase 13: 원본 사진 S3 객체 키 (pins/{groupId}/{pinId}/{uuid}.jpg). NULL = 사진 없음.';
COMMENT ON COLUMN pins.photo_thumbnail_key IS
    'Phase 13: 썸네일 사진 S3 객체 키 (pins/{groupId}/{pinId}/{uuid}_thumb.webp). 원본과 uuid 공유.';
COMMENT ON COLUMN pins.photo_uploaded_by IS
    'Phase 13: 사진을 업로드한 사용자 id. created_by 컨벤션대로 컬럼만 둔다 (명시 FK 없음).';
COMMENT ON COLUMN pins.photo_uploaded_at IS
    'Phase 13: 사진 업로드(또는 교체) 시각. 삭제 시 NULL 로 초기화.';
