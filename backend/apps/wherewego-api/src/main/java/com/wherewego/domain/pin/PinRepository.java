package com.wherewego.domain.pin;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PinRepository {

    Pin save(Pin pin);

    /**
     * 즉시 flush 하여 DB 제약 위반을 동일 트랜잭션 내에서 받아낼 수 있게 한다.
     * <p>UNIQUE 충돌을 {@link org.springframework.dao.DataIntegrityViolationException} 으로
     * try-catch 블록에서 잡아 도메인 ErrorType 으로 변환하는 흐름에 사용한다.</p>
     */
    Pin saveAndFlush(Pin pin);

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
     * 그룹의 활성 핀 목록을 {@code created_at} 내림차순으로 페이지네이션 조회한다.
     */
    List<Pin> findActiveByGroupIdOrderByCreatedAtDesc(Long groupId, int page, int size);

    /**
     * 그룹의 활성 핀을 태그 필터링 후 {@code created_at} 내림차순으로 페이지네이션 조회한다.
     */
    List<Pin> findActiveByGroupIdAndTagOrderByCreatedAtDesc(Long groupId, PinTag tag, int page, int size);

    /**
     * 그룹의 활성 핀 전체 개수를 반환한다.
     */
    long countActiveByGroupId(Long groupId);

    /**
     * 그룹의 활성 핀을 태그로 필터링한 개수를 반환한다.
     */
    long countActiveByGroupIdAndTag(Long groupId, PinTag tag);

    /**
     * PATCH/DELETE 단일 행 잠금 조회. {@code PESSIMISTIC_WRITE} 로 동시성을 직렬화한다 (Q4).
     */
    Optional<Pin> findActiveByIdAndGroupIdForUpdate(Long pinId, Long groupId);

    /**
     * 같은 그룹 내에서 placeName 동일 + 좌표가 약 10m 이내(±0.0001도 bounding box)인
     * 활성 핀을 1건 조회한다. URL이 달라도 같은 장소로 간주하기 위한 사전 중복 검사용.
     */
    Optional<Pin> findActiveByGroupPlaceNear(Long groupId, String placeName,
                                             BigDecimal latitude, BigDecimal longitude);
}
