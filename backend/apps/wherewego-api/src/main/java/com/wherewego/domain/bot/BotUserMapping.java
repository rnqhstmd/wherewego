package com.wherewego.domain.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

/**
 * 카카오 botUserKey ↔ 웹 user_id 영구 매핑 (BR-4).
 *
 * <p>TODO(Phase 후속): {@code users.deleted_at} 설정 시 bot_user_mappings 처리 정책 확정.
 * 현재는 BR-4 "영구 매핑" 원칙으로 매핑 잔류. 재가입 케이스 미정의.</p>
 */
@Entity
@Getter
@Table(name = "bot_user_mappings")
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
