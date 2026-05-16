package com.wherewego.infrastructure.group;

import com.wherewego.domain.group.InviteLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface InviteLinkJpaRepository extends JpaRepository<InviteLink, Long> {

    Optional<InviteLink> findByToken(String token);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE InviteLink i SET i.expiresAt = :now "
            + "WHERE i.groupId = :gid AND i.acceptedAt IS NULL AND i.expiresAt > :now")
    int expirePendingByGroupId(@Param("gid") Long groupId, @Param("now") Instant now);
}
