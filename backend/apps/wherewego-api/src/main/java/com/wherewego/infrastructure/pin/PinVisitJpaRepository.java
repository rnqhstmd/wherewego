package com.wherewego.infrastructure.pin;

import com.wherewego.domain.pin.PinVisit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PinVisitJpaRepository extends JpaRepository<PinVisit, Long> {

    Optional<PinVisit> findByPinIdAndUserId(Long pinId, Long userId);

    // 핀 응답 visitors[] 합성용 batch 조회(registered 파생 선례 — 페이지당 IN 1회).
    List<PinVisit> findByPinIdIn(Collection<Long> pinIds);
}
