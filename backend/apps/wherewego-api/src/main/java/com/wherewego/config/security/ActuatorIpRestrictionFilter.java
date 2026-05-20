package com.wherewego.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * {@code /actuator/refresh}를 로컬호스트에서만 허용하는 IP 제한 Filter.
 *
 * <p>운영 EC2 보안 그룹 차단선과 별개로 애플리케이션 레벨 차단선 확보 (Phase 2.6 PR-B B-2).
 * SSH 터널 등을 통한 로컬 루프백 호출만 허용한다.</p>
 */
@Component
public class ActuatorIpRestrictionFilter extends OncePerRequestFilter {

    private static final String PROTECTED_PATH = "/actuator/refresh";
    private static final Set<String> LOCALHOST_ADDRESSES = Set.of(
            "127.0.0.1", "0:0:0:0:0:0:0:1", "::1"
    );
    private static final String FORBIDDEN_BODY =
            "{\"error\":\"FORBIDDEN_ACTUATOR_ACCESS\"}";

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getServletPath().equals(PROTECTED_PATH);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String remoteAddr = request.getRemoteAddr();
        if (!LOCALHOST_ADDRESSES.contains(remoteAddr)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(FORBIDDEN_BODY);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
