package com.wherewego.config.env;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Phase 13: 추억핀 사진 S3 스토리지 설정. {@code wherewego.s3.*} 바인딩.
 * <p>{@code WherewegoApiApplication} 의 {@code @ConfigurationPropertiesScan} 으로 자동 등록된다.</p>
 */
@Validated
@ConfigurationProperties(prefix = "wherewego.s3")
public record S3Properties(
        @NotBlank String bucket,
        @NotBlank String region,
        @NotBlank String publicBaseUrl
) { }
