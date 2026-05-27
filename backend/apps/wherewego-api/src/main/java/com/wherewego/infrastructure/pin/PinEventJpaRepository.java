package com.wherewego.infrastructure.pin;

import com.wherewego.domain.pin.PinEvent;
import com.wherewego.domain.pin.PinEventAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Phase 12: {@link PinEvent} Spring Data JPA 리포지토리.
 *
 * <p>{@link com.wherewego.domain.pin.PinEventRepository} 포트의 어댑터인
 * {@link PinEventRepositoryImpl} 내부에서만 의존한다 (헥사고날 격리).</p>
 */
public interface PinEventJpaRepository extends JpaRepository<PinEvent, Long> {

    boolean existsByPinIdAndUserIdAndAction(Long pinId, Long userId, PinEventAction action);

    @Modifying
    @Query("DELETE FROM PinEvent e WHERE e.pinId = :pinId AND e.userId = :userId AND e.action = :action")
    int deleteByPinIdAndUserIdAndAction(@Param("pinId") Long pinId,
                                        @Param("userId") Long userId,
                                        @Param("action") PinEventAction action);

    @Query("SELECT COUNT(e) FROM PinEvent e WHERE e.pinId = :pinId AND e.action = com.wherewego.domain.pin.PinEventAction.WANT")
    long countWantByPinId(@Param("pinId") Long pinId);

    @Query("SELECT e.userId FROM PinEvent e WHERE e.pinId = :pinId "
            + "AND e.action = com.wherewego.domain.pin.PinEventAction.WANT "
            + "ORDER BY e.createdAt ASC, e.id ASC")
    List<Long> findWantVoterIdsByPinId(@Param("pinId") Long pinId);

    @Query("SELECT e.pinId FROM PinEvent e WHERE e.pinId IN :pinIds "
            + "AND e.userId = :userId "
            + "AND e.action = com.wherewego.domain.pin.PinEventAction.WANT")
    List<Long> findMyWantPinIds(@Param("pinIds") Collection<Long> pinIds,
                                @Param("userId") Long userId);
}
