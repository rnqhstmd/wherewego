package com.wherewego.domain.group;

import com.wherewego.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * 초대 링크 엔티티. invite_links 테이블 매핑.
 *
 * <p>UUID 기반 단방향 토큰 + base56 8자 단축 slug. TTL 7d (Phase 11 PR-A 변경, 이전 24h).
 * accepted_at IS NULL = 미수락, NOT NULL = 수락 완료.</p>
 * <p>재발급 시 미수락 토큰은 InviteLinkRepository.expirePendingByGroupId 일괄 만료.</p>
 * <p>slug 는 path-based 단축 URL (/invite/{slug}) 용. token 과 1:1 매핑.</p>
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "invite_links")
public class InviteLink extends BaseEntity {

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "inviter_id", nullable = false)
    private Long inviterId;

    @Column(name = "token", nullable = false, length = 100, unique = true)
    private String token;

    @Column(name = "slug", length = 16)
    private String slug;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    private InviteLink(Long groupId, Long inviterId, String token, String slug, Instant expiresAt) {
        this.groupId = groupId;
        this.inviterId = inviterId;
        this.token = token;
        this.slug = slug;
        this.expiresAt = expiresAt;
    }

    /**
     * 신규 초대 링크 발급. expiresAt = now + ttl.
     * slug 는 신규 발급에서 반드시 채운다 (백필은 별도 Runner 가 처리).
     */
    public static InviteLink issue(Long groupId, Long inviterId, String token, String slug,
                                   Instant now, Duration ttl) {
        return new InviteLink(groupId, inviterId, token, slug, now.plus(ttl));
    }

    /**
     * 수락 완료 기록. 이미 수락된 상태에서 재호출되지 않도록 서비스 사전 검증 전제.
     */
    public void markAccepted(Instant now) {
        this.acceptedAt = now;
    }

    public boolean isPending() {
        return acceptedAt == null;
    }

    /**
     * 만료 여부. expiresAt이 now 이전이거나 같으면 만료(true). acceptedAt은 별도 판정.
     */
    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
