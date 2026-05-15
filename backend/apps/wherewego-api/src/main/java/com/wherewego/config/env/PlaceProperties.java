package com.wherewego.config.env;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "place")
public record PlaceProperties(
        @Valid Instagram instagram,
        @Valid Search search,
        @Valid Scraper scraper
) {
    public record Instagram(
            boolean scrapingEnabled
    ) { }

    public record Search(
            @Positive long syncDeadlineMs,
            @Positive int kakaoLocalSize
    ) { }

    public record Scraper(
            @Valid InstagramScraper instagram
    ) { }

    public record InstagramScraper(
            @Positive int timeoutMs
    ) { }
}
