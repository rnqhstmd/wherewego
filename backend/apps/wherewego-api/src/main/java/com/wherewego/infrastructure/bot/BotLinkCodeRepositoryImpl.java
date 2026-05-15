package com.wherewego.infrastructure.bot;

import com.wherewego.domain.bot.BotLinkCode;
import com.wherewego.domain.bot.BotLinkCodeRepository;
import com.wherewego.domain.bot.BotLinkCodeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class BotLinkCodeRepositoryImpl implements BotLinkCodeRepository {

    private final BotLinkCodeJpaRepository jpaRepository;

    @Override
    public BotLinkCode save(BotLinkCode entity) {
        return jpaRepository.save(entity);
    }

    @Override
    public Optional<BotLinkCode> findActiveByCode(String code, Instant now) {
        return jpaRepository.findFirstByCodeAndStatusAndExpiresAtAfter(code, BotLinkCodeStatus.ACTIVE, now);
    }

    @Override
    public Optional<BotLinkCode> findActiveByUserId(Long userId, Instant now) {
        return jpaRepository.findFirstByUserIdAndStatusAndExpiresAtAfter(userId, BotLinkCodeStatus.ACTIVE, now);
    }

    @Override
    public int expireActiveByUserId(Long userId, Instant now) {
        return jpaRepository.expireActiveByUserId(userId);
    }

    @Override
    public boolean existsActiveByCode(String code, Instant now) {
        return jpaRepository.existsByCodeAndStatusAndExpiresAtAfter(code, BotLinkCodeStatus.ACTIVE, now);
    }
}
