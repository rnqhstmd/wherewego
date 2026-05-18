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
  - ⋮ 펼침 영역: 세그먼트 탭(태그/메모) + HLine 분리 + 우측 정렬 "삭제" 텍스트 버튼 (Phase 2.8, `colors.pinNew`)
  - 삭제 흐름: `onRequestDelete` → MapClient에서 `PinDeleteConfirm` 재사용 → 확인 시 `useOptimistic({kind:"remove",pinId})`로 마커 즉시 제거 + 팝업 닫힘 (QE-1)
  - 삭제 실패 시: useOptimistic transition 종료 시 자동 롤백 → 핀별 `deleteErrorByPinId` 키 맵으로 인라인 에러 표시
  - **방문 체크 버튼 제거** (visited 폐기)
- `useOptimistic` reducer 일반화 (Phase 2.8):
  - `{kind:"patch", pinId, patch} | {kind:"remove", pinId}` 판별 유니온
  - 태그/메모 갱신은 patch, 삭제는 remove로 처리하여 마커 인스턴스 캐시 유지 + 즉시 시각 반영
  - `revalidatePath("/map")` 미호출 (mapbox-gl 재마운트 회피, MUST-1). `revalidatePath("/pins")`만 try/catch로 fail-safe 호출하여 양쪽 라우트 정합성 확보
- 검색 UX: 입력 → 백엔드 → 카카오/Google → 결과 JSON → 커스텀 드롭다운 → 선택 → 태그 선택 → Mapbox 마커 추가
- 멤버별 핀 구분: 안 함 (정보창 내 created_by 표시만)

## 주제 문서

| 주제 | 설명 |
|------|------|
| gl-migration-plan | DOM Marker → GL symbol layer 전환 시 변경 지점 사전 분석 (Phase 2.9) |
