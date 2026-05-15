# place 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현

## 요구사항

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-PLC-1 | 인스타 HTML 메타 스크래핑 (og:title/og:description) | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) |
| FR-PLC-2 | regex 장소명 추출 (📍 → 키워드 → 해시태그 우선순위) | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — Phase 2.5에서 Gemini로 교체 예정 |
| FR-PLC-3 | Kakao Local API 1차 검색 | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — 5초 SLA 데드라인 가드 + 좌표 누락 필터링 |
| FR-PLC-4 | Google Places API 폴백 (language=ko) | ⬜ | Phase 5 이월 — 비동기 + 카카오 콜백 푸시 |
| FR-PLC-5 | 결과 1건/복수/0건 분기 처리 | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — sealed `PlaceSearchOutcome` Single/Multiple/Empty |
| FR-PLC-6 | 좌표 정규화 (lat/lng 통일) | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) |
| FR-PLC-7 | Gemini 2.0 Flash 장소명 추출 (regex 대체) | ⬜ | Phase 2.5 — [gemini-migration.md](gemini-migration.md) |
| FR-PLC-8 | `INSTAGRAM_SCRAPING_ENABLED` feature flag (즉시 무력화) | ⚠️ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — 재기동 토글, `@RefreshScope`는 Phase 후속 |

## 후속 작업

- **Phase 2.5**: `PlaceNameExtractor`를 `GeminiPlaceClient` + `CaptionCleaner`로 교체 ([gemini-migration.md](gemini-migration.md))
- **Phase 5**: Google Places API 비동기 폴백 (FR-PLC-4)
- **Phase 후속**: `@RefreshScope` + Spring Cloud Config (FR-PLC-8 즉시 토글)
