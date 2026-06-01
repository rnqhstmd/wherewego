# recommendation 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

- 입력: 사용자 현재 좌표 (브라우저 geolocation) + 거리 범위 + group_id + (옵션) 태그 필터
- 처리:
  1. [[pin]] 테이블에서 `group_id` + (기본) `tag=PLACE` 조건으로 후보 조회
  2. Bounding Box 1차 필터 (위경도 범위, 인덱스 활용)
  3. Haversine 공식으로 거리 정확도 보정 → 반경 내 후보 확정
  4. 후보 중 랜덤 1건 선정
  5. 0건일 경우 다음 거리 범위로 확장 제안
- 거리 계산: **Haversine** (애플리케이션 레벨). PostGIS 미사용
- 추천 다양성: 직전 N회 추천된 핀을 클라이언트 측에 보관하고 가중치 낮추기 ❓ (MVP 후반 결정)

## iOS 네이티브 룰렛 (P4, [PR #91](https://github.com/rnqhstmd/wherewego/pull/91))

룰렛 추첨을 **클라이언트(iOS)에서 수행** — 백엔드 추천 API 없이 이미 로드된 핀 목록으로 추첨. `Roulette.swift`(순수 함수, RNG 주입으로 결정적 테스트)가 웹 `roulette.ts` 의 `pickRandomWithExpansion` 동치 이식: 현재 위치 기준 BBox 1차 필터 → Haversine 거리 보정 → 반경 내 후보 랜덤 1건, 0건 시 반경 확장. 기본 후보 태그 `[REEL, WISH]`(MEMORY 제외), "MEMORY 포함" 토글로 확장. "지도에서 보기" 시 flyTo(zoom15)+정보창, "다시" 재추첨(직전 핀 제외). 핀 목록 5분 캐시, 룰렛 직전 stale 시 재조회.

## 주제 문서

| 주제 | 설명 |
|------|------|
