-- Phase 7: PLACE → REEL/WISH/MEMORY 태그 리뉴얼 (단일 합본 배포)
-- 단일 Flyway 트랜잭션 안에서 다음 3 작업이 원자적으로 실행됨.
-- 부분 적용 시 Flyway가 전체 롤백.

-- 1) CHECK 제약 일시 확장: PLACE / REEL / WISH / MEMORY 모두 허용
ALTER TABLE pins DROP CONSTRAINT IF EXISTS chk_pins_tag;
ALTER TABLE pins ADD CONSTRAINT chk_pins_tag
    CHECK (tag IN ('PLACE', 'REEL', 'WISH', 'MEMORY'));

-- 2) 기존 PLACE 핀을 REEL로 일괄 변환 (~2,500건 예상, <1초)
UPDATE pins SET tag = 'REEL' WHERE tag = 'PLACE';

-- 3) CHECK 제약 최종 축소: REEL / WISH / MEMORY만 허용
ALTER TABLE pins DROP CONSTRAINT IF EXISTS chk_pins_tag;
ALTER TABLE pins ADD CONSTRAINT chk_pins_tag
    CHECK (tag IN ('REEL', 'WISH', 'MEMORY'));
