-- ============================================================
-- V004__add_group_members_deleted_at.sql
-- Phase 3: group_members 테이블에 deleted_at 컬럼 추가
-- 대상 DB: PostgreSQL 17
--
-- 목적: GroupMember 엔티티가 BaseEntity 상속으로 deleted_at 컬럼을 매핑함.
--      V001 group_members 정의에 deleted_at 이 누락되어 있어 INSERT 시 SQL 오류 발생.
--      Phase 2 read-only 사용 시점에는 노출되지 않았으나, Phase 3 그룹 생성/탈퇴 INSERT/UPDATE 경로에서 필요.
--
-- 재실행 정책: ADD COLUMN IF NOT EXISTS 멱등.
-- 데이터 영향: 신규 컬럼이라 데이터 영향 없음.
-- ============================================================

ALTER TABLE group_members ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
