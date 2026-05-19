-- 인스타 1개 캡션에서 N개 장소를 추출 → 같은 URL로 여러 핀 등록 허용.
-- UNIQUE 키를 (group_id, instagram_url) → (group_id, instagram_url, place_name)으로 완화.
-- 동일 (group_id, URL, 장소명) 조합은 여전히 중복 차단 (idempotent 보장).

ALTER TABLE pins DROP CONSTRAINT IF EXISTS uq_pins_group_instagram;

ALTER TABLE pins
    ADD CONSTRAINT uq_pins_group_instagram_place
    UNIQUE (group_id, instagram_url, place_name);
