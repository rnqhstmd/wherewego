package com.wherewego.domain.pin;

import java.util.List;

/**
 * 방문 선언({@link PinVisitService#declareVisit}) 응답(정책 v2, FR-B2/B3).
 *
 * @param converted        이번 호출이 WISH/REEL → MEMORY 전환을 발생시켰으면 {@code true}(동행/1인 그룹 혼자).
 *                         체크인(다인 그룹 혼자)이거나 이미 MEMORY 였으면 {@code false}.
 * @param alreadyConverted 동행 선언인데 핀이 이미 MEMORY 였으면 {@code true}(늦은 제출 — visits union 만, 카드 미적재).
 *                         클라이언트 합산 토스트 분기(FR-I4)에 사용한다.
 * @param visitors         이 핀의 현재 방문자 전체(union 반영 후, GP-1 프사 resolver 적용).
 */
public record DeclareVisitResult(
        boolean converted,
        boolean alreadyConverted,
        List<PinVisitorResult> visitors
) {
}
