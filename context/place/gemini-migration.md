# 장소명 추출: regex → Gemini 2.0 Flash 전환

> Phase 2 다음 단계(Phase 2.5)에서 진행할 PlaceNameExtractor 교체 계획.

## 배경

Phase 2 현재 구현은 `PlaceNameExtractor`가 regex 3개 규칙을 우선순위로 적용한다:
1. `📍 이모지` 뒤 텍스트
2. `장소:`, `at @`, `in @` 키워드 뒤 텍스트
3. `#해시태그`

이 방식은 다음 한계가 있다:
- 자유 형식 캡션(이모지/키워드/해시태그가 없는 일반 문장)에서 추출 불가
- 베타 100명 트래픽 기준 추출 성공률 ~30% 추정 (목표 ≥70%)
- 패턴 추가 시 regex가 복잡해지고 false positive 증가

## 결정

**Gemini 2.0 Flash API**로 캡션을 LLM에게 던지고 장소명 1개를 추출하는 방식으로 전환한다.

- 무료 티어: **1,500건/일** (베타 30건/일 대비 50배 여유)
- 유료 전환 시: 연간 $1~5 수준 (베타 100명 → 사용자 5,000명 기준)
- 평균 응답 시간: ~1.5초 (Kakao Local 동기 5초 SLA 안에 포함 가능)

## 변경 플로우

```
[변경 전 — Phase 2 현재]
og:description
  → MetaExtractor
  → PlaceNameExtractor (regex EMOJI_PIN / KEYWORD / HASHTAG)
  → Optional<ExtractionResult>

[변경 후 — Phase 2.5]
og:description
  → MetaExtractor
  → CaptionCleaner (likes/comments 앞부분 제거)
  → GeminiPlaceClient (API 호출)
  → Optional<String> placeKeyword
```

## 신규/변경 파일 (Phase 2.5)

| 파일 | 변경 유형 | 역할 |
|------|----------|------|
| `infrastructure/gemini/GeminiPlaceClient.java` | 신규 | Gemini 2.0 Flash REST API 호출, timeout 3000ms 기본 |
| `infrastructure/scraper/instagram/CaptionCleaner.java` | 신규 | og:description의 likes/comments 앞부분 정제 (regex `/:\s*"(.+)"\.?\s*$/`) |
| `infrastructure/scraper/instagram/PlaceNameExtractor.java` | 삭제 | spike 디렉토리에는 보존 (이력) |
| `domain/place/InstagramContentService.java` | 수정 | PlaceNameExtractor → GeminiPlaceClient + CaptionCleaner 주입 |
| `config/env/PlaceProperties.java` | 수정 | `Scraper.gemini(apiKey, timeoutMs)` record 추가 |
| `application.yml` | 수정 | `place.scraper.gemini.api-key=${GEMINI_API_KEY}` + `timeout-ms: 3000` |
| `.env` | 수정 | `GEMINI_API_KEY` 추가 |

## Gemini 호출 계약

**엔드포인트**: `POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={API_KEY}`

**프롬프트**:
```
다음은 인스타그램 게시물 캡션이야.
가게명 또는 장소명 하나만 추출해줘.
이모지, 설명 코멘트, 해시태그는 제외하고 이름만.
장소가 없으면 null 이라고만 답해.

캡션:
{caption}
```

**요청 body**:
```json
{
  "contents": [{ "parts": [{ "text": "{prompt}" }] }],
  "generationConfig": {
    "maxOutputTokens": 50,
    "temperature": 0
  }
}
```

**응답 파싱**: `candidates[0].content.parts[0].text` → trim → `"null"`이면 `Optional.empty()`.

## 에러 처리

| 상황 | 처리 |
|------|------|
| Gemini API 타임아웃(3s 초과) | `Optional.empty()` → 폴백 메시지 |
| 429 Rate Limit | `Optional.empty()` → 폴백 메시지 |
| 응답 "null" | `Optional.empty()` → 폴백 메시지 |
| HTML 스크래핑 실패 | 기존 동일 (Gemini 호출 전 early return) |

## 미결 사항

- **복수 장소 게시물**: Gemini가 첫 번째 장소명 1개만 반환하도록 프롬프트 고정. 추후 리스트 반환 필요 시 별도 스펙.
- **GEMINI_API_KEY 발급**: [Google AI Studio](https://aistudio.google.com/apikey) 에서 무료 발급. 운영 secret 관리는 카카오 Skill Secret과 동일 메커니즘.
- **유료 전환 트리거**: 1,500건/일 초과 시점 모니터링. 초과 시 Quota 알림 + 결제 활성화.

## 관련 ADR

- 이 결정은 별도 ADR 미작성. 향후 사용자 응답 품질에 따라 ADR 승격 가능.
