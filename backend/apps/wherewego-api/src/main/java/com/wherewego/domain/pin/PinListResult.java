package com.wherewego.domain.pin;

import java.util.List;

/**
 * PinService.listGroupPinsPaged의 페이지 모드 전용 결과.
 * totalCount와 hasNext는 항상 유의미한 값을 가진다 (null 가능성 없음).
 */
public record PinListResult(List<PinSummary> items, long totalCount, boolean hasNext) {}
