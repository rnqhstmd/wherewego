-- V011: invite_links.slug 컬럼 추가 (path-based 단축 URL).
-- nullable 로 추가하고 활성/유효 행에만 partial unique index 를 둔다.
-- 신규 발급은 InviteLink.issue 가 항상 slug 를 채우며,
-- 기존 미수락/미만료 행은 InviteLinkBackfillRunner 가 부팅 시 base56 8자로 채운다.
-- 만료/수락 완료/삭제된 행의 slug 는 NULL 로 두어도 무방하다 (조회 키로 사용되지 않음).

ALTER TABLE invite_links ADD COLUMN slug VARCHAR(16);

CREATE UNIQUE INDEX idx_invite_links_slug_active
    ON invite_links(slug)
    WHERE slug IS NOT NULL AND accepted_at IS NULL AND deleted_at IS NULL;

COMMENT ON COLUMN invite_links.slug IS 'base56 8자 단축 슬러그. /invite/{slug} 경로용. UUID token 과 1:1 매핑.';
