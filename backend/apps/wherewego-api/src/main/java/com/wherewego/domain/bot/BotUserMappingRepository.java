package com.wherewego.domain.bot;

import java.util.Optional;

public interface BotUserMappingRepository {
    BotUserMapping save(BotUserMapping entity);
    Optional<BotUserMapping> findByBotUserKey(String botUserKey);
    Optional<BotUserMapping> findByUserId(Long userId);
}
