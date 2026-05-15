package com.wherewego.config.env;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "bot")
public record BotProperties(
        @Valid LinkCode linkCode
) {
    public record LinkCode(
            @Positive int ttlMinutes,
            @Positive int maxGenerationRetries
    ) { }
}
