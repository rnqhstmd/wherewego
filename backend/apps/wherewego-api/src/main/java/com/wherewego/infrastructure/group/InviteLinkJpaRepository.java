package com.wherewego.infrastructure.group;

import com.wherewego.domain.group.InviteLink;
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
     * V011 백필 대상 id 목록 — slug IS NULL 인 활성 미만료 미수락 행.
     */
    @Query("SELECT i.id FROM InviteLink i "
            + "WHERE i.slug IS NULL AND i.acceptedAt IS NULL AND i.expiresAt > :now")
    List<Long> findIdsWithoutSlug(@Param("now") Instant now);

    /**
     * 백필 전용 단건 UPDATE. 동시성 안전을 위해 slug IS NULL 조건을 다시 검증한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE InviteLink i SET i.slug = :slug WHERE i.id = :id AND i.slug IS NULL")
    int updateSlugIfNull(@Param("id") Long id, @Param("slug") String slug);
}
