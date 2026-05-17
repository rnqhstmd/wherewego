package com.wherewego.config.env;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "kakao")
public record KakaoApiProperties(
        @NotBlank String localApiKey,
        @Valid OAuth oauth,
        @Valid Local local,
        @Valid Skill skill,
        @Valid Callback callback
) {
    public record OAuth(
            @NotBlank String clientId,
            @NotBlank String clientSecret,
            @NotBlank String redirectUri
    ) { }

    public record Local(
            @NotBlank String baseUrl,
            @Positive int timeoutMs
    ) { }

    public record Skill(
            @NotBlank String secret
    ) { }

    public record Callback(
            @Positive int timeoutMs
    ) { }
}
