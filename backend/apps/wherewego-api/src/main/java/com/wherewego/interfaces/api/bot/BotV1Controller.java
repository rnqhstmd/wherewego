package com.wherewego.interfaces.api.bot;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.bot.BotLinkCodeIssueResult;
import com.wherewego.domain.bot.BotLinkCodeService;
import com.wherewego.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
