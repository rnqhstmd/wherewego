# Trust Ledger — Phase 2.11 PR-B 1차 리뷰

## QA Manager (CERTAIN)

### Critical (1건)
- **C1** [SPEC] `InstagramScraperClient.java:83-87` + `InstagramBlockedRateTracker.java:643-646` — `recordBlocked(safeUrl)` 이중 sanitize 버그
  - 근거: `InstagramScraperClient`가 `safeForLog(url)`로 sanitize된 `safeUrl`을 tracker에 전달. `InstagramBlockedRateTracker.recordBlocked`가 내부에서 `safeForLog`를 다시 적용 → 정상 URL에서는 무영향이나 CRLF 포함 URL 시 의도 외 동작. 또한 테스트가 원본 `url`로 verify해 일관성 깨짐
  - 권고: `tracker.recordBlocked(url)`로 원본 전달. `safeForLog`는 로그 출력에만 사용

### Warning (5건)
- W1 `GooglePlacesClient.java` keyHash=null 동작(hashKey 예외 시) 주석 부족
- W2 `ThresholdMonitorScheduler.java` ConcurrentHashMap vs 단일 스레드 가정의 주석 명확화 필요
- W3 `GooglePlacesClient.java:128-139` `classifyOutcome`이 문자열 파싱으로 429 분기 — Spring 버전 변경에 취약
- W4 `InstagramScraperClientTest` AC-11 검증이 원본 URL로 verify (C1 수정 후 자연 해결)
- W5 `GooglePlacesClient.java:183-184` finally의 `recordCall → recordDuration` 순서가 `GeminiPlaceClient`(`recordDuration → recordCall`)와 반대 — 일관성

### Info (3건)
- I1 `GooglePlacesResponseCacheService.java:41` SHA-256 fallback `raw:` 폴백은 ZT HIGH와 중복
- I2 `ThresholdMonitorScheduler.java:303` 쿨다운 무관 주석 명확화
- I3 `GeminiPlaceClientServerErrorTest` 400 → server_error 동작이 BR-1과 어긋남 명시

### QUESTION (2건 — SELF_CHECK에서 이미 보고)
- Q1 `response == null` 분기 캐싱이 의도된 동작인가
- Q2 Instagram TIMEOUT 분기 테스트 누락 (recordAttempt 카운트 여부)

## Security Auditor (통합 감사)

### RISK / HIGH (3건)
- **H1** `GooglePlacesClient.java:112, 172` — keyword 로그 인젝션 (CRLF 미정제)
  - 권고: `safeForLog(keyword)` 처리 후 로그 출력
- **H2** `GooglePlacesResponseCacheService.java:41` — SHA-256 fallback `raw:` 키 충돌
  - 권고: `throw new IllegalStateException("SHA-256 unavailable", e)`로 대체
- **H3** `GeminiPlaceClient.java:216-218` — BR-1 위반: `onStatus(HttpStatusCode::isError, ...)`가 429 제외 4xx도 GeminiResponseException으로 throw → server_error 분류
  - 권고: 5xx 전용 핸들러(`HttpStatusCode::is5xxServerError`) 사용 또는 PRD를 "429 제외 4xx/5xx → server_error"로 재정의
  - **SELF_CHECK_QUESTIONS QUESTION 1과 동일** — 사용자 결정 필요

### GAP / HIGH (2건)
- **G1** `GooglePlacesClient.java` — `cachePut = true`가 try-catch 바깥에 있어 put RuntimeException 시에도 true → `cache=miss` 로그 오출력 (FR-9-6 위반)
  - 권고: `cachePut = true`를 `responseCache.put()` 호출 직후 try 블록 내부로 이동
- **G2** `ThresholdMonitorScheduler.java:60` — `@Scheduled(fixedRate = WINDOW_MS)` `initialDelay` 부재 → 서버 시작 직후 첫 tick에 과거 누적 카운터 전체가 delta로 잡혀 오탐 가능
  - 권고: `@Scheduled(fixedRate = WINDOW_MS, initialDelay = WINDOW_MS)` 또는 첫 tick skip 플래그

### POLICY / HIGH (1건)
- **P1** `GeminiPlaceClient.java:212-219` `onStatus` 체이닝 순서 — Spring RestClient 첫 매칭 핸들러 실행 동작에 의존. 코드 주석으로 명시 권고

### MISSING / HIGH (1건)
- **M1** `GooglePlacesClient.searchByKeyword():82` — keyword=null 시 `Map.of("textQuery", keyword, ...)` NPE 전파. NFR-3 격리 위반 가능
  - 권고: 진입부에 null/blank 가드 추가

### RISK / MEDIUM (1건)
- MD1 `MonitoringThresholdProperties.Gemini.cooldownMinutes` validation 부재. 0 설정 시 쿨다운 비활성화 → BR-4 위반

### GAP / MEDIUM (1건)
- MD2 Instagram 알림에 쿨다운 미구현 (Gemini만 적용)

### POLICY / MEDIUM (1건)
- MD3 `ThresholdMonitorScheduler` `final` 미적용 (InstagramBlockedRateTracker는 final 적용)

### ASSUMPTION / MEDIUM (2건)
- MD4 `@Scheduled` 단일 스레드 가정 — `application.yml`에 `spring.task.scheduling.pool.size=1` 명시 권고
- MD5 Counter deregistration 시 delta 불일치 가능 (현재 미발생, 후속 검토)

### QUESTION (2건)
- ZQ1 `GeminiPlaceClient`에 `@RefreshScope`가 실제 적용되어 있는가? (import만? 아니면 클래스에 @RefreshScope?)
- ZQ2 FR-11-1 PRD의 `AtomicLong` 명세와 코드의 `synchronized long` 불일치 — PRD 업데이트 필요?

## 통합 처리 분류

### Critical/HIGH 자동 수정 대상 (6건)
| 항목 | 위치 | 수정 |
|------|------|------|
| C1 (이중 sanitize) | InstagramScraperClient + InstagramBlockedRateTracker | tracker에 원본 url 전달, recordBlocked 위치 finally로 이동 (자기점검 W도 동시 해소) |
| H1 (keyword 로그 인젝션) | GooglePlacesClient | `safeForLog(keyword)` 적용 |
| H2 (SHA-256 fallback) | GooglePlacesResponseCacheService | `IllegalStateException` throw |
| G1 (cachePut=true 위치) | GooglePlacesClient | put 직후 try 블록 내부로 이동 |
| G2 (initialDelay) | ThresholdMonitorScheduler | `initialDelay = WINDOW_MS` 추가 |
| M1 (keyword=null NPE) | GooglePlacesClient | 진입부 null/blank 가드 |

### QUESTION (사용자 결정 필요, 3건)
- Q1+H3 (BR-1 4xx 분류): PRD 수정 vs 코드 수정
- Q2: Instagram TIMEOUT 시 recordAttempt 카운트 여부
- ZQ2: PRD의 `AtomicLong`을 `synchronized long`로 업데이트할지

### 보류/Medium (선택적, 자동 수정에서 제외)
- W3, MD3, MD4, MD5, P1, MD1, MD2 → phase-review 2회차 또는 후속 PR

### 미답변 QA QUESTION
(없음 — 모두 위 QUESTION 섹션에 포함)
