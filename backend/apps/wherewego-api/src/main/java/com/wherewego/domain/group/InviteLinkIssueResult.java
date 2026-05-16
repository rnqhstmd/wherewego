package com.wherewego.domain.group;

import java.time.Instant;

/**
 * 초대 링크 발급 결과. token + 만료 시각(UTC).
 */
public record InviteLinkIssueResult(String token, Instant expiresAt) {
}
