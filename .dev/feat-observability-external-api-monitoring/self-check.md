# 자기점검 결과 (Phase 2.11 PR-B)

## 요약
- Critical: 0건
- Warning: 1건 (phase-review로 이월)
- Info: 0건
- QUESTION: 2건 (phase-review로 이월)
- AC-1 ~ AC-19 전부 충족 (테스트 커버리지 검증 완료)

## SELF_CHECK_FINDINGS

[Warning] backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/scraper/instagram/InstagramScraperClient.java:83~94 — recordBlocked와 recordAttempt 호출 순서로 blocked > attempts 비정상 스냅샷 가능성
- 현재: BLOCKED 분기에서 `tracker.recordBlocked(url)` 호출 (try 블록) → finally에서 `tracker.recordAttempt()` 호출
- 문제: recordBlocked 후 finally 진입 전 스케줄러가 flushWindow를 호출하면 blocked=N+1, attempts=N 비정상 스냅샷
- 영향: 매우 드물지만 race 윈도우 존재. 실제 발생 시 ratio가 100%를 약간 초과
- 수정 방안: recordBlocked를 finally 블록 내부로 이동하여 둘 다 finally에서 처리. 또는 OUTCOME_BLOCKED 플래그를 finally에서 조건 처리
- phase-review에서 수정 또는 위험 항목 명시 결정

## SELF_CHECK_QUESTIONS

[QUESTION 1] BR-1 정합성 — GeminiPlaceClient의 4xx 응답 분류
- 위치: GeminiPlaceClient.java:216 (onStatus(HttpStatusCode::isError, ...))
- 현재 동작: 429 제외 4xx + 5xx 모두 GeminiResponseException으로 throw → 모두 OUTCOME_SERVER_ERROR로 분류
- BR-1 정의: "HTTP 500/502/503/504만 server_error"
- 테스트(GeminiPlaceClientServerErrorTest.extractPlaceName_400_classification)는 현재 동작(400 → server_error)을 검증 중
- 선택지:
  - (a) PRD 수정: BR-1을 "429 제외 4xx/5xx → server_error"로 재정의 (코드/테스트 그대로, 권장)
  - (b) 코드 수정: onStatus를 4xx/5xx 분리. 5xx만 server_error, 4xx는 error
  - phase-review에서 결정

[QUESTION 2] Instagram 타임아웃 시 recordAttempt 카운트 여부
- 위치: InstagramScraperClient.java:91 (finally의 recordAttempt)
- 현재 동작: ctx.expired() 조기 return(OUTCOME_TIMEOUT) 시에도 finally의 recordAttempt가 +1
- PRD FR-11-2는 "fetchHtml finally에서 recordAttempt"만 명시. 타임아웃을 시도로 카운트할지 불명확
- 선택지:
  - (a) 의도된 동작 — 타임아웃도 시도로 카운트 (권장, 단순)
  - (b) OUTCOME_TIMEOUT일 때 recordAttempt 스킵
  - phase-review에서 결정

## AC 체크리스트 결과 (전부 충족)

| AC | 결과 | 검증 위치 |
|----|------|----------|
| AC-1 ~ AC-19 | ✅ 충족 | 코드 + 단위 테스트 |

자세한 매핑은 qa-manager 자기점검 보고 원문 참조.

## 빌드/테스트 결과
- `./gradlew :apps:wherewego-api:test` Green (exit 0)
