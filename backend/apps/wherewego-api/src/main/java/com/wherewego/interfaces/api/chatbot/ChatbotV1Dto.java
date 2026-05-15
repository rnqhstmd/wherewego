package com.wherewego.interfaces.api.chatbot;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 카카오 i 오픈빌더 Skill 요청/응답 DTO. ApiResponse 래핑 미사용.
 */
public final class ChatbotV1Dto {

    public record SkillRequest(UserRequest userRequest, Action action) { }

    public record UserRequest(String utterance, User user) { }

    public record User(String id, String type) { }

    public record Action(Map<String, String> params) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SkillResponse(String version, Template template) {

        public static SkillResponse simple(String text) {
            Map<String, Object> output = Map.of(
                    "simpleText", Map.of("text", text)
            );
            return new SkillResponse("2.0", new Template(List.of(output)));
        }

        public static SkillResponse cards(List<Map<String, Object>> outputs) {
            return new SkillResponse("2.0", new Template(outputs));
        }

        public static SkillResponse empty() {
            return new SkillResponse("2.0", new Template(List.of()));
        }
    }

    public record Template(List<Map<String, Object>> outputs) { }

    private ChatbotV1Dto() { }
}
