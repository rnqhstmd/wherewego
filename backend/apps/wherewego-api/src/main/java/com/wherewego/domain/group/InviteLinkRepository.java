package com.wherewego.domain.group;

import java.time.Instant;
import java.util.Optional;

public interface InviteLinkRepository {

    InviteLink save(InviteLink link);

    Optional<InviteLink> findByToken(String token);

    /**
     * slug 로 활성(미만료) 초대 링크 조회. 만료(expires_at <= now) 시 empty.
     * IC-1: 재사용 모델에서 코드는 정원 도달과 무관하게 TTL 까지 유효하므로 만료 판정은 expires_at 단일이다.
     * 공개 GET by-slug API 의 진입점.
     */
    Optional<InviteLink> findActiveBySlug(String slug, Instant now);

    /**
     * 그룹의 현재 활성(미만료, expires_at > now) 초대 링크 조회(IC-2 후속). 없으면 empty.
     * 발급(issue)과 달리 새 코드를 만들지 않는 읽기 전용 조회 — '코드 항상 표시' UX 용.
     */
    Optional<InviteLink> findActiveByGroupId(Long groupId, Instant now);

    /**
     * 동일 그룹의 미만료(expires_at > now) 활성 토큰을 즉시 만료한다(expires_at = now).
     * IC-1: 재발급(BR-3)·탈퇴(BR-5) 전용. 정원 도달은 만료가 아니라 가입 차단이므로 정원 후처리에서 호출하지 않는다.
     * 반환값은 갱신된 행 수.
     */
    int expirePendingByGroupId(Long groupId, Instant now);

    /**
     * slug 가 unique 제약 범위(slug IS NOT NULL AND deleted_at IS NULL — V019
     * idx_invite_links_slug_active)에서 이미 사용 중인지 검사한다(PR #118 리뷰 반영).
     * 발급 시 slug 충돌 사전 검사용 — 만료(expires_at) 여부와 무관하게 인덱스 술어와 정확히 일치해야 한다
     * ({@link #findActiveBySlug}는 만료 필터가 있어 이 용도에 부적합).
     */
    boolean existsActiveSlug(String slug);
}
