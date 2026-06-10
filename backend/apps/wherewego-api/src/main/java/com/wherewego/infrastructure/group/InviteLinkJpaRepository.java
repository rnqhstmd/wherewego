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
            + "WHERE i.slug = :slug AND i.expiresAt > :now")
    Optional<InviteLink> findActiveBySlug(@Param("slug") String slug, @Param("now") Instant now);

    /**
     * 그룹의 현재 활성(미만료) 초대 링크 조회(IC-2 후속). 재발급(BR-3)이 동일 그룹 미만료 토큰을 일괄 만료한 뒤
     * 1건만 신규 발급하므로 활성 행은 0~1개지만, 안전하게 만료가 가장 늦은 1건을 반환한다.
     */
    Optional<InviteLink> findFirstByGroupIdAndExpiresAtAfterOrderByExpiresAtDesc(Long groupId, Instant now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE InviteLink i SET i.expiresAt = :now "
            + "WHERE i.groupId = :gid AND i.expiresAt > :now")
    int expirePendingByGroupId(@Param("gid") Long groupId, @Param("now") Instant now);

    /**
     * V011 백필 대상 id 페이지 — slug IS NULL 인 활성 미만료 행.
     * WHERE 조건이 backfill 완료에 따라 자동으로 좁아지므로 항상 offset=0 인 페이지로 반복 호출한다.
     */
    @Query("SELECT i.id FROM InviteLink i "
            + "WHERE i.slug IS NULL AND i.expiresAt > :now "
            + "ORDER BY i.id ASC")
    List<Long> findIdsWithoutSlug(@Param("now") Instant now, Pageable pageable);

    /**
     * 백필 전용 단건 UPDATE. 동시성 안전을 위해 slug IS NULL 조건을 다시 검증한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE InviteLink i SET i.slug = :slug WHERE i.id = :id AND i.slug IS NULL")
    int updateSlugIfNull(@Param("id") Long id, @Param("slug") String slug);
}
