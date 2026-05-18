package com.wherewego.infrastructure.pin;

import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinRepository;
import com.wherewego.domain.pin.PinTag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
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
    public Pin saveAndFlush(Pin pin) {
        return jpaRepository.saveAndFlush(pin);
    }

    @Override
    public Optional<Pin> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public int updateAutoMemoIfNotManual(Long pinId, Long ownerUserId, String memo) {
        return jpaRepository.updateAutoMemoIfNotManual(pinId, ownerUserId, memo);
    }

    @Override
    public List<Pin> findActiveByGroupIdOrderByCreatedAtDesc(Long groupId) {
        return jpaRepository.findByGroupIdAndDeletedAtIsNullOrderByCreatedAtDesc(groupId);
    }

    @Override
    public List<Pin> findActiveByGroupIdAndTagOrderByCreatedAtDesc(Long groupId, PinTag tag) {
        return jpaRepository.findByGroupIdAndTagAndDeletedAtIsNullOrderByCreatedAtDesc(groupId, tag);
    }

    @Override
    public List<Pin> findActiveByGroupIdOrderByCreatedAtDesc(Long groupId, int page, int size) {
        return jpaRepository.findByGroupIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                groupId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Override
    public List<Pin> findActiveByGroupIdAndTagOrderByCreatedAtDesc(Long groupId, PinTag tag, int page, int size) {
        return jpaRepository.findByGroupIdAndTagAndDeletedAtIsNullOrderByCreatedAtDesc(
                groupId, tag, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Override
    public long countActiveByGroupId(Long groupId) {
        return jpaRepository.countByGroupIdAndDeletedAtIsNull(groupId);
    }

    @Override
    public long countActiveByGroupIdAndTag(Long groupId, PinTag tag) {
        return jpaRepository.countByGroupIdAndTagAndDeletedAtIsNull(groupId, tag);
    }

    @Override
    public Optional<Pin> findActiveByIdAndGroupIdForUpdate(Long pinId, Long groupId) {
        return jpaRepository.findActiveByIdAndGroupIdForUpdate(pinId, groupId);
    }
}
