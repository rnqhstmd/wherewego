# map 용어 사전

| 용어 | 설명 |
|------|------|
| Mapbox GL JS | WebGL 기반 지도 라이브러리. 3D 지구본 모드 지원 |
| 3D 지구본 모드 | 줌 아웃 시 평면 지도가 구체(globe)로 전환 |
| 파스텔 마커 | 태그별 색상 마커. PLACE=파란 동그라미 / MEMORY=핑크 하트 ([[tag]] 참조) |
| 정보창 (Popup) | 마커 클릭 시 표시되는 카드. 가게 이름 + 메모 + 릴스 바로가기 + 태그 변경 |
| AMOU 스타일 | 참조 디자인. 감성적·미니멀 정보창 |
| 커스텀 검색 UI | Tailwind CSS로 직접 만든 검색 드롭다운. 카카오/Google API는 데이터 소스로만 사용 |
| Mapbox loads | 한 페이지 로드당 1 load 카운트. 월 50k 무료 |
| ~~방문 체크 버튼~~ | **제거됨**. visited 정책 폐기 |
| VisitToast (Phase 10) | 방문 감지 확정 시 화면 정중앙에 띄우는 PinPopup 스타일 카드. max-width 380px, "함께 방문하셨나요?" 카피 + 메모 미리보기 + CTA 2종 |
| VisitMemoSheet (Phase 10) | MEMORY 전환 후 메모 입력 바텀시트. "🌸 다녀온 흔적" + "다녀온 날 · YYYY.MM.DD" |
| triggerVisitCelebration (Phase 10) | `MapboxView`가 `forwardRef` + `useImperativeHandle`로 노출하는 명령형 트리거. 호출 시 마커 bounce 900ms + 6개 하트 confetti 1000ms 실행 |
| BBox prefilter (Phase 10) | Haversine 호출 전 ±LAT_DEG_PER_METER × PROXIMITY_METERS 박스로 후보 핀 1차 필터링. 1000핀 환경에서 거리 계산을 99% 컷 |
| 5초 자동 폴링 (Phase 10) | 권한 `granted` 시 `setInterval`로 5초마다 `getCurrentPosition` 호출. Mapbox `geolocate` 콜백이 희소하게 발생하는 환경 보완. visibilitychange로 hidden 진입 시 일시 정지 |
