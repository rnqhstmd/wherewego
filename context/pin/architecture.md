# pin 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

- 테이블: `pins (id, group_id FK, place_name, address, latitude, longitude, instagram_url NULLABLE, memo, memo_source, tag, created_at, created_by FK)`
- 인덱스:
  - `UNIQUE(group_id, instagram_url) WHERE instagram_url IS NOT NULL` — 챗봇 중복 방지
  - `INDEX(group_id, tag)` — 지도 렌더링 시 그룹별 + 태그별 필터
  - `INDEX(group_id, latitude, longitude)` — [[recommendation]] Bounding Box 1차 필터
- 거리 계산: **Haversine 공식 (애플리케이션 레벨)**. PostGIS 미도입
- 도메인 협력:
  - 입력: [[place]] 결과(좌표·이름) + [[memo]] 정책 + [[chatbot]] 또는 [[map]] 검색 UI + [[tag]] 카테고리
  - 출력: [[map]] 렌더링, [[recommendation]] 랜덤 선정

## 주제 문서

| 주제 | 설명 |
|------|------|
