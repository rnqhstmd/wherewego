-- ============================================================
-- V010__add_pins_visited_at.sql
-- Phase 10 후속 보강: WISH/REEL → MEMORY 전환 시점 기록.
--
-- 위시 등록 일자(created_at)는 보존하고, 메모리 전환 일자는 visited_at에 별도 저장.
-- 핀 팝업의 "written by 좌측 날짜"는 MEMORY + visited_at 있으면 visited_at, 그 외 created_at.
--
-- NULL 허용: 기존 메모리 핀(과거 등록분)은 visited_at 없으므로 createdAt 폴백.
-- WISH/REEL은 항상 NULL.
-- ============================================================

ALTER TABLE pins
    ADD COLUMN IF NOT EXISTS visited_at TIMESTAMPTZ NULL;
