# pin 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

- 테이블: `pins (id, group_id FK, created_by FK, place_name, address, latitude, longitude, instagram_url NULLABLE, memo, memo_source, tag, created_at, updated_at, deleted_at)`
  - `CONSTRAINT chk_pins_tag CHECK (tag IN ('PLACE', 'MEMORY'))`
  - `CONSTRAINT chk_pins_memo_source CHECK (memo_source IN ('AUTO', 'MANUAL'))`
- 인덱스:
  - `UNIQUE(group_id, instagram_url)` — 챗봇 중복 방지. PostgreSQL 표준 동작으로 NULL distinct 처리 → instagram_url IS NULL 행(직접 등록)은 중복 허용, 비NULL만 차단
  - `INDEX(group_id, deleted_at)` — 기본 그룹 핀 조회
  - `INDEX(group_id, tag) WHERE deleted_at IS NULL` — 지도 렌더링 시 그룹별 + 태그별 필터
  - `INDEX(group_id, latitude, longitude) WHERE deleted_at IS NULL` — [[recommendation]] Bounding Box 1차 필터
- 거리 계산: **Haversine 공식 (애플리케이션 레벨)**. PostGIS 미도입
- 웹 CRUD API (Phase 4 도입):
  - `GET /api/v1/groups/{groupId}/pins?tag=PLACE|MEMORY` — 활성 그룹원 목록 조회 (`deleted_at IS NULL`, `created_at` 내림차순). 페이지네이션 미적용
  - `PATCH /api/v1/groups/{groupId}/pins/{pinId}` — 부분 수정. `memo`/`tag` 각각 독립 갱신, 둘 다 미전달이면 400. JSON 본문은 `JsonNode`로 받아 "키 없음 vs JSON null vs 빈 문자열" 구분. 빈 문자열 memo는 [[memo]] 잠금 해제 정책 트리거
  - `DELETE /api/v1/groups/{groupId}/pins/{pinId}` — 소프트 삭제, 204 No Content. `BaseEntity.delete()` 멱등
  - 권한: **활성 GroupMember 전체**(등록자 검사 없음). 비활성 시 403 `GROUP_NOT_MEMBER`
  - 동시성: `PESSIMISTIC_WRITE` 비관 락(`findActiveByIdAndGroupIdForUpdate`)으로 PATCH-DELETE 경합 직렬화
- 도메인 협력:
  - 입력: [[place]] 결과(좌표·이름) + [[memo]] 정책 + [[chatbot]] 또는 [[map]] 검색 UI + [[tag]] 카테고리
  - 출력: [[map]] 렌더링, [[recommendation]] 랜덤 선정, 웹 UI `/pins` 목록·수정·삭제

## 주제 문서

| 주제 | 설명 |
|------|------|
