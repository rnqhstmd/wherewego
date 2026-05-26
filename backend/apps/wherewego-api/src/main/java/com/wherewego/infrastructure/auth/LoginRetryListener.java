package com.wherewego.infrastructure.auth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 카카오 로그인의 {@code @Retryable} 관측 listener.
 *
 * <p>발급 메트릭:
 * <ul>
 *     <li>{@code auth.login.retry.attempts} — 각 재시도 실패마다 +1 (tag: {@code exception=<SimpleClassName>})</li>
 *     <li>{@code auth.login.retry.exhausted} — 모든 시도 소진 후 fallback {@code @Recover} 진입 시 +1</li>
 * </ul>
 * </p>
 *
 * <p>로그 레벨:
 * <ul>
 *     <li>attempt: WARN — recoverable 이지만 비정상</li>
 *     <li>exhausted: ERROR — 사용자 노출 실패(AUTH_KAKAO_API_FAILED)로 이어짐</li>
 * </ul>
 * </p>
 *
 * <p>등록 방식: {@code @Component("loginRetryListener")} 명시 + 호출처에서
 * {@code @Retryable(listeners = "loginRetryListener")} 로 직접 연결.
 * 전역 {@code RetryConfigurer} 가 아니어서 향후 다른 {@code @Retryable} 도입 시 의도치 않은 노이즈가 없다.</p>
 */
@Component("loginRetryListener")
public class LoginRetryListener implements RetryListener {

    private static final Logger log = LoggerFactory.getLogger(LoginRetryListener.class);

    private static final String ATTEMPTS_METRIC = "auth.login.retry.attempts";
    private static final String EXHAUSTED_METRIC = "auth.login.retry.exhausted";

    private final MeterRegistry registry;
    private final Map<String, Counter> attemptCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> exhaustedCounters = new ConcurrentHashMap<>();

    public LoginRetryListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
        String exceptionName = throwable.getClass().getSimpleName();
        attemptCounters.computeIfAbsent(exceptionName, name ->
                Counter.builder(ATTEMPTS_METRIC)
                        .tag("exception", name)
                        .register(registry)
        ).increment();
        log.warn("login retry attempt count={} exception={} message={}",
                context.getRetryCount(), exceptionName, throwable.getMessage());
    }

    /**
     * {@code close()} 는 성공/실패 모두에서 호출되므로 lastThrowable 가드.
     * 마지막 시도가 예외로 끝나면 fallback {@code @Recover} 가 호출되는 케이스 — exhausted 로 카운트.
     */
    @Override
    public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
        if (throwable == null) {
            return;
        }
        String exceptionName = throwable.getClass().getSimpleName();
        exhaustedCounters.computeIfAbsent(exceptionName, name ->
                Counter.builder(EXHAUSTED_METRIC)
                        .tag("exception", name)
                        .register(registry)
        ).increment();
        log.error("login retry exhausted attempts={} exception={} message={}",
                context.getRetryCount(), exceptionName, throwable.getMessage());
    }
}
