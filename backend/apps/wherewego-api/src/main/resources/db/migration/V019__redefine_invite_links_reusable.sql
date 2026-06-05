-- IC-1: 1회용→정원 재사용. accepted_at(소진 판정) 제거. 가입=group_members 판정(D2).
--   정원 도달은 '만료'가 아니라 '가입 차단'(count>=10)이라 코드는 TTL까지 유지
--   → by-slug 정원초과(GROUP_CAPACITY_EXCEEDED) 구분 정합.
-- index: V011 의 idx_invite_links_slug_active(accepted_at IS NULL 포함)를 DROP 후
--        slug IS NOT NULL AND deleted_at IS NULL 로 재정의(accepted_at 컬럼 제거 동반).
--        → V011 파일의 'accepted_at IS NULL' index 조건은 이 V019 가 대체한다(V011 미수정).
-- 적용 전 GATE: 동일 non-null slug 중복 0건 확인.
--   SELECT slug, count(*) FROM invite_links
--    WHERE slug IS NOT NULL AND deleted_at IS NULL GROUP BY slug HAVING count(*) > 1;
--   (0건이어야 적용 가능. 1건+ 이면 index 생성 실패 → 보류·수동정리.)
-- 라이브: DROP COLUMN/INDEX 짧은 락. 대규모면 CREATE INDEX CONCURRENTLY 검토.
-- 롤백(수동 — Flyway Community 자동롤백 없음):
--   1) ALTER TABLE invite_links ADD COLUMN accepted_at TIMESTAMPTZ;
--   2) DROP INDEX idx_invite_links_slug_active;
--   3) 구 index(accepted_at IS NULL 포함) 재생성.
--   ⚠️ 비가역: accepted_at 값 복원 불가(전부 NULL). V019 적용 후 재사용 가입이 1건이라도 발생하면,
--      롤백한 구 코드가 accepted_at IS NULL 을 '미수락'으로 오판하므로 사실상 롤백 불가 → 적용 전 스냅샷 필수.
DROP INDEX IF EXISTS idx_invite_links_slug_active;
ALTER TABLE invite_links DROP COLUMN IF EXISTS accepted_at;
CREATE UNIQUE INDEX idx_invite_links_slug_active
    ON invite_links(slug)
    WHERE slug IS NOT NULL AND deleted_at IS NULL;
