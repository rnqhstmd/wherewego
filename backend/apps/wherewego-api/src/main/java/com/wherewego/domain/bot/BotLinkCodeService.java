package com.wherewego.domain.bot;

import com.wherewego.config.env.BotProperties;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class BotLinkCodeService {

    private final BotLinkCodeRepository linkCodeRepository;
    private final LinkCodeGenerator linkCodeGenerator;
    private final BotProperties botProperties;

    @Transactional
    public BotLinkCodeIssueResult issueCode(Long userId) {
        Instant now = Instant.now();
        Duration ttl = Duration.ofMinutes(botProperties.linkCode().ttlMinutes());

        linkCodeRepository.expireActiveByUserId(userId, now);

        int maxRetries = botProperties.linkCode().maxGenerationRetries();
        String code = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String candidate = linkCodeGenerator.generate6Digits();
            if (!linkCodeRepository.existsActiveByCode(candidate, now)) {
                code = candidate;
                break;
            }
        }
        if (code == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "연동코드 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        }

        BotLinkCode entity = linkCodeRepository.save(BotLinkCode.issue(userId, code, now, ttl));
        return new BotLinkCodeIssueResult(entity.getCode(), entity.getExpiresAt(), entity.getUserId());
    }

    @Transactional
    public BotLinkCodeConsumeResult consumeCode(String code, Instant now) {
        BotLinkCode active = linkCodeRepository.findActiveByCode(code, now)
                .orElseThrow(() -> new CoreException(ErrorType.BOT_LINK_CODE_INVALID));

        if (active.isExpired(now)) {
            throw new CoreException(ErrorType.BOT_LINK_CODE_EXPIRED);
        }

        active.markConsumed(now);
        linkCodeRepository.save(active);
        return new BotLinkCodeConsumeResult(active.getUserId(), active.getCode());
    }
}
