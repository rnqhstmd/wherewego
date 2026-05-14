package com.wherewego.config.env;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "google.places")
public record GooglePlacesProperties(
        @NotBlank String apiKey
) { }
