package com.wherewego.config.env;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "mapbox")
public record MapboxProperties(
        @NotBlank String token
) { }
