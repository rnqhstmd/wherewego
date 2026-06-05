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
 * IC-1: 1회용 소진(accepted_at) 시맨틱을 제거하고, 코드는 TTL(expires_at) 동안 정원 한도 내에서
 * 복수 사용자가 재사용 가입한다. 가입 여부는 group_members 행으로 판정하며(D2),
 * 정원 도달은 '만료'가 아니라 '가입 차단'(count>=10)이라 코드는 TTL까지 유지된다.</p>
 * <p>재발급(BR-3)·탈퇴(BR-5) 시 활성 토큰은 InviteLinkRepository.expirePendingByGroupId 일괄 만료.</p>
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
     * 만료 여부. expiresAt이 now 이전이거나 같으면 만료(true).
     * IC-1: 정원 도달은 만료가 아니라 가입 차단이므로 만료 판정은 expires_at(TTL) 단일이다(BR-1).
     */
    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
