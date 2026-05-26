package com.wherewego.infrastructure.auth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.Set;

/**
 * 카카오 로그인의 {@code @Retryable} 관측 listener.
 *
 * <p>발급 메트릭:
 * <ul>
 *     <li>{@code auth.login.retry.attempts} — retryable 예외로 인한 시도 실패마다 +1 (tag: {@code exception=<SimpleClassName>})</li>
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
 * <p>Spring Retry 의 RetryListener 는 retryFor 에 없는 예외(예: 도메인 {@code CoreException})에 대해서도
 * onError/close 가 호출된다. 따라서 listener 안에서 retryable 예외만 카운트해야 메트릭 의미가 흐려지지 않는다.
 * retryFor 목록은 {@link com.wherewego.domain.auth.UserLoginPersistence#upsertAndIssueTokens} 와 동기화 유지 필요.</p>
 *
 * <p>등록 방식: {@code @Component("loginRetryListener")} + 호출처 {@code @Retryable(listeners = "loginRetryListener")} 명시 연결.</p>
 */
@Component("loginRetryListener")
public class LoginRetryListener implements RetryListener {

    private static final Logger log = LoggerFactory.getLogger(LoginRetryListener.class);

    private static final String ATTEMPTS_METRIC = "auth.login.retry.attempts";
    private static final String EXHAUSTED_METRIC = "auth.login.retry.exhausted";

    // UserLoginPersistence.@Retryable(retryFor=...) 와 동기화 유지.
    private static final Set<Class<? extends Throwable>> RETRYABLE_EXCEPTIONS = Set.of(
            CannotCreateTransactionException.class,
            DataIntegrityViolationException.class
    );

    private final MeterRegistry registry;

    public LoginRetryListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
        if (!isRetryable(throwable)) {
            return;
        }
        String exceptionName = throwable.getClass().getSimpleName();
        counter(ATTEMPTS_METRIC, exceptionName).increment();
        log.warn("login retry attempt count={} exception={} message={}",
                context.getRetryCount(), exceptionName, throwable.getMessage());
    }

    /**
     * {@code close()} 는 성공/실패 모두에서 호출되므로 throwable null + retryable 가드.
     * 마지막 시도가 retryable 예외로 끝나 fallback {@code @Recover} 가 호출되는 케이스만 exhausted 로 카운트.
     */
    @Override
    public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
        if (throwable == null || !isRetryable(throwable)) {
            return;
        }
        String exceptionName = throwable.getClass().getSimpleName();
        counter(EXHAUSTED_METRIC, exceptionName).increment();
        log.error("login retry exhausted attempts={} exception={} message={}",
                context.getRetryCount(), exceptionName, throwable.getMessage());
    }

    private boolean isRetryable(Throwable t) {
        for (Class<? extends Throwable> retryable : RETRYABLE_EXCEPTIONS) {
            if (retryable.isInstance(t)) return true;
        }
        return false;
    }

    /**
     * 매 호출마다 Counter.builder(...).register(registry) — Micrometer 가 동일 name+tag 의 meter 를
     * dedup 하므로 인스턴스는 재사용된다. 캐시를 따로 두지 않아 테스트에서 {@code registry.clear()} 후에도
     * 자동으로 새 meter 가 다시 등록되어 호출-격리가 깨지지 않는다.
     */
    private Counter counter(String name, String exceptionName) {
        return Counter.builder(name)
                .tag("exception", exceptionName)
                .register(registry);
    }
}
