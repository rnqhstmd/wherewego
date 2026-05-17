package com.wherewego.infrastructure.bot;

import com.wherewego.domain.bot.BotUserMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BotUserMappingJpaRepository extends JpaRepository<BotUserMapping, Long> {

    Optional<BotUserMapping> findByBotUserKey(String botUserKey);

    Optional<BotUserMapping> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
