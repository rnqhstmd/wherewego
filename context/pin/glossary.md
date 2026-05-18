# pin 용어 사전

| 용어 | 설명 |
|------|------|
| Pin | 핀 엔티티. 컬럼: `id`, `group_id`, `place_name`, `address`, `latitude`, `longitude`, `instagram_url`(nullable), `memo`, `memo_source`(AUTO/MANUAL), `tag`(PLACE/MEMORY), `created_at`, `created_by` |
| group_id | 핀이 속한 그룹의 외래키. [[group]] 도메인 참조 |
| place_name | API 응답에서 가져온 상호명 (한국어) |
| address | API 응답 주소 (한국어) |
| latitude / longitude | 위경도. Mapbox 핀 표시에 필수 |
| instagram_url | 원본 릴스 URL. 챗봇 등록 시에만 채워짐. 웹 직접 등록은 nullable |
| tag | PLACE 또는 MEMORY. [[tag]] 도메인 참조. NOT NULL |
| memo_source | AUTO(챗봇 2초 룰) 또는 MANUAL(웹 직접 입력). [[memo]] 우선순위 정책 참조 |
| 중복 방지 | `UNIQUE(group_id, instagram_url) WHERE instagram_url IS NOT NULL`. 웹 직접 등록 핀은 중복 가능 |
| ~~visited~~ | **PRD에서 제거됨**. 방문 인증 기능 없음. 시각적 표현은 [[tag]]로 일원화 |
| PinListResult | Phase 2.9 — `PinService.listGroupPinsPaged`의 페이지 모드 전용 결과 record. 필드: `List<PinSummary> items`, `long totalCount`, `boolean hasNext`. 항상 유의미한 값(null 없음). 인터페이스 레이어에서 `PinListResponse.fromPaged()`로 응답 매핑 |
| legacy 모드 | Phase 2.9 — 핀 목록 API가 `page`/`size` 파라미터 미전달일 때 동작. 기존 `{items}` 단일 응답 구조 유지. 룰렛 stale 재조회 + `/pins` 초기 fetch가 사용 |
| 페이지 모드 | Phase 2.9 — 핀 목록 API가 `page`(0-based)와 `size`(최대 100)를 둘 다 전달받았을 때 동작. 응답에 `totalCount`/`hasNext` 추가. `hasNext = (long)(page + 1) * size < totalCount` (long 캐스팅으로 오버플로 방지) |
| PIN_PAGE_PARAM_INVALID | Phase 2.9 — `page`/`size` 부분 전달, 음수/0, 비숫자 입력 시 400 응답 코드 |
| PIN_PAGE_SIZE_EXCEEDED | Phase 2.9 — `size>100` 시 400 응답 코드 (MAX_PAGE_SIZE 상한) |
