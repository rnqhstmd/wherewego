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
| FR-PLC-4 | Google Places API 폴백 (language=ko) | ✅ | [#11](https://github.com/rnqhstmd/wherewego/pull/11) — 동기/비동기 자동 분기 + 카카오 콜백 푸시 + SSRF 가드 |
| FR-PLC-5 | 결과 1건/복수/0건 분기 처리 | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — sealed `PlaceSearchOutcome` Single/Multiple/Empty |
| FR-PLC-6 | 좌표 정규화 (lat/lng 통일) | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) |
| FR-PLC-7 | Gemini 2.0 Flash 장소명 추출 (regex 대체) | ✅ | [#15](https://github.com/rnqhstmd/wherewego/pull/15) — `GeminiPlaceClient` + `CaptionCleaner` 신규, `PlaceNameExtractor` 삭제. `x-goog-api-key` 헤더 인증 + 캡션 500자 가드 + Gemini 호출 직전 데드라인 가드 |
| FR-PLC-8 | `INSTAGRAM_SCRAPING_ENABLED` feature flag (즉시 무력화) | ✅ | [#18](https://github.com/rnqhstmd/wherewego/pull/18) — Phase 2.6 PR-B: `PlaceProperties` `@RefreshScope` + Actuator `/refresh` (localhost 제한). record→class 전환으로 CGLIB 호환. `GeminiPlaceClient` sub-record 캡처 제거로 매 호출 재평가 |

## 후속 작업

- **Phase 2.5 완료**: `PlaceNameExtractor` → `GeminiPlaceClient` + `CaptionCleaner` 교체 ([#15](https://github.com/rnqhstmd/wherewego/pull/15))
- **Phase 5 완료**: Google Places API 비동기 폴백 — [#11](https://github.com/rnqhstmd/wherewego/pull/11)
- **Phase 2.6 PR-B 완료**: `@RefreshScope` + Actuator `/refresh` (FR-PLC-8 즉시 토글) — [#18](https://github.com/rnqhstmd/wherewego/pull/18)
- **Phase 2.7 완료**: `GeminiPlaceClient` BASE_URL 외부화(`PlaceProperties.Gemini.baseUrl` + yaml `base-url` 기본값 보존) + WireMock 경량 단위 5케이스(200/null/429/500/timeout, 캐시 미적재 verify 포함) — [#20](https://github.com/rnqhstmd/wherewego/pull/20)
