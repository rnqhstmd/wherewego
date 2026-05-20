package com.wherewego.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 모든 HTTP 요청에 UUID RequestId를 발급하여 MDC와 응답 헤더에 주입한다.
 *
 * <p>외부 헤더({@code X-Request-Id})는 신뢰하지 않고 항상 자체 발급한다 (MUST-2 스푸핑 차단).
 * {@code finally} 블록의 {@link MDC#clear()}로 스레드 재사용 시 오염을 방지한다 (AC-2).</p>
 */
public final class RequestIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";
    public static final String RESPONSE_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);
        response.setHeader(RESPONSE_HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
