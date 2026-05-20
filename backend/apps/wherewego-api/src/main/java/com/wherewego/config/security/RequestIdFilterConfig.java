package com.wherewego.config.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@link RequestIdFilter}를 모든 요청 진입점에 최우선 등록한다.
 * <p>비동기/스케줄러 MDC 전파는 호출 지점에서 명시적으로 처리한다
 * ({@code PlaceFallbackOrchestrator.runAsync}: snapshot capture,
 * {@code PendingInstagramAutoSaveScheduler}: {@code MDC.put("SCHEDULER")}).
 * {@code TaskDecorator} 빈은 별도 노출하지 않으며, Phase 2.11 PR-B에서
 * {@code AsyncConfigurer}/{@code SchedulingConfig}가 도입될 때 함께 보강될 예정이다.</p>
 *
 * <p>{@link EnableScheduling}은 본 PR-A에서 활성화한다 — PR-B에서 도입될
 * {@code ThresholdMonitorScheduler}가 누락된 어노테이션으로 인해 묵묵히 미실행되는 사고를
 * 사전 차단하기 위함이다. 현재는 기존 {@code @Scheduled} 빈
 * ({@code PendingInstagramAutoSaveScheduler})만 적용된다.</p>
 */
@Configuration
@EnableScheduling
class RequestIdFilterConfig {

    @Bean
    RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration(RequestIdFilter filter) {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
