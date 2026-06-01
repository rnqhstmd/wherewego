package com.wherewego.config.env;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
            @NotBlank String redirectUri,
            // P1: 우리 카카오 앱 ID. 네이티브 로그인 access_token_info 의 app_id 와 대조해 타앱 토큰 오용을 차단한다.
            @NotNull Long appId
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
