package com.wherewego.domain.bot;

import java.time.Instant;
import java.util.Optional;

public interface BotLinkCodeRepository {
    BotLinkCode save(BotLinkCode entity);
    Optional<BotLinkCode> findActiveByCode(String code, Instant now);
    Optional<BotLinkCode> findActiveByUserId(Long userId, Instant now);
    int expireActiveByUserId(Long userId, Instant now);
    boolean existsActiveByCode(String code, Instant now);
}
