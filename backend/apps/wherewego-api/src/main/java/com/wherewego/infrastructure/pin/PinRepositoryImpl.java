package com.wherewego.infrastructure.pin;

import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinRepository;
import com.wherewego.domain.pin.PinTag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PinRepositoryImpl implements PinRepository {

    /** 좌표 근접 판정 허용 오차 — 위/경도 각 ±0.0001도 (약 10m bounding box). */
    private static final BigDecimal COORDINATE_TOLERANCE = new BigDecimal("0.0001");

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
        return jpaRepository.findByGroupIdAndDeletedAtIsNull(
                groupId, PageRequest.of(page, size, pagedSort()));
    }

    @Override
    public List<Pin> findActiveByGroupIdAndTagOrderByCreatedAtDesc(Long groupId, PinTag tag, int page, int size) {
        return jpaRepository.findByGroupIdAndTagAndDeletedAtIsNull(
                groupId, tag, PageRequest.of(page, size, pagedSort()));
    }

    /**
     * 페이지네이션 tie-breaker. 동일 {@code created_at} 행에서 page disjoint 를 보장하기 위해
     * 보조 키로 {@code id DESC} 를 추가한다 (cross-review Warning 후속).
     */
    private static Sort pagedSort() {
        return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
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

    @Override
    public Optional<Pin> findActiveByIdAndGroupId(Long pinId, Long groupId) {
        return jpaRepository.findByIdAndGroupIdAndDeletedAtIsNull(pinId, groupId);
    }

    @Override
    public Optional<Pin> findActiveByGroupPlaceNear(Long groupId, String placeName,
                                                    BigDecimal latitude, BigDecimal longitude) {
        BigDecimal latMin = latitude.subtract(COORDINATE_TOLERANCE);
        BigDecimal latMax = latitude.add(COORDINATE_TOLERANCE);
        BigDecimal lngMin = longitude.subtract(COORDINATE_TOLERANCE);
        BigDecimal lngMax = longitude.add(COORDINATE_TOLERANCE);
        return jpaRepository.findActiveByGroupPlaceNear(
                        groupId, placeName, latMin, latMax, lngMin, lngMax,
                        PageRequest.of(0, 1))
                .stream().findFirst();
    }

    // ────── Phase 12 ──────

    @Override
    public List<Pin> findCleanupCandidates(Long groupId, ZonedDateTime threshold) {
        return jpaRepository.findCleanupCandidates(groupId, threshold);
    }

    @Override
    public long countCleanupCandidates(Long groupId, ZonedDateTime threshold) {
        return jpaRepository.countCleanupCandidates(groupId, threshold);
    }

    @Override
    public int softDeleteAll(Collection<Long> pinIds) {
        if (pinIds == null || pinIds.isEmpty()) {
            return 0;
        }
        List<Pin> active = jpaRepository.findActiveByIdIn(pinIds);
        int deleted = 0;
        for (Pin pin : active) {
            // BaseEntity.delete() 는 deletedAt==null 일 때만 NOW() 부여 (멱등).
            // findActiveByIdIn 에서 deletedAt IS NULL 필터 통과 → 모두 신규 삭제 대상.
            pin.delete();
            deleted++;
        }
        return deleted;
    }

    @Override
    public List<Pin> findActiveByGroupIdSortedByWantCount(Long groupId, int page, int size) {
        return jpaRepository.findByGroupIdAndDeletedAtIsNull(
                groupId, PageRequest.of(page, size, wantCountSort()));
    }

    @Override
    public List<Pin> findActiveByGroupIdAndTagSortedByWantCount(Long groupId, PinTag tag, int page, int size) {
        if (tag == null) {
            return findActiveByGroupIdSortedByWantCount(groupId, page, size);
        }
        return jpaRepository.findByGroupIdAndTagAndDeletedAtIsNull(
                groupId, tag, PageRequest.of(page, size, wantCountSort()));
    }

    @Override
    public List<Pin> findActiveByGroupIdInterestOnly(Long groupId, PinTag tag, int page, int size) {
        if (tag == null) {
            return jpaRepository.findActiveByGroupIdInterestOnly(
                    groupId, PageRequest.of(page, size, wantCountSort()));
        }
        return jpaRepository.findActiveByGroupIdAndTagInterestOnly(
                groupId, tag, PageRequest.of(page, size, wantCountSort()));
    }

    @Override
    public long countActiveByGroupIdInterestOnly(Long groupId, PinTag tag) {
        if (tag == null) {
            return jpaRepository.countActiveByGroupIdInterestOnly(groupId);
        }
        return jpaRepository.countActiveByGroupIdAndTagInterestOnly(groupId, tag);
    }

    /**
     * want_count 내림차순 정렬 + tie-breaker.
     * 동일 want_count 의 행은 {@code created_at DESC}, 그래도 같으면 {@code id DESC} 로 disjoint 페이지를 보장한다.
     * 인덱스 {@code idx_pins_group_want_count (group_id, want_count DESC) WHERE deleted_at IS NULL} 활용 가능.
     */
    private static Sort wantCountSort() {
        return Sort.by(
                Sort.Order.desc("wantCount"),
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id"));
    }
}
