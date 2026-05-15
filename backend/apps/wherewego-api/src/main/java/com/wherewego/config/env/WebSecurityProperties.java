package com.wherewego.config.env;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "web-security")
public record WebSecurityProperties(
        @Valid @DefaultValue Cookie cookie,
        @Valid @DefaultValue Cors cors
) {
    public record Cookie(
            @DefaultValue("true") boolean secure,
            @DefaultValue("") String domain,
            @NotBlank @DefaultValue("None") String sameSite
    ) { }

    public record Cors(
            @NotEmpty List<@NotBlank String> allowedOrigins
    ) { }
}
