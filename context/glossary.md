# 공통 용어 사전

> 도메인을 가리지 않고 프로젝트 전체에서 쓰이는 용어입니다.
> 도메인별 용어는 `context/{도메인}/glossary.md`를 참조하세요.

| 용어 | 설명 |
|------|------|
| wherewego / 우리가갈지도 | 서비스명. 커플(또는 그룹)이 데이트 장소·추억을 아카이빙하는 글로벌 3D 지도 서비스 |
| Group | 핀/메모/태그를 공유하는 사용자 묶음. MVP에서는 2인 커플이지만 N인 확장 가능 구조 |
| Couple | `group.size == 2`인 특수 케이스. MVP 단계의 기본 형태 |
| 핀 (Pin) | 지도 위에 저장된 장소 1건. 핵심 컬럼: `group_id`, `place_name`, `latitude`, `longitude`, `tag`, `memo`, `instagram_url` |
| 태그 (Tag) | 핀의 카테고리. V006(Phase 7) 이후 3종: `REEL`·`WISH`·`MEMORY`. 지도에서 색상·형태로 시각화. 도메인별 상세 → [context/tag/glossary.md](tag/glossary.md) |
| REEL 태그 | "발견" 카테고리. 챗봇(릴스 공유) 경로 자동 등록 시 기본값. 색상: `#C5B4E3` (연보라, 인스타 아이콘) |
| WISH 태그 | "설렘" 카테고리. 웹에서 "가보고 싶은 곳"으로 직접 저장 시 선택. 색상: `#A8E6CF` (민트, 동그라미) |
| MEMORY 태그 | "추억" 카테고리. 웹에서 "다녀온 의미 있는 곳"으로 직접 저장 시 선택. 색상: `#FFB3C6` (핑크, 하트) |
| 위경도 (lat/lng) | 지도 표시에 필요한 핵심 좌표. 카카오는 `x=경도/y=위도`, Google은 `lat/lng` |
| Skill Webhook | 카카오 i 오픈빌더가 Spring Boot REST API로 보내는 챗봇 이벤트 |
| 2초 룰 | 챗봇에 링크 수신 후 2초 이내 들어온 텍스트만 메모로 저장하는 정책 |
| AMOU 스타일 | 핀 정보창 UI 디자인 참조 (장소명 + 메모 + 릴스 바로가기) |
| 3D 지구본 모드 | Mapbox 줌 아웃 시 평면 지도가 구체로 전환되는 모드 |
| 룰렛 | "오늘 어디 갈까?" 기능. 거리 범위 내 핀 중 랜덤 1곳 추천 |
| 핀 공유 카드 (Pin Share Card) | 핀 한 건을 1080×1350(4:5) PNG 카드로 클라이언트 Canvas에서 합성하여 외부 공유(클립보드 복사 또는 파일 다운로드)에 사용하는 형식. 좌하단 "우리가갈지도" 워터마크로 브랜드 노출 (Phase 9 도입) |
| Mapbox Static Images API | Mapbox가 제공하는 지도 PNG 정적 이미지 API (`api.mapbox.com/styles/v1/{style}/static/...`). access token + 좌표/줌으로 호출. Phase 9 카드 배경에 사용 |
