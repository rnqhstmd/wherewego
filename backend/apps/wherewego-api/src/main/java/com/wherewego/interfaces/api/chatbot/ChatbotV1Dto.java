package com.wherewego.interfaces.api.chatbot;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 카카오 i 오픈빌더 Skill 요청/응답 DTO. ApiResponse 래핑 미사용.
 */
public final class ChatbotV1Dto {

    public record SkillRequest(UserRequest userRequest, Action action) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserRequest(String utterance, User user, String callbackUrl) { }

    public record User(String id, String type) { }

    public record Action(Map<String, String> params, Map<String, String> clientExtra) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SkillResponse(String version, Template template, Boolean useCallback) {

        public static SkillResponse simple(String text) {
            return simple(text, null);
        }

        public static SkillResponse simple(String text, List<QuickReply> quickReplies) {
            Map<String, Object> output = Map.of(
                    "simpleText", Map.of("text", text)
            );
            return new SkillResponse("2.0", new Template(List.of(output), quickReplies), null);
        }

        public static SkillResponse cards(List<Map<String, Object>> outputs) {
            return cards(outputs, null);
        }

        public static SkillResponse cards(List<Map<String, Object>> outputs,
                                          List<QuickReply> quickReplies) {
            return new SkillResponse("2.0", new Template(outputs, quickReplies), null);
        }

        public static SkillResponse empty() {
            return new SkillResponse("2.0", new Template(List.of(), null), null);
        }

        public static SkillResponse useCallback(String waitText) {
            // 카카오 i 오픈빌더 useCallback=true 응답: 대기 텍스트는 template.outputs[].simpleText.text 로 노출.
            // 별도 data 필드는 도입하지 않으며, NON_NULL 직렬화로 useCallback 필드는 false 가 아닌 true 일 때만 포함된다.
            Map<String, Object> output = Map.of(
                    "simpleText", Map.of("text", waitText)
            );
            return new SkillResponse("2.0", new Template(List.of(output), null), Boolean.TRUE);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Template(List<Map<String, Object>> outputs, List<QuickReply> quickReplies) { }

    /**
     * 카카오 i 오픈빌더 QuickReply (응답 하단 빠른 답장 버튼).
     * action="block"이면 blockId 사용, action="message"이면 messageText 사용.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuickReply(String label, String action, String blockId, String messageText) {

        public static QuickReply block(String label, String blockId) {
            return new QuickReply(label, "block", blockId, null);
        }

        public static QuickReply message(String label, String messageText) {
            return new QuickReply(label, "message", null, messageText);
        }
    }

    /**
     * 카카오 i 오픈빌더 BasicCard 컴포넌트. 직렬화 결과는 기존 Map.of 구조와 동일해야 한다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BasicCard(String title, String description, List<Button> buttons) { }

    /**
     * BasicCard 의 액션 버튼. {@code action="message"} 시 {@code messageText} 가 사용자 발화로 전송된다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Button(String label, String action, String messageText, Map<String, String> extra) { }

    private ChatbotV1Dto() { }
}
