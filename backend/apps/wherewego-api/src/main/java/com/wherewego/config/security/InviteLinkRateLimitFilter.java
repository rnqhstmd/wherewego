package com.wherewego.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherewego.interfaces.api.ApiResponse;
import com.wherewego.support.error.ErrorType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@code GET /api/v1/groups/invite-links/by-slug/{slug}} 의 IP 기반 레이트리밋 Filter.
 *
 * <p>공개 엔드포인트이므로 무차별 대입(brute-force)을 막기 위해 IP당 분당 30회로 제한.
 * 초과 시 429 + INVITE_LINK_RATE_LIMITED 코드를 반환한다.</p>
 */
@Component
@RequiredArgsConstructor
public class InviteLinkRateLimitFilter extends OncePerRequestFilter {

    private static final String PATH_PREFIX = "/api/v1/groups/invite-links/by-slug/";

    private final InviteLinkRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !(HttpMethod.GET.matches(request.getMethod())
                && request.getRequestURI().startsWith(PATH_PREFIX));
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String clientIp = resolveClientIp(request);
        if (!rateLimiter.tryConsume(clientIp)) {
            writeRateLimited(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private void writeRateLimited(HttpServletResponse response) throws IOException {
        ErrorType type = ErrorType.INVITE_LINK_RATE_LIMITED;
        response.setStatus(type.getStatus().value());
        response.setHeader("Retry-After", "60");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.fail(type.getCode(), type.getMessage())
        );
    }
}
