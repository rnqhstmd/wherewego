package com.wherewego.interfaces.api.bot;

import com.wherewego.domain.bot.BotLinkCodeIssueResult;

import java.time.Instant;

public class BotV1Dto {

    public record LinkCodeResponse(String code, Instant expiresAt) {
        public static LinkCodeResponse from(BotLinkCodeIssueResult result) {
            return new LinkCodeResponse(result.code(), result.expiresAt());
        }
    }

    private BotV1Dto() { }
}
