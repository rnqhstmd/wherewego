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
 * by-slug(GET) + accept(POST) 의 IP 기반 레이트리밋 Filter.
 *
 * <ul>
 *   <li>{@code GET /api/v1/groups/invite-links/by-slug/{slug}} — 공개 미리보기 엔드포인트</li>
 *   <li>{@code POST /api/v1/groups/invite-links/{token}/accept} — 초대 수락 엔드포인트
 *       (IC-1 재사용 모델로 유효 토큰 반복 accept 가 가능해져 무차별 호출 방지를 위해 추가)</li>
 * </ul>
 *
 * <p>두 엔드포인트 모두 IP당 분당 30회로 제한하며, IP 예산을 공유한다.
 * 초과 시 429 + INVITE_LINK_RATE_LIMITED 코드를 반환한다.</p>
 *
 * <p>issue 엔드포인트({@code POST /api/v1/groups/{groupId}/invite-links})는 URI 가
 * {@link #BY_SLUG_PREFIX}/{@link #ACCEPT_PREFIX} 와 매칭되지 않으므로 대상에서 제외된다.</p>
 */
@Component
@RequiredArgsConstructor
public class InviteLinkRateLimitFilter extends OncePerRequestFilter {

    private static final String BY_SLUG_PREFIX = "/api/v1/groups/invite-links/by-slug/";
    private static final String ACCEPT_PREFIX = "/api/v1/groups/invite-links/";
    private static final String ACCEPT_SUFFIX = "/accept";

    private final InviteLinkRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !(isBySlugPreview(request) || isInviteAccept(request));
    }

    private boolean isBySlugPreview(HttpServletRequest request) {
        return HttpMethod.GET.matches(request.getMethod())
                && resolvePath(request).startsWith(BY_SLUG_PREFIX);
    }

    private boolean isInviteAccept(HttpServletRequest request) {
        String path = resolvePath(request);
        return HttpMethod.POST.matches(request.getMethod())
                && path.startsWith(ACCEPT_PREFIX)
                && path.endsWith(ACCEPT_SUFFIX);
    }

    /**
     * matrix variable({@code ;x=1}) / 경로 변형으로 매칭을 우회해 레이트리밋을 건너뛰는 것을 막기 위해
     * {@link HttpServletRequest#getServletPath()} 를 사용하고, 세미콜론 이후를 잘라내 정규화한다.
     */
    private String resolvePath(HttpServletRequest request) {
        String path = request.getServletPath();
        int semicolon = path.indexOf(';');
        return semicolon < 0 ? path : path.substring(0, semicolon);
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
        response.setHeader("Retry-After", String.valueOf(rateLimiter.getRefillSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.fail(type.getCode(), type.getMessage())
        );
    }
}
