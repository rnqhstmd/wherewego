package com.wherewego.domain.bot;

public record BotLinkCodeConsumeResult(
        Long userId,
        String code
) { }
