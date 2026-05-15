package com.wherewego.domain.bot;

import java.time.Instant;

public record BotLinkCodeIssueResult(
        String code,
        Instant expiresAt,
        Long userId
) { }
