package com.wherewego.domain.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;

@Entity
@Getter
@Table(name = "bot_link_code")
public class BotLinkCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code", columnDefinition = "CHAR(6)", nullable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BotLinkCodeStatus status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected BotLinkCode() { }

    private BotLinkCode(Long userId, String code, Instant issuedAt, Instant expiresAt) {
        this.userId = userId;
        this.code = code;
        this.status = BotLinkCodeStatus.ACTIVE;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public static BotLinkCode issue(Long userId, String code, Instant now, Duration ttl) {
        return new BotLinkCode(userId, code, now, now.plus(ttl));
    }

    public void markConsumed(Instant now) {
        this.status = BotLinkCodeStatus.CONSUMED;
        this.consumedAt = now;
    }

    public void markExpired(Instant now) {
        this.status = BotLinkCodeStatus.EXPIRED;
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    public boolean isActive() {
        return status == BotLinkCodeStatus.ACTIVE;
    }
}
