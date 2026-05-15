package com.wherewego.domain.pin;

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
}
