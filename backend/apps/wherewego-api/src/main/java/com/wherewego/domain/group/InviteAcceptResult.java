package com.wherewego.domain.group;

import java.time.Instant;

/**
 * 초대 수락 결과. 가입된 그룹 ID + 수락 시각(UTC).
 */
public record InviteAcceptResult(Long groupId, Instant acceptedAt) {
}
