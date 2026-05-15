package com.wherewego.domain.bot;

import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BotUserMappingService {

    private final BotUserMappingRepository mappingRepository;
    private final BotLinkCodeService linkCodeService;

    @Transactional
    public BotUserLinkResult link(String code, String botUserKey, Instant now) {
        if (mappingRepository.findByBotUserKey(botUserKey).isPresent()) {
            throw new CoreException(ErrorType.BOT_USER_ALREADY_LINKED);
        }

        BotLinkCodeConsumeResult consumed = linkCodeService.consumeCode(code, now);

        if (mappingRepository.findByUserId(consumed.userId()).isPresent()) {
            throw new CoreException(ErrorType.BOT_USER_ALREADY_LINKED);
        }

        BotUserMapping mapping = mappingRepository.save(
                BotUserMapping.link(botUserKey, consumed.userId(), now)
        );
        return new BotUserLinkResult(mapping.getUserId(), mapping.getBotUserKey(), mapping.getLinkedAt());
    }

    @Transactional(readOnly = true)
    public Optional<Long> resolveUserId(String botUserKey) {
        return mappingRepository.findByBotUserKey(botUserKey).map(BotUserMapping::getUserId);
    }
}
