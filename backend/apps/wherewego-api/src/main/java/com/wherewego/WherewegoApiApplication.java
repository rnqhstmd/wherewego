package com.wherewego;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.Ordered;
import org.springframework.retry.annotation.EnableRetry;
import java.util.TimeZone;

/**
 * {@code @EnableRetry(order = LOWEST_PRECEDENCE - 1)}:
 * Retry 인터셉터를 TX 인터셉터보다 outer 로 두어 TX 가 던지는
 * {@code CannotCreateTransactionException} 을 {@code @Retryable} 이 잡을 수 있게 한다.
 * (Spring Retry 1.3+ 에서 추가된 order 속성을 사용 — 별도 RetryConfiguration 상속 불필요.)
 */
@EnableRetry(order = Ordered.LOWEST_PRECEDENCE - 1)
@ConfigurationPropertiesScan
@SpringBootApplication
public class WherewegoApiApplication {

    @PostConstruct
    public void started() {
        // set timezone
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    public static void main(String[] args) {
        SpringApplication.run(WherewegoApiApplication.class, args);
    }
}
