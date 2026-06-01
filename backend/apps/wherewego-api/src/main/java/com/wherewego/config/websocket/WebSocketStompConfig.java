package com.wherewego.config.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket 설정 (실시간 = 단방향 서버 push).
 *
 * <ul>
 *     <li>엔드포인트 {@code /ws/chat}: 네이티브 앱은 SockJS 불필요, Origin 없음 → setAllowedOriginPatterns("*").</li>
 *     <li>SimpleBroker {@code /topic}: 인메모리 단일 인스턴스 전제 (deployment.md).</li>
 *     <li>애플리케이션 prefix {@code /app}: 클라 전송은 REST POST 전용이라 실사용은 없으나 표준 구성 유지.</li>
 *     <li>clientInboundChannel 에 {@link StompAuthChannelInterceptor} 등록 (CONNECT 인증 + SUBSCRIBE 인가).</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketStompConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
