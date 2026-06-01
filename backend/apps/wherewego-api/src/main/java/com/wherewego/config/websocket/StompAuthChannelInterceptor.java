package com.wherewego.config.websocket;

import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.auth.jwt.JwtValidationResult;
import com.wherewego.domain.group.GroupMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;

/**
 * STOMP 프레임 인증/인가 인터셉터.
 *
 * <ul>
 *     <li><b>CONNECT</b>: Authorization Bearer → {@link JwtTokenProvider#parseAccessToken} 검증.
 *         valid면 principal = Long userId (JwtAuthenticationFilter 동일 모델), invalid면 연결 거부.</li>
 *     <li><b>SUBSCRIBE</b>: 화이트리스트 인가(미인식 destination = 거부).
 *         {@code /topic/chat/bot/{userId}} 는 principal userId 일치만,
 *         {@code /topic/chat/couple/{groupId}} 는 활성 그룹 멤버만 허용.
 *         두 패턴에 매칭되지 않는 destination(미인식 {@code /topic/...} 포함)은 모두 거부한다.</li>
 *     <li><b>SEND</b>: 미인증 전송 거부(principal userId 가 없으면 거부). 클라 전송은 REST 전용이라
 *         정상 흐름에서는 발생하지 않으나, 미인증 SEND 프레임을 명시적으로 차단한다.</li>
 *     <li>그 외 command(DISCONNECT/UNSUBSCRIBE/ACK/NACK 등 연결 종료·해제) 는 통과.</li>
 * </ul>
 *
 * <p>인가 실패 시 {@link MessageDeliveryException} 을 던져 STOMP ERROR 프레임으로 연결/구독을 거부한다.</p>
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BOT_TOPIC_PREFIX = "/topic/chat/bot/";
    private static final String COUPLE_TOPIC_PREFIX = "/topic/chat/couple/";

    private final JwtTokenProvider jwtTokenProvider;
    private final GroupMemberRepository groupMemberRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }

        switch (command) {
            case CONNECT -> authenticateConnect(accessor);
            case SUBSCRIBE -> authorizeSubscribe(accessor);
            case SEND -> authorizeSend(accessor);
            default -> {
                // DISCONNECT/UNSUBSCRIBE/ACK/NACK 등은 통과
            }
        }
        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String token = extractBearerToken(accessor.getFirstNativeHeader("Authorization"));
        if (token == null) {
            throw new MessageDeliveryException("STOMP CONNECT requires Authorization Bearer token");
        }

        JwtValidationResult result = jwtTokenProvider.parseAccessToken(token);
        if (!(result instanceof JwtValidationResult.Valid valid)) {
            throw new MessageDeliveryException("STOMP CONNECT token is invalid or expired");
        }

        accessor.setUser(new UsernamePasswordAuthenticationToken(valid.userId(), null, List.of()));
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        Long userId = resolvePrincipalUserId(accessor.getUser());
        if (userId == null) {
            throw new MessageDeliveryException("STOMP SUBSCRIBE requires an authenticated principal");
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            throw new MessageDeliveryException("STOMP SUBSCRIBE requires a destination");
        }

        if (destination.startsWith(BOT_TOPIC_PREFIX)) {
            Long pathUserId = parseId(destination.substring(BOT_TOPIC_PREFIX.length()));
            if (pathUserId == null || !pathUserId.equals(userId)) {
                throw new MessageDeliveryException("Not allowed to subscribe to another user's bot topic");
            }
        } else if (destination.startsWith(COUPLE_TOPIC_PREFIX)) {
            Long groupId = parseId(destination.substring(COUPLE_TOPIC_PREFIX.length()));
            if (groupId == null
                    || groupMemberRepository.findActiveByGroupIdAndUserId(groupId, userId).isEmpty()) {
                throw new MessageDeliveryException("Not an active member of the couple group");
            }
        } else {
            // 화이트리스트: bot/couple 패턴 외 destination(미인식 /topic/... 포함)은 명시적으로 거부.
            throw new MessageDeliveryException("Subscription to this destination is not allowed");
        }
    }

    /**
     * SEND 프레임은 인증된 principal 이 있어야만 허용한다(미인증 전송 거부).
     * 클라 메시지 전송은 REST 전용이라 정상 흐름에서는 SEND 가 없으나, 미인증 SEND 를 차단한다.
     */
    private void authorizeSend(StompHeaderAccessor accessor) {
        if (resolvePrincipalUserId(accessor.getUser()) == null) {
            throw new MessageDeliveryException("STOMP SEND requires an authenticated principal");
        }
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        if (!authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String value = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return value.isEmpty() ? null : value;
    }

    private Long resolvePrincipalUserId(Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken token
                && token.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }

    private Long parseId(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("/")) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
