package com.wherewego.infrastructure.bot;

import com.wherewego.domain.bot.BotUserMapping;
import com.wherewego.domain.bot.BotUserMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class BotUserMappingRepositoryImpl implements BotUserMappingRepository {

    private final BotUserMappingJpaRepository jpaRepository;

    @Override
    public BotUserMapping save(BotUserMapping entity) {
        return jpaRepository.save(entity);
    }

    @Override
    public Optional<BotUserMapping> findByBotUserKey(String botUserKey) {
        return jpaRepository.findByBotUserKey(botUserKey);
    }

    @Override
    public Optional<BotUserMapping> findByUserId(Long userId) {
        return jpaRepository.findByUserId(userId);
    }
}
