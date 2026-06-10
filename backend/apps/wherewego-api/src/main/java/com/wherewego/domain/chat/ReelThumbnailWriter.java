package com.wherewego.domain.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-3(FR-GC3-2): 비동기 스크래핑으로 확보한 og:image URL 을 REEL_LINK payload 에 반영하는 트랜잭션 부품.
 *
 * <p>{@link ReelThumbnailService}(@Async, no-tx)가 self-invocation 으로 @Transactional 을 우회하지 않도록
 * 별도 빈으로 분리한다(BotChatProcessor/Writer 선례). dirty checking 으로 커밋 시 payload_json 만 갱신되며
 * 컬럼/스키마 변경은 없다.</p>
 *
 * <p>그 사이 메시지가 soft-delete/부재이거나 비-REEL_LINK 면 조용히 no-op 한다(best-effort — 전송 흐름과 무관).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReelThumbnailWriter {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageAppender chatMessageAppender;
    private final ObjectMapper objectMapper;

    /**
     * 활성 REEL_LINK 메시지의 payload 에 {@code thumbnailUrl} 을 채운다(GC-3). 기존 {@code url} 은 보존한다.
     *
     * @param messageId    대상 메시지 ID
     * @param thumbnailUrl 스크래핑한 og:image URL
     */
    @Transactional
    public void attach(Long messageId, String thumbnailUrl) {
        try {
            ChatMessage message = chatMessageRepository.findActiveById(messageId).orElse(null);
            if (message == null || message.getKind() != MessageKind.REEL_LINK) {
                return;
            }
            String url = readUrl(message.getPayloadJson());
            if (url == null || url.isBlank()) {
                return;
            }
            String newJson = chatMessageAppender.serializeReelLinkPayload(url, thumbnailUrl);
            message.replacePayloadJson(newJson);
            chatMessageRepository.save(message);
        } catch (Exception e) {
            log.warn("릴스 썸네일 payload 반영 실패 (messageId={}): {}", messageId, e.getMessage());
        }
    }

    private String readUrl(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        JsonNode node = readTree(payloadJson);
        if (node == null) {
            return null;
        }
        JsonNode url = node.get("url");
        return url == null || url.isNull() ? null : url.asText();
    }

    private JsonNode readTree(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            log.warn("릴스 썸네일 payload 파싱 실패: {}", e.getMessage());
            return null;
        }
    }
}
