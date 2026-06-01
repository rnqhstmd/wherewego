package com.wherewego.interfaces.api.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.chat.BotChatService;
import com.wherewego.domain.chat.ChatMessage;
import com.wherewego.domain.chat.ChatMessagePageResult;
import com.wherewego.domain.chat.CoupleChatService;
import com.wherewego.interfaces.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P2 PR-1: 앱 채팅 REST 컨트롤러(FR-4/5/8/9).
 *
 * <p>봇 방/커플 방 메시지 전송과 cursor 기반 메시지 페이지 조회를 노출한다. 실시간 push(STOMP/APNs)는
 * 서비스 계층이 트랜잭션 커밋 후 별도로 처리한다.</p>
 */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatV1Controller implements ChatV1ApiSpec {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final BotChatService botChatService;
    private final CoupleChatService coupleChatService;
    private final ObjectMapper objectMapper;

    @PostMapping("/bot/messages")
    @Override
    public ApiResponse<ChatV1Dto.SendMessageResponse> postBotMessage(
            @AuthUser Long userId,
            @Valid @RequestBody ChatV1Dto.BotMessageRequest request
    ) {
        ChatMessage processing = botChatService.postMessage(userId, request.text());
        return ApiResponse.success(ChatV1Dto.SendMessageResponse.from(processing));
    }

    @GetMapping("/bot/messages")
    @Override
    public ApiResponse<ChatV1Dto.MessagesResponse> getBotMessages(
            @AuthUser Long userId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer limit
    ) {
        ChatMessagePageResult result =
                botChatService.getBotMessages(userId, normalizeCursor(cursor), clampLimit(limit));
        return ApiResponse.success(ChatV1Dto.MessagesResponse.from(result, objectMapper));
    }

    @PostMapping("/couple/{groupId}/messages")
    @Override
    public ApiResponse<ChatV1Dto.SendMessageResponse> postCoupleMessage(
            @AuthUser Long userId,
            @PathVariable Long groupId,
            @Valid @RequestBody ChatV1Dto.CoupleMessageRequest request
    ) {
        ChatMessage saved = coupleChatService.postCoupleMessage(userId, groupId, request.text());
        return ApiResponse.success(ChatV1Dto.SendMessageResponse.from(saved));
    }

    @GetMapping("/couple/{groupId}/messages")
    @Override
    public ApiResponse<ChatV1Dto.MessagesResponse> getCoupleMessages(
            @AuthUser Long userId,
            @PathVariable Long groupId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer limit
    ) {
        ChatMessagePageResult result = coupleChatService.getCoupleMessages(
                userId, groupId, normalizeCursor(cursor), clampLimit(limit));
        return ApiResponse.success(ChatV1Dto.MessagesResponse.from(result, objectMapper));
    }

    /**
     * cursor 파라미터를 정규화한다. {@code null}이거나 1 미만(0/음수)이면 {@code null}로 처리하여
     * 최신부터 조회한다(유효 id는 1 이상이므로 음수/0은 의미 없는 값).
     */
    private static Long normalizeCursor(Long cursor) {
        if (cursor == null || cursor < 1) {
            return null;
        }
        return cursor;
    }

    /**
     * limit 파라미터를 1~50으로 정규화한다. 미전달이면 기본 20, 50 초과면 50으로 클램프한다(AC-3).
     */
    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
