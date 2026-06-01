package com.wherewego.config.async;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * P2 PR-1: 앱 봇 채팅 비동기 처리용 스레드 풀.
 *
 * <p>봇 1턴(인스타 링크 → Gemini 장소 추출 → PLACE_CARDS)을 {@code @Async("botChatExecutor")}
 * 로 백그라운드 처리한다. TX-A(요청)에서 PROCESSING 을 즉시 응답한 뒤 이 풀에서 TX-B/TX-C 가
 * 별도 트랜잭션으로 결과/실패를 append 한다.</p>
 *
 * <p>단일 t3.micro 인스턴스 전제(deployment.md)이므로 작은 풀로 고정한다. 큐 포화 시
 * {@link ThreadPoolExecutor.CallerRunsPolicy}로 호출 스레드(afterCommit 트리거 스레드)에서
 * 직접 실행하여 작업 유실을 방지한다(역압). 풀 포화·서버 재시작 시 PROCESSING 고아가 남을 수
 * 있으나(인지된 한계), 서버측 추가 방어는 범위 밖이며 클라 stale 처리(P5)로 다룬다.</p>
 *
 * <p>{@link AsyncConfigurer}의 {@link #getAsyncUncaughtExceptionHandler()}만 제공하여
 * 반환형 없는 {@code @Async} 메서드의 미처리 예외를 {@code log.error}로 가시화한다.
 * {@code getAsyncExecutor}는 오버라이드하지 않는다 — {@code @Async("botChatExecutor")}로
 * 풀을 명시 지정하는 현행 사용을 유지한다.</p>
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Bean("botChatExecutor")
    public ThreadPoolTaskExecutor botChatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("bot-chat-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 반환형 없는 {@code @Async} 메서드에서 전파된 미처리 예외 핸들러.
     * 비동기 스레드라 호출자에게 전파되지 않으므로 {@code log.error}로 가시성을 확보한다.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("미처리 @Async 예외 (method={}, params={})",
                        method, Arrays.toString(params), ex);
    }
}
