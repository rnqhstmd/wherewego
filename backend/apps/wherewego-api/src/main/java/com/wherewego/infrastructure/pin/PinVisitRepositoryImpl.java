package com.wherewego.infrastructure.pin;

import com.wherewego.domain.pin.PinVisit;
import com.wherewego.domain.pin.PinVisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PinVisitRepositoryImpl implements PinVisitRepository {

    private final PinVisitJpaRepository jpaRepository;

    @Override
    public PinVisit save(PinVisit visit) {
        return jpaRepository.save(visit);
    }

    @Override
    public Optional<PinVisit> findByPinIdAndUserId(Long pinId, Long userId) {
        return jpaRepository.findByPinIdAndUserId(pinId, userId);
    }

    @Override
    public List<PinVisit> findByPinIdIn(Collection<Long> pinIds) {
        if (pinIds == null || pinIds.isEmpty()) {
            return List.of();
        }
        return jpaRepository.findByPinIdIn(pinIds);
    }
}
