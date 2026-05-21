package com.wherewego.infrastructure.notification;

import com.wherewego.domain.notification.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationJpaRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByReceiverIdOrderByCreatedAtDesc(Long receiverId, Pageable pageable);

    Optional<Notification> findByIdAndReceiverId(Long id, Long receiverId);

    long countByReceiverIdAndReadAtIsNull(Long receiverId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now, n.updatedAt = :nowZdt "
            + "WHERE n.receiverId = :receiverId AND n.readAt IS NULL")
    int markAllRead(@Param("receiverId") Long receiverId,
                    @Param("now") Instant now,
                    @Param("nowZdt") ZonedDateTime nowZdt);
}
