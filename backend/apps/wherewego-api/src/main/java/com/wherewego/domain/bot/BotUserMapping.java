package com.wherewego.domain.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

@Entity
@Getter
@Table(name = "bot_user_mapping")
public class BotUserMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bot_user_key", nullable = false, unique = true)
    private String botUserKey;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    protected BotUserMapping() { }

    private BotUserMapping(String botUserKey, Long userId, Instant linkedAt) {
        this.botUserKey = botUserKey;
        this.userId = userId;
        this.linkedAt = linkedAt;
    }

    public static BotUserMapping link(String botUserKey, Long userId, Instant now) {
        return new BotUserMapping(botUserKey, userId, now);
    }
}
