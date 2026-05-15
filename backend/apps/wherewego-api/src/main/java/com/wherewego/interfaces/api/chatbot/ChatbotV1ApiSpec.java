package com.wherewego.interfaces.api.chatbot;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Chatbot V1 API", description = "카카오 i 오픈빌더 Skill 서버 webhook API 입니다.")
public interface ChatbotV1ApiSpec {

    @Operation(
            summary = "카카오 챗봇 Skill webhook",
            description = "카카오 i 오픈빌더에서 호출하는 Skill 서버 진입점. "
                    + "X-Kakao-Skill-Secret 헤더 검증 후 5초 SLA 내에 SkillResponse 를 반환한다."
    )
    ChatbotV1Dto.SkillResponse webhook(ChatbotV1Dto.SkillRequest request);
}
