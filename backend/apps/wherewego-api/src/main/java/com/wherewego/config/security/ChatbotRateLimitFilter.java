package com.wherewego.config.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 카카오 챗봇 Webhook 레이트 리밋 Filter (Phase 2.6 PR-B B-3).
 *
 * <p>흐름: {@code /api/v1/chatbot/webhook} POST 만 처리.
 * <ol>
 *   <li>{@link BufferedRequestWrapper} 로 본문 byte[] 캐싱</li>
 *   <li>JSON {@code userRequest.user.id} 추출</li>
 *   <li>누락/null/빈 문자열 → HTTP 200 + AC-B9 안내 메시지</li>
 *   <li>{@link ChatbotRateLimiter#tryConsume}=false → HTTP 200 + AC-B5 안내 메시지</li>
 *   <li>통과 → wrapped request 로 다음 Filter 호출</li>
 * </ol>
 * </p>
 */
@Component
@RequiredArgsConstructor
public class ChatbotRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ChatbotRateLimitFilter.class);

    private static final String WEBHOOK_PATH = "/api/v1/chatbot/webhook";
    private static final String MISSING_KEY_MESSAGE =
            "일시적으로 이용에 불편이 있어요. 잠시 후 다시 시도해 주세요.";
    private static final String RATE_LIMITED_MESSAGE = "잠시 후 다시 시도해 주세요.";

    private final ChatbotRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod())
                && WEBHOOK_PATH.equals(request.getServletPath()));
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        BufferedRequestWrapper wrapped;
        try {
            wrapped = new BufferedRequestWrapper(request);
        } catch (IOException e) {
            log.warn("Failed to buffer chatbot webhook request body cause={}", e.getMessage());
            writeSkillResponse(response, MISSING_KEY_MESSAGE);
            return;
        }

        String botUserKey = extractBotUserKey(wrapped.getCachedBody());
        if (botUserKey == null || botUserKey.isBlank()) {
            writeSkillResponse(response, MISSING_KEY_MESSAGE);
            return;
        }

        if (!rateLimiter.tryConsume(botUserKey)) {
            writeSkillResponse(response, RATE_LIMITED_MESSAGE);
            return;
        }

        filterChain.doFilter(wrapped, response);
    }

    private String extractBotUserKey(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode idNode = root.path("userRequest").path("user").path("id");
            if (idNode.isMissingNode() || idNode.isNull()) {
                return null;
            }
            return idNode.asText(null);
        } catch (IOException e) {
            log.warn("Failed to parse chatbot webhook body cause={}", e.getMessage());
            return null;
        }
    }

    private void writeSkillResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ChatbotV1Dto.SkillResponse.simple(message));
    }
}
