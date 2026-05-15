package com.wherewego.infrastructure.bot;

import com.wherewego.domain.bot.BotLinkCode;
import com.wherewego.domain.bot.BotLinkCodeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface BotLinkCodeJpaRepository extends JpaRepository<BotLinkCode, Long> {

    Optional<BotLinkCode> findFirstByCodeAndStatusAndExpiresAtAfter(
            String code,
            BotLinkCodeStatus status,
            Instant now
    );

    Optional<BotLinkCode> findFirstByUserIdAndStatusAndExpiresAtAfter(
            Long userId,
            BotLinkCodeStatus status,
            Instant now
    );

    boolean existsByCodeAndStatusAndExpiresAtAfter(
            String code,
            BotLinkCodeStatus status,
            Instant now
    );

    @Modifying
    @Query("UPDATE BotLinkCode b SET b.status = com.wherewego.domain.bot.BotLinkCodeStatus.EXPIRED "
            + "WHERE b.userId = :userId AND b.status = com.wherewego.domain.bot.BotLinkCodeStatus.ACTIVE")
    int expireActiveByUserId(@Param("userId") Long userId);
}
