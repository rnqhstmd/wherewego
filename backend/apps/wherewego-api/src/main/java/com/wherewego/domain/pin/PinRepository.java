package com.wherewego.domain.pin;

import java.util.List;
import java.util.Optional;

public interface PinRepository {

    Pin save(Pin pin);

    Optional<Pin> findById(Long id);

    /**
     * race-safe 조건부 UPDATE.
     * <p>{@code WHERE id=? AND created_by=? AND (memo_source IS NULL OR memo_source <> 'MANUAL')}.</p>
     * @return 갱신 행 수 (0 = 이미 MANUAL 또는 소유자 불일치)
     */
    int updateAutoMemoIfNotManual(Long pinId, Long ownerUserId, String memo);

    /**
     * 그룹의 활성 핀 목록을 {@code created_at} 내림차순으로 조회한다 (BR-2, BR-10).
     */
    List<Pin> findActiveByGroupIdOrderByCreatedAtDesc(Long groupId);

    /**
     * 그룹의 활성 핀을 태그 필터링 후 {@code created_at} 내림차순으로 조회한다 (FR-5).
     */
    List<Pin> findActiveByGroupIdAndTagOrderByCreatedAtDesc(Long groupId, PinTag tag);

    /**
     * PATCH/DELETE 단일 행 잠금 조회. {@code PESSIMISTIC_WRITE} 로 동시성을 직렬화한다 (Q4).
     */
    Optional<Pin> findActiveByIdAndGroupIdForUpdate(Long pinId, Long groupId);
}
