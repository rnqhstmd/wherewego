package com.wherewego.infrastructure.pin;

import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinTag;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PinJpaRepository extends JpaRepository<Pin, Long> {

    @Modifying
    @Query("UPDATE Pin p SET p.memo = :memo, "
            + "p.memoSource = com.wherewego.domain.pin.MemoSource.AUTO "
            + "WHERE p.id = :pinId "
            + "AND p.createdBy = :ownerUserId "
            + "AND p.deletedAt IS NULL "
            + "AND (p.memoSource IS NULL OR p.memoSource <> com.wherewego.domain.pin.MemoSource.MANUAL)")
    int updateAutoMemoIfNotManual(@Param("pinId") Long pinId,
                                  @Param("ownerUserId") Long ownerUserId,
                                  @Param("memo") String memo);

    List<Pin> findByGroupIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long groupId);

    List<Pin> findByGroupIdAndTagAndDeletedAtIsNullOrderByCreatedAtDesc(Long groupId, PinTag tag);

    // paged 변형은 메서드명에 OrderBy 를 포함하지 않는다. 호출자가 Pageable.Sort 로
    // (createdAt DESC, id DESC) tie-breaker 를 명시한다 (cross-review Warning 후속).
    List<Pin> findByGroupIdAndDeletedAtIsNull(Long groupId, Pageable pageable);

    List<Pin> findByGroupIdAndTagAndDeletedAtIsNull(Long groupId, PinTag tag, Pageable pageable);

    long countByGroupIdAndDeletedAtIsNull(Long groupId);

    long countByGroupIdAndTagAndDeletedAtIsNull(Long groupId, PinTag tag);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Pin p WHERE p.id = :pinId AND p.groupId = :groupId AND p.deletedAt IS NULL")
    Optional<Pin> findActiveByIdAndGroupIdForUpdate(@Param("pinId") Long pinId, @Param("groupId") Long groupId);

    /**
     * placeName 동일 + 위/경도 bounding box 내 활성 핀 조회.
     * {@code idx_pins_group_location} 인덱스가 활용된다. 호출자({@link com.wherewego.infrastructure.pin.PinRepositoryImpl})
     * 가 {@code PageRequest.of(0, 1)} 로 1건만 가져온다.
     */
    @Query("SELECT p FROM Pin p WHERE p.groupId = :groupId "
            + "AND p.placeName = :placeName "
            + "AND p.latitude BETWEEN :latMin AND :latMax "
            + "AND p.longitude BETWEEN :lngMin AND :lngMax "
            + "AND p.deletedAt IS NULL "
            + "ORDER BY p.id ASC")
    List<Pin> findActiveByGroupPlaceNear(@Param("groupId") Long groupId,
                                         @Param("placeName") String placeName,
                                         @Param("latMin") BigDecimal latMin,
                                         @Param("latMax") BigDecimal latMax,
                                         @Param("lngMin") BigDecimal lngMin,
                                         @Param("lngMax") BigDecimal lngMax,
                                         Pageable pageable);
}
