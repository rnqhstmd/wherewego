# place 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조 (Phase 2 — 현재 구현)

```
[URL] → ContentParser 디스패치 (MVP: InstagramParser만)
                          │
                          ▼
                HTML 메타 스크래핑 (og:title/og:description)
                  feature flag: place.instagram.scraping-enabled
                          │
                          ▼
                  regex 장소명 추출
                  (📍 이모지 → 키워드 → 해시태그)
                          │
                ┌─────────┴─────────┐
                ▼                   ▼
        성공: 추출 텍스트       실패: 폴백 → "장소를 찾지 못했어요" ([[chatbot]])
                │
                ▼
        Kakao Local 검색 (동기, 5초 SLA 데드라인 가드)
        좌표 누락 문서 필터링 (NOT NULL 위반 방지)
                │
        ┌───────┴───────┬───────────┐
        ▼               ▼           ▼
     1건            복수(≤5)       0건
        │               │           │
        │               │           ▼
        │               │   "장소를 찾지 못했어요" 폴백
        │               │   (Phase 5: Google Places 비동기 폴백 예정)
        │               │
        ▼               ▼
   자동 등록     리스트 카드 응답
   (tag=PLACE,   (BasicCard, placeSelectionCandidate Caffeine 10m)
    memo_source=NULL)        │
        │                    ▼
        │            두 번째 webhook (action.params.placeId)
        │                    │
        └──────────┬─────────┘
                   ▼
        [[pin]] 등록 + twoSecondMemo 세션 put
        (Caffeine 2s, key=botUserKey)
```

- **좌표 정규화**: `{lat, lng}` 통일 (카카오 `y→lat`, `x→lng`). 좌표가 null인 문서는 결과에서 제외.
- **5초 SLA**: `ChatbotContext.deadlineMs=4500`. `InstagramScraperClient`/`KakaoLocalClient` 진입 직전 컷오프 검사. 초과 시 폴백 SimpleText.
- **멀티 플랫폼 확장 구조**: `ContentParser` 인터페이스 + `InstagramParser` 구현체. TikTok/YouTube 추가 시 새 구현체만 등록.
- **캐시**: `placeSelectionCandidate`(10m, 리스트 카드 선택 1회 사용), `twoSecondMemo`(2s, AUTO 메모 부착). 핀 중복 방지(`UNIQUE(group_id, instagram_url)`)로 동일 URL 재처리는 자연스럽게 차단.
- **Feature flag**: `place.instagram.scraping-enabled=false`이면 스크래핑 시도 없이 즉시 폴백 (인스타 법무 미승인/IP 차단 사고 시 안전망). 운영 초기값 `false`.

## Phase 2.5 — Gemini 2.0 Flash 도입 계획

regex 방식의 추출 성공률 한계(~30%)를 LLM 호출로 보완한다.

```
[Phase 2.5 차세대]
og:description
  → MetaExtractor
  → CaptionCleaner (likes/comments 앞부분 제거)
  → GeminiPlaceClient (Gemini 2.0 Flash, 1500건/일 무료)
  → Optional<String> placeKeyword
```

상세 명세: [[gemini-migration]] 참조.

## 주제 문서

| 주제 | 설명 |
|------|------|
| [Gemini 2.0 Flash 전환](gemini-migration.md) | Phase 2.5 PlaceNameExtractor → Gemini API 교체 명세 |
