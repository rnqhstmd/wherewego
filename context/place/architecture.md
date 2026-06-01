# place 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조 (Phase 2.5 — 현재 구현)

```
[URL] → ContentParser 디스패치 (MVP: InstagramParser만)
                          │
                          ▼
                HTML 메타 스크래핑 (og:title/og:description)
                  feature flag: place.instagram.scraping-enabled
                          │
                          ▼
              CaptionCleaner: likes/comments 앞부분 제거
              regex `/:\s*"(.+)"\.?\s*$/` DOTALL
                          │
                          ▼
              **ctx.expired() 가드** (Gemini 호출 전 컷오프)
                          │
                          ▼
              GeminiPlaceClient: Gemini 2.0 Flash 호출
              `x-goog-api-key` 헤더 인증, timeout 3000ms
              CAPTION_MAX_LENGTH=500 절단 (비용/인젝션 가드)
                          │
                ┌─────────┴─────────┐
                ▼                   ▼
        성공: 장소명 1개        empty: 타임아웃/429/응답 "null"
                │                   │
                │                   ▼
                │           "장소를 찾지 못했어요" 폴백 ([[chatbot]])
                ▼
        Kakao Local 검색 (동기, 5초 SLA 데드라인 가드)
        좌표 누락 문서 필터링 (NOT NULL 위반 방지)
                │
        ┌───────┴───────┬───────────┐
        ▼               ▼           ▼
     1건            복수(≤5)       0건
        │               │           │
        │               │           ▼
        │               │   Google Places 비동기 폴백 (Phase 5)
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
- **5초 SLA**: `ChatbotContext.deadlineMs=4500`. `InstagramScraperClient`/Gemini/`KakaoLocalClient` 진입 직전 각각 컷오프 검사. 초과 시 폴백 SimpleText.
- **Gemini API 키 보안**: `application.yml`의 `place.scraper.gemini.api-key=${GEMINI_API_KEY}` 환경변수 참조. `x-goog-api-key` 헤더 인증 (URL query param 미사용 → 로그 노출 차단).
- **멀티 플랫폼 확장 구조**: `ContentParser` 인터페이스 + `InstagramParser` 구현체. TikTok/YouTube 추가 시 새 구현체만 등록.
- **캐시**: `placeSelectionCandidate`(10m, 리스트 카드 선택 1회 사용), `twoSecondMemo`(2s, AUTO 메모 부착). 핀 중복 방지(`UNIQUE(group_id, instagram_url)`)로 동일 URL 재처리는 자연스럽게 차단.
- **Feature flag**: `place.instagram.scraping-enabled=false`이면 스크래핑 시도 없이 즉시 폴백 (인스타 법무 미승인/IP 차단 사고 시 안전망). 운영 초기값 `true` (베타 2명 규모).

## 이전 구현 (Phase 2 — regex, 폐기됨)

regex 방식의 추출 성공률 한계(~30%)로 인해 Phase 2.5에서 폐기되었다.

```
[Phase 2 — regex (폐기)]
og:description
  → MetaExtractor
  → PlaceNameExtractor (📍 이모지 → 키워드 → 해시태그 regex 3종)
  → Optional<ExtractionResult>
```

이력 보존을 위해 `backend/spike/instagram-meta-scraper/`에 코드 사본이 남아있다.
상세 전환 명세: [[gemini-migration]] (구현 완료, PR [#15](https://github.com/rnqhstmd/wherewego/pull/15)).

## 주제 문서

| 주제 | 설명 |
|------|------|
| [Gemini 2.0 Flash 전환](gemini-migration.md) | Phase 2.5 PlaceNameExtractor → Gemini API 교체 명세 |

## iOS 네이티브 소비 (P4, [PR #91](https://github.com/rnqhstmd/wherewego/pull/91))

iOS 지도 검색이 `GET /api/v1/places/search?q=` 를 `PlaceAPI`(Swift)로 소비. `PlaceItem`(placeName/address?/latitude/longitude) 디코딩 → 검색 결과 목록 → 태그 선택 → [[pin]] 등록. 검색어 인코딩은 값 전용 문자셋(`urlQueryAllowed`에서 `=&+#?` 제외)으로 쿼리 파라미터 인젝션을 방어(보안 감사 HIGH 반영). 백엔드 계약 변경 없음.
