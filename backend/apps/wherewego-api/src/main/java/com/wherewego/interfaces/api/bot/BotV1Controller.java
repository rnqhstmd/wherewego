package com.wherewego.interfaces.api.bot;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.bot.BotLinkCodeIssueResult;
import com.wherewego.domain.bot.BotLinkCodeService;
import com.wherewego.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 봇 연동 관련 REST API.
 *
 * <p>TODO(Phase 후속): 사용자당 분당 N회 제한 적용 (Bucket4j/resilience4j 검토).
 * 베타 100명 규모에서는 즉시 위험 낮음.</p>
 */
@RestController
@RequestMapping("/api/v1/bot")
@RequiredArgsConstructor
public class BotV1Controller implements BotV1ApiSpec {

    private final BotLinkCodeService linkCodeService;

    @PostMapping("/link-codes")
    @Override
    public ApiResponse<BotV1Dto.LinkCodeResponse> issueLinkCode(@AuthUser Long userId) {
        BotLinkCodeIssueResult result = linkCodeService.issueCode(userId);
        return ApiResponse.success(BotV1Dto.LinkCodeResponse.from(result));
    }
}
