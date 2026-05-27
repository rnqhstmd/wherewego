package com.wherewego.infrastructure.auth;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.RetryContext;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LoginRetryListener 의 메트릭/가드 동작 단위 검증.
 * Spring AOP 없이 listener 만 직접 호출해 메트릭 발급 규칙을 단순하게 검증한다.
 */
class LoginRetryListenerTest {

    private MeterRegistry registry;
    private LoginRetryListener listener;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        listener = new LoginRetryListener(registry);
    }

    @Test
    @DisplayName("onError 가 호출되면 attempts counter 가 exception 태그별로 증가한다.")
    void onError_incrementsAttemptsCounterPerException() {
        RetryContext ctx = Mockito.mock(RetryContext.class);

        listener.onError(ctx, null, new DataIntegrityViolationException("race"));
        listener.onError(ctx, null, new DataIntegrityViolationException("race"));
        listener.onError(ctx, null, new CannotCreateTransactionException("cold start"));

        assertThat(attempts("DataIntegrityViolationException")).isEqualTo(2.0);
        assertThat(attempts("CannotCreateTransactionException")).isEqualTo(1.0);
        assertThat(registry.find("auth.login.retry.exhausted").counter()).isNull();
    }

    @Test
    @DisplayName("close(throwable != null) 시 exhausted counter 가 증가한다.")
    void close_withThrowable_incrementsExhausted() {
        RetryContext ctx = Mockito.mock(RetryContext.class);
        Mockito.when(ctx.getRetryCount()).thenReturn(3);

        listener.close(ctx, null, new DataIntegrityViolationException("unique"));

        assertThat(exhausted("DataIntegrityViolationException")).isEqualTo(1.0);
        // attempts 는 onError 만 증가시키므로 close 호출만으로는 미등록.
        assertThat(registry.find("auth.login.retry.attempts").counter()).isNull();
    }

    @Test
    @DisplayName("close(throwable == null) 즉 성공 종료 시 exhausted counter 는 변하지 않는다.")
    void close_withoutThrowable_doesNothing() {
        RetryContext ctx = Mockito.mock(RetryContext.class);

        listener.close(ctx, null, null);

        assertThat(registry.find("auth.login.retry.exhausted").counter()).isNull();
    }

    private double attempts(String exceptionName) {
        return registry.get("auth.login.retry.attempts")
                .tag("exception", exceptionName)
                .counter().count();
    }

    private double exhausted(String exceptionName) {
        return registry.get("auth.login.retry.exhausted")
                .tag("exception", exceptionName)
                .counter().count();
    }
}
