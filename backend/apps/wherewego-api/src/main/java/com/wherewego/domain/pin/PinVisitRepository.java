package com.wherewego.domain.pin;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 핀 방문 기록 port(정책 v2). {@code pin_visits} 접근을 도메인 인터페이스로 노출한다(PinRepository 선례).
 */
public interface PinVisitRepository {

    PinVisit save(PinVisit visit);

    /**
     * 방문 upsert 결정용 단건 조회 — 핀 비관 락 안에서 호출되므로 별도 락이 불필요하다.
     */
    Optional<PinVisit> findByPinIdAndUserId(Long pinId, Long userId);

    /**
     * 핀 목록/단건 응답 visitors[] 합성용 배치 조회(registered 파생 동형 — 페이지당 IN 1회).
     * {@code pinIds} 가 비어 있으면 빈 리스트(쿼리 생략은 호출자 책임).
     */
    List<PinVisit> findByPinIdIn(Collection<Long> pinIds);
}
