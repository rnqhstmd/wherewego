package com.wherewego.config.security;

import com.wherewego.config.env.KakaoApiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@RequiredArgsConstructor
public class KakaoSkillSecretFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Kakao-Skill-Secret";

    private static final String CHATBOT_PATH_PREFIX = "/api/v1/chatbot/";
    private static final String UNAUTHORIZED_BODY =
            "{\"result\":\"FAIL\",\"error\":{\"code\":\"BOT_SKILL_SECRET_INVALID\"}}";

    private final KakaoApiProperties properties;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getRequestURI().startsWith(CHATBOT_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        String expected = properties.skill() != null ? properties.skill().secret() : null;

        if (provided == null || expected == null
                || !MessageDigest.isEqual(
                        provided.getBytes(StandardCharsets.UTF_8),
                        expected.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(UNAUTHORIZED_BODY);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
