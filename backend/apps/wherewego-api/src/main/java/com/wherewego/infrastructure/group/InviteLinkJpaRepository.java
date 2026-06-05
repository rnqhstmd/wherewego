package com.wherewego.infrastructure.group;

import com.wherewego.domain.group.InviteLink;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InviteLinkJpaRepository extends JpaRepository<InviteLink, Long> {

    Optional<InviteLink> findByToken(String token);

    @Query("SELECT i FROM InviteLink i "
            + "WHERE i.slug = :slug AND i.acceptedAt IS NULL AND i.expiresAt > :now")
    Optional<InviteLink> findActiveBySlug(@Param("slug") String slug, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE InviteLink i SET i.expiresAt = :now "
            + "WHERE i.groupId = :gid AND i.acceptedAt IS NULL AND i.expiresAt > :now")
    int expirePendingByGroupId(@Param("gid") Long groupId, @Param("now") Instant now);

    /**
     * V011 백필 대상 id 페이지 — slug IS NULL 인 활성 미만료 미수락 행.
     * WHERE 조건이 backfill 완료에 따라 자동으로 좁아지므로 항상 offset=0 인 페이지로 반복 호출한다.
     */
    @Query("SELECT i.id FROM InviteLink i "
            + "WHERE i.slug IS NULL AND i.acceptedAt IS NULL AND i.expiresAt > :now "
            + "ORDER BY i.id ASC")
    List<Long> findIdsWithoutSlug(@Param("now") Instant now, Pageable pageable);

    /**
     * 백필 전용 단건 UPDATE. 동시성 안전을 위해 slug IS NULL 조건을 다시 검증한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE InviteLink i SET i.slug = :slug WHERE i.id = :id AND i.slug IS NULL")
    int updateSlugIfNull(@Param("id") Long id, @Param("slug") String slug);

    /**
     * 토큰 1회용 보장: accepted_at IS NULL 일 때만 원자적으로 수락 시각을 기록한다.
     * 동일 토큰 동시 수락 시 1건만 1 을 반환하고 나머지는 0 → 서비스에서 INVITE_LINK_ALREADY_USED 처리.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE InviteLink i SET i.acceptedAt = :now WHERE i.id = :id AND i.acceptedAt IS NULL")
    int markAcceptedIfPending(@Param("id") Long id, @Param("now") Instant now);
}
