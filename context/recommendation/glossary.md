# recommendation 용어 사전

| 용어 | 설명 |
|------|------|
| 룰렛 | "오늘 어디 갈까?" 위치 기반 랜덤 추천 기능 |
| 거리 범위 | 도보 1km / 대중교통 5km / 드라이브 10km 세 단계 |
| 후보 필터 | 기본: `tag=PLACE` 핀만. 사용자 옵션으로 MEMORY 포함 가능 |
| 브라우저 위치 권한 | navigator.geolocation 권한. 거부 시 룰렛 사용 불가 |
| 범위 확장 유도 | 현재 범위 내 후보 0건 시 다음 범위로 자동 안내 (예: 1km → 5km) |
| 추천 결과 카드 | 장소명, 거리, 메모, 태그를 표시하는 결과 UI |
| Haversine | 위경도 → 두 지점 간 거리 계산 공식. PostGIS 미사용 |
| Bounding Box 1차 필터 | DB 인덱스 활용을 위해 `latitude BETWEEN ? AND ?` 으로 1차 좁힘 후 Haversine 적용 |
