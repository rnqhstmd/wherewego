-- ============================================================
-- V003. invite_links 테이블에 deleted_at 컬럼 추가.
--    Phase 3 InviteLink 엔티티가 BaseEntity (deleted_at 포함) 를 상속하도록 일관화.
--    Hibernate SchemaValidator 가 매핑된 deleted_at 컬럼을 요구하므로 부트 실패 방지.
--    향후 초대 토큰 회수(소프트 삭제) 시나리오 대비 — 현재는 매핑 정합성만 확보.
--
-- 대상 DB: PostgreSQL 17 (Supabase wherewego-dev/wherewego-prod, local postgres:17-alpine)
-- 데이터 백업: 신규 컬럼 추가만 수행, 기존 데이터에 영향 없음. 백업 불필요.
-- 멱등성: ADD COLUMN IF NOT EXISTS 사용 — 재실행 안전.
-- ============================================================

ALTER TABLE invite_links
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
