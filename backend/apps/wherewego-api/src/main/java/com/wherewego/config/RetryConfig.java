package com.wherewego.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.retry.annotation.RetryConfiguration;

/**
 * @Retryable이 @Transactional보다 바깥(outer) 프록시가 되도록 순서를 명시한다.
 * 기본값은 둘 다 Ordered.LOWEST_PRECEDENCE(Integer.MAX_VALUE)라 순서가 불명확하다.
 * Retry를 LOWEST_PRECEDENCE - 1로 설정해 TX보다 먼저 실행되게 하면,
 * TX 인터셉터가 던지는 CannotCreateTransactionException을 Retry가 잡을 수 있다.
 *
 * @EnableRetry를 붙이지 않는다: @EnableRetry는 내부적으로 RetryConfiguration을 @Import하는데,
 * 이 클래스 자체가 RetryConfiguration을 상속하므로 함께 사용하면 동일 빈이 중복 등록되어
 * BeanDefinitionOverrideException이 발생한다.
 */
@Configuration
public class RetryConfig extends RetryConfiguration {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
