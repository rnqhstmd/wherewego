package com.wherewego.infrastructure.pin;

import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PinRepositoryImpl implements PinRepository {

    private final PinJpaRepository jpaRepository;

    @Override
    public Pin save(Pin pin) {
        return jpaRepository.save(pin);
    }

    @Override
    public Optional<Pin> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public int updateAutoMemoIfNotManual(Long pinId, Long ownerUserId, String memo) {
        return jpaRepository.updateAutoMemoIfNotManual(pinId, ownerUserId, memo);
    }
}
