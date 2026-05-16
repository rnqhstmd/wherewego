package com.wherewego.domain.group;

import java.time.Instant;
import java.util.Optional;

public interface InviteLinkRepository {

    InviteLink save(InviteLink link);

    Optional<InviteLink> findByToken(String token);

    /**
     * 동일 그룹의 미수락(accepted_at IS NULL) + 미만료(expires_at > now) 토큰을 즉시 만료한다(expires_at = now).
     * 재발급 시 BR-3 적용. 반환값은 갱신된 행 수.
     */
    int expirePendingByGroupId(Long groupId, Instant now);
}
