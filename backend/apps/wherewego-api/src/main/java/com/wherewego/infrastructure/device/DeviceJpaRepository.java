package com.wherewego.infrastructure.device;

import com.wherewego.domain.device.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface DeviceJpaRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByUserIdAndDeviceTokenAndDeletedAtIsNull(Long userId, String deviceToken);

    List<Device> findByDeviceTokenAndDeletedAtIsNull(String deviceToken);

    List<Device> findByUserIdAndDeletedAtIsNull(Long userId);

    /**
     * 활성 (user_id, device_token) 행을 soft delete 한다(FR-16/BR-9). 벌크 갱신은
     * {@code @PreUpdate}를 우회하므로 {@code updatedAt}도 명시적으로 갱신한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Device d SET d.deletedAt = :now, d.updatedAt = :now "
            + "WHERE d.userId = :userId AND d.deviceToken = :deviceToken AND d.deletedAt IS NULL")
    int softDeleteByUserIdAndToken(@Param("userId") Long userId,
                                   @Param("deviceToken") String deviceToken,
                                   @Param("now") ZonedDateTime now);

    /**
     * 동일 token의 활성 행을 모두 soft delete 한다(FR-19 APNs 거부 토큰 정리).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Device d SET d.deletedAt = :now, d.updatedAt = :now "
            + "WHERE d.deviceToken = :deviceToken AND d.deletedAt IS NULL")
    int softDeleteByDeviceToken(@Param("deviceToken") String deviceToken,
                                @Param("now") ZonedDateTime now);

    /**
     * 사용자의 활성 행을 모두 soft delete 한다(FR-21 계정 삭제 정리).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Device d SET d.deletedAt = :now, d.updatedAt = :now "
            + "WHERE d.userId = :userId AND d.deletedAt IS NULL")
    int softDeleteByUserId(@Param("userId") Long userId, @Param("now") ZonedDateTime now);

    /**
     * 활성 행의 {@code updatedAt}을 갱신한다(FR-15 upsert touch, AC-7). 벌크 갱신이라
     * {@code @PreUpdate}를 우회하므로 {@code updatedAt}을 직접 지정한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Device d SET d.updatedAt = :now WHERE d.id = :id AND d.deletedAt IS NULL")
    int touchById(@Param("id") Long id, @Param("now") ZonedDateTime now);
}
