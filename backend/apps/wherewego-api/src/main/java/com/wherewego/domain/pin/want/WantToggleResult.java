package com.wherewego.domain.pin.want;

import com.wherewego.domain.pin.PinTag;

/**
 * Phase 12: WANT 토글/INSERT-only 헬퍼의 결과 DTO. 도메인 서비스 응답이며,
 * 컨트롤러는 본 record 를 {@code PinV1Dto.WantToggleResponse} 로 변환하여 반환한다.
 *
 * @param tag           토글 결과 핀의 현재 태그 (WISH 전환 시 REEL → WISH)
 * @param wantCount     갱신 후 want_count
 * @param myWant        토글 결과 (방금 누른 경우 true, 취소한 경우 false; 헬퍼는 항상 true)
 * @param wishConverted 이번 호출이 REEL → WISH 를 트리거했으면 true
 */
public record WantToggleResult(PinTag tag, int wantCount, boolean myWant, boolean wishConverted) {
}
