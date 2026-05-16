package com.wherewego.infrastructure.group;

import com.wherewego.domain.group.Group;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GroupJpaRepository extends JpaRepository<Group, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM GroupAggregate g WHERE g.id = :id")
    Optional<Group> findByIdForUpdate(@Param("id") Long id);
}
