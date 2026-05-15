# map 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

```
[Next.js / Vercel]
   │
   ├─ Mapbox GL JS (3D globe + 커스텀 마커 렌더)
   ├─ Tailwind 커스텀 검색 드롭다운
   └─ Framer Motion (정보창 애니메이션)
        │
        ▼
   Spring Boot REST API
        │
        ├─ GET /api/pins?groupId=…&tag=…  ([[pin]])
        ├─ GET /api/places/search?q=…     ([[place]])
        └─ POST /api/pins                 ([[pin]] + [[tag]] 선택)
```

- 데이터 흐름: 초기 진입 시 그룹의 모든 핀을 한 번에 받아 마커로 렌더
- 마커 표현 ([[tag]] 도메인 참조):
  - PLACE → 파란 파스텔 동그라미
  - MEMORY → 핑크 파스텔 하트
  - visited 별도 표시 없음 (PRD 정책 변경으로 제거)
- 정보창 (AMOU 스타일):
  - 장소명, 메모, 원본 릴스 바로가기, 태그 변경 (PLACE ↔ MEMORY)
  - **방문 체크 버튼 제거** (visited 폐기)
- 검색 UX: 입력 → 백엔드 → 카카오/Google → 결과 JSON → 커스텀 드롭다운 → 선택 → 태그 선택 → Mapbox 마커 추가
- 멤버별 핀 구분: 안 함 (정보창 내 created_by 표시만)

## 주제 문서

| 주제 | 설명 |
|------|------|
