package com.wherewego.domain.bot;

import java.time.Instant;

public record BotUserLinkResult(
        Long userId,
        String botUserKey,
        Instant linkedAt
) { }
