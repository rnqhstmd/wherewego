# place 용어 사전

| 용어 | 설명 |
|------|------|
| 카카오 로컬 API | 국내 장소 키워드 검색 API. 응답: `x`(경도), `y`(위도) |
| Google Places Text Search | 해외 장소 폴백 API. `language=ko` 적용. `geometry.location.lat/lng` |
| og:title / og:description | 인스타 페이지 HTML 메타 태그. 캡션이 보통 `og:description`에 들어 있음 |
| 📍 이모지 우선 추출 | 캡션 내 핀 이모지 뒤 텍스트를 1순위 장소명 후보로 사용 |
| 위치/장소 키워드 | 2순위 추출 규칙. ❓ (정확한 키워드 목록 미정의) |
| 해시태그 폴백 | 3순위 추출 규칙. `#장소명` 형태에서 추출 |
| 검색 결과 분기 | 1건 → 자동 등록 / 복수 → 리스트 카드 / 0건 → 폴백 메시지 |
| 국내/해외 분기 | 카카오 로컬 결과 있음=국내(무료), 없음=해외(Google 폴백) |
| ContentParser | URL 종류별 파싱 추상화 인터페이스. MVP는 InstagramParser만 구현, 추후 TikTok/YouTube 등 확장 가능 |
