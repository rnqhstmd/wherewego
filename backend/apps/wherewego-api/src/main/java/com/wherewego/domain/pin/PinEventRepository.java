package com.wherewego.domain.pin;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Phase 12: {@link PinEvent} 포트 인터페이스. 헥사고날 도메인 계층 (Spring 의존 노출 X).
 *
 * <p>WANT 토글 트랜잭션(WantService) 및 {@code GET /pins} 응답 enrichment(myWant)에 사용된다.
 * 영구 멱등은 V012 부분 UNIQUE 인덱스 {@code uq_pin_events_pin_user_want} 가 보장한다 (D-19).</p>
 */
public interface PinEventRepository {

    /**
     * 이벤트 행 저장. 동일 {@code (pinId, userId, action=WANT)} 중복 시
     * {@link org.springframework.dao.DataIntegrityViolationException} 이 호출자에게 그대로 전파된다.
     * WantService 는 이를 catch 하여 멱등 처리(현재 상태 재조회)한다.
     */
    PinEvent save(PinEvent event);

    /**
     * 동일 {@code (pinId, userId, action)} 행을 삭제한다. 부분 UNIQUE 인덱스 정의상 0 또는 1 행만 존재한다.
     * WANT 취소 경로에서 사용한다 (멱등: 행이 없어도 예외 없이 0건 삭제로 처리).
     */
    void deleteByPinAndUserAndAction(Long pinId, Long userId, PinEventAction action);

    /**
     * 동일 {@code (pinId, userId, action)} 행 존재 여부.
     * WANT 토글 전 +1 / -1 분기 판단에 사용한다.
     */
    boolean existsByPinAndUserAndAction(Long pinId, Long userId, PinEventAction action);

    /**
     * 핀에 대한 WANT 이벤트 총 건수. {@code pins.want_count} 와 일치해야 한다 (도메인 invariant).
     * 정합성 검증/복구 도구에서 참조용으로 활용 가능.
     */
    int countWantByPinId(Long pinId);

    /**
     * 핀에 WANT 한 사용자 ID 목록. 알림 fan-out, 디버그 등에 사용.
     */
    List<Long> findWantVoterIdsByPinId(Long pinId);

    /**
     * 주어진 핀 ID 목록 중 {@code userId} 가 WANT 한 핀 ID 들의 집합.
     * {@code GET /pins} 목록 응답에서 N+1 회피용 batch 조회 (PinService.toSummaries 에서 호출).
     * 빈 컬렉션 입력 시 빈 Set 반환.
     */
    Set<Long> findMyWantPinIds(Collection<Long> pinIds, Long userId);
}
