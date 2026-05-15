# tag 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

- 저장 방식: `pins.tag` enum 컬럼 (`PLACE` | `MEMORY`). 별도 테이블 없음 (MVP 단순화)
- 입력 경로:
  - [[chatbot]] 자동 등록 → 항상 `PLACE`
  - [[map]] 검색 UI 직접 등록 → 사용자가 PLACE/MEMORY 선택 (라디오 또는 토글)
- 수정 경로: 핀 상세 화면에서 태그 변경 가능 ([[pin]] FR-PIN-4)
- 시각화:
  - PLACE → Mapbox custom marker: 파란 파스텔 동그라미 (Hex: ❓ 디자인 단계 확정)
  - MEMORY → Mapbox custom marker: 핑크 파스텔 하트 (Hex: ❓ 디자인 단계 확정)
- 향후 확장: 그룹 커스텀 태그(예: "맛집", "카페")는 v2에서 검토. 도입 시 `tag_definitions` 테이블 + `pin_tags` 매핑(N:M)으로 마이그레이션

## 주제 문서

| 주제 | 설명 |
|------|------|
