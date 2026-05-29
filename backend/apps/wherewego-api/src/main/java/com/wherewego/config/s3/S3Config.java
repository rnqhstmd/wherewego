package com.wherewego.config.s3;

import com.wherewego.config.env.S3Properties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;

/**
 * Phase 13: 추억핀 사진용 S3 클라이언트 빈.
 *
 * <p>자격증명은 {@link DefaultCredentialsProvider} 체인으로 해석한다 (운영 EC2 IAM Role /
 * 로컬 {@code .env}·AWS 프로필). 짧은 타임아웃(Q3)으로 비관 락 + 커넥션 장기 점유를 방지한다:
 * apiCallTimeout 5s, apiCallAttemptTimeout 3s, ApacheHttpClient connection 3s / socket 5s.</p>
 */
@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(S3Properties props) {
        return S3Client.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(c -> c
                        .apiCallTimeout(Duration.ofSeconds(5))
                        .apiCallAttemptTimeout(Duration.ofSeconds(3)))
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(5)))
                .build();
    }
}
