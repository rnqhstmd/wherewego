package com.wherewego.domain.pin;

/**
 * 방문 기록 출처(정책 v2). pin_visits.source 컬럼 매핑.
 * <ul>
 *     <li>SELF   : 본인이 직접 방문을 선언(체크인/동행 선언 시 본인).</li>
 *     <li>TAGGED : 동행자가 명단에 포함시켜 기록됨. 이후 본인이 직접 체크인하면 SELF 로 승격된다(역방향 강등 없음).</li>
 * </ul>
 */
public enum VisitSource {
    SELF,
    TAGGED
}
