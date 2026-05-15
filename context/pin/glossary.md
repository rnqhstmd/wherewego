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
