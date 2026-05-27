package com.wherewego.domain.pin.want;

/**
 * Phase 12: WANT 상태 조회 결과 DTO. {@code GET /api/v1/groups/{gid}/pins/{pid}/want} 응답.
 *
 * @param wantCount 현재 핀의 want_count
 * @param myWant    조회자 본인의 WANT 누름 여부
 */
public record WantStatusResult(int wantCount, boolean myWant) {
}
