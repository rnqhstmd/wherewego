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
- 웹 CRUD API (Phase 4 도입, Phase 2.8 확장):
  - `GET /api/v1/groups/{groupId}/pins?tag=PLACE|MEMORY` — 활성 그룹원 목록 조회 (`deleted_at IS NULL`, `created_at` 내림차순). 페이지네이션 미적용
  - `PATCH /api/v1/groups/{groupId}/pins/{pinId}` — 부분 수정. `memo`/`tag`/`placeName`/`address` 각각 독립 갱신, 4개 모두 미전달이면 400 `PIN_UPDATE_EMPTY`. JSON 본문은 `JsonNode`로 받아 "키 없음 vs JSON null vs 빈 문자열" 구분. 빈 memo는 [[memo]] 잠금 해제, 빈 placeName은 `PIN_PLACE_NAME_INVALID`, 빈 address는 **미변경으로 안전 무시** (주소 제거 UX는 제공 안 함). Phase 2.8에서 `PinUpdateCommand`가 4-arg → 8-arg로 확장됨
  - `DELETE /api/v1/groups/{groupId}/pins/{pinId}` — 소프트 삭제, 204 No Content. `BaseEntity.delete()` 멱등. `/map`과 `/pins` 양쪽에서 호출
  - 권한: **활성 GroupMember 전체**(등록자 검사 없음). 비활성 시 403 `GROUP_NOT_MEMBER`
  - 동시성: `PESSIMISTIC_WRITE` 비관 락(`findActiveByIdAndGroupIdForUpdate`)으로 PATCH-DELETE 경합 직렬화
- **instagramUrl 보안 정책** (Phase 2.8):
  - 백엔드: `Pin.validateInstagramUrl(url)`이 모든 등록 진입점(`autoFromInstagram`/`fromSelection`/`createFromUser`)에서 `https://` 시작 필수 검증. 위반 시 `PIN_INSTAGRAM_URL_INVALID` (400). null/빈 문자열은 허용
  - 프론트엔드: `PinCard.tsx`에서 href 발행 전 `pin.instagramUrl?.startsWith("https://")` 조건부 렌더로 기존 DB 데이터에 대한 XSS/오픈 리다이렉션 이중 방어
- 도메인 협력:
  - 입력: [[place]] 결과(좌표·이름) + [[memo]] 정책 + [[chatbot]] 또는 [[map]] 검색 UI + [[tag]] 카테고리
  - 출력: [[map]] 렌더링, [[recommendation]] 랜덤 선정, 웹 UI `/pins` 목록·수정·삭제

## 주제 문서

| 주제 | 설명 |
|------|------|
