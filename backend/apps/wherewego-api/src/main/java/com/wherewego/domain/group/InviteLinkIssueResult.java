package com.wherewego.domain.group;

import java.time.Instant;

/**
 * 초대 링크 발급 결과. token + slug + 만료 시각(UTC).
 * shareUrl 은 인터페이스 계층에서 InviteProperties.shareBaseUrl 과 결합하여 생성한다.
 */
public record InviteLinkIssueResult(String token, String slug, Instant expiresAt) {
}
