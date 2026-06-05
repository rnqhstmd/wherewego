package com.wherewego.domain.group;

import java.time.Instant;
import java.util.Optional;

public interface InviteLinkRepository {

    InviteLink save(InviteLink link);

    Optional<InviteLink> findByToken(String token);

    /**
     * slug 로 활성(미수락 + 미만료) 초대 링크 조회. 만료/소진 시 empty.
     * 공개 GET by-slug API 의 진입점.
     */
    Optional<InviteLink> findActiveBySlug(String slug, Instant now);

    /**
     * 동일 그룹의 미수락(accepted_at IS NULL) + 미만료(expires_at > now) 토큰을 즉시 만료한다(expires_at = now).
     * 재발급 시 BR-3 적용. 반환값은 갱신된 행 수.
     */
    int expirePendingByGroupId(Long groupId, Instant now);

    /**
     * 토큰 1회용 보장(동시성). accepted_at IS NULL 인 경우에만 수락 시각을 원자적으로 기록한다.
     * 갱신 행 수를 반환하며, 동시 수락 시 1건만 1, 나머지는 0 을 받는다.
     */
    int markAcceptedIfPending(Long id, Instant now);
}
