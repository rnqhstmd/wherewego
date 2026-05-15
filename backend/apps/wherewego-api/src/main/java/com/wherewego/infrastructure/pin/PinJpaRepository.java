package com.wherewego.infrastructure.pin;

import com.wherewego.domain.pin.Pin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PinJpaRepository extends JpaRepository<Pin, Long> {

    @Modifying
    @Query("UPDATE Pin p SET p.memo = :memo, "
            + "p.memoSource = com.wherewego.domain.pin.MemoSource.AUTO "
            + "WHERE p.id = :pinId "
            + "AND p.createdBy = :ownerUserId "
            + "AND (p.memoSource IS NULL OR p.memoSource <> com.wherewego.domain.pin.MemoSource.MANUAL)")
    int updateAutoMemoIfNotManual(@Param("pinId") Long pinId,
                                  @Param("ownerUserId") Long ownerUserId,
                                  @Param("memo") String memo);
}
