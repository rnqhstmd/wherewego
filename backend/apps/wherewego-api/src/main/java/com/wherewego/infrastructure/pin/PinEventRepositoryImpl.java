package com.wherewego.infrastructure.pin;

import com.wherewego.domain.pin.PinEvent;
import com.wherewego.domain.pin.PinEventAction;
import com.wherewego.domain.pin.PinEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 12: {@link PinEventRepository} 어댑터 — {@link PinEventJpaRepository} 위임.
 *
 * <p>도메인 포트는 Spring Data 의존을 노출하지 않는다 (헥사고날). 본 클래스가 그 경계를 격리한다.</p>
 */
@RequiredArgsConstructor
@Component
public class PinEventRepositoryImpl implements PinEventRepository {

    private final PinEventJpaRepository jpaRepository;

    @Override
    public PinEvent save(PinEvent event) {
        return jpaRepository.saveAndFlush(event);
    }

    @Override
    public void deleteByPinAndUserAndAction(Long pinId, Long userId, PinEventAction action) {
        jpaRepository.deleteByPinIdAndUserIdAndAction(pinId, userId, action);
    }

    @Override
    public boolean existsByPinAndUserAndAction(Long pinId, Long userId, PinEventAction action) {
        return jpaRepository.existsByPinIdAndUserIdAndAction(pinId, userId, action);
    }

    @Override
    public int countWantByPinId(Long pinId) {
        return Math.toIntExact(jpaRepository.countWantByPinId(pinId));
    }

    @Override
    public List<Long> findWantVoterIdsByPinId(Long pinId) {
        return jpaRepository.findWantVoterIdsByPinId(pinId);
    }

    @Override
    public Set<Long> findMyWantPinIds(Collection<Long> pinIds, Long userId) {
        if (pinIds == null || pinIds.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(jpaRepository.findMyWantPinIds(pinIds, userId));
    }
}
