package com.wherewego.domain.pin;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Collection;
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
     * 락 없는 단건 활성 핀 조회 (read-only 트랜잭션 안전).
     * <p>readOnly=true 트랜잭션에서 {@code SELECT FOR UPDATE} 사용 시 일부 드라이버/설정에서
     * "cannot use SELECT FOR UPDATE in a read-only transaction" 오류가 발생하므로,
     * 조회 전용 경로에서 본 메서드를 사용한다.</p>
     */
    Optional<Pin> findActiveByIdAndGroupId(Long pinId, Long groupId);

    /**
     * 같은 그룹 내에서 placeName 동일 + 좌표가 약 10m 이내(±0.0001도 bounding box)인
     * 활성 핀을 1건 조회한다. URL이 달라도 같은 장소로 간주하기 위한 사전 중복 검사용.
     */
    Optional<Pin> findActiveByGroupPlaceNear(Long groupId, String placeName,
                                             BigDecimal latitude, BigDecimal longitude);

    /**
     * Phase 12: 정리 후보 핀 조회.
     * <p>조건: {@code tag=REEL AND memo_source='AUTO' AND created_at < threshold AND deleted_at IS NULL}.
     * 인덱스 {@code idx_pins_cleanup} (group_id, created_at) WHERE 조건 partial 인덱스를 활용한다.</p>
     */
    List<Pin> findCleanupCandidates(Long groupId, ZonedDateTime threshold);

    /**
     * Phase 12: 정리 후보 핀 개수. {@link #findCleanupCandidates}와 동일 조건.
     */
    long countCleanupCandidates(Long groupId, ZonedDateTime threshold);

    /**
     * Phase 12: 일괄 soft-delete. 각 엔티티의 {@link com.wherewego.domain.BaseEntity#delete()} 멱등 호출.
     * 이미 삭제된 행은 건너뛴다. {@code pinIds} 가 비어 있으면 0 반환.
     *
     * @return 이번 호출이 실제로 삭제 처리한 행 수 (이미 삭제됐던 행 제외)
     */
    int softDeleteAll(Collection<Long> pinIds);

    /**
     * GC-1(FR-GC1-4): 그룹의 활성 핀 중 주어진 인스타 URL 집합에 존재하는 URL 만 distinct 로 반환한다.
     * <p>메시지 페이지의 REEL_LINK {@code registered} 파생 계산용 — 페이지당 IN 쿼리 1회.
     * {@code uq_pins_group_instagram_place(group_id, instagram_url, place_name)} prefix 인덱스가 커버한다.</p>
     *
     * @param urls 비어 있으면 빈 리스트를 반환한다(쿼리 생략은 호출자 책임).
     */
    List<String> findActiveInstagramUrlsIn(Long groupId, Collection<String> urls);
}
