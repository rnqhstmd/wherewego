## 코드 맵: Phase 2.11 PR-B — Google Places 메트릭/캐시 + Gemini 5xx 임계값 스케줄러 + Instagram 차단 감지

### 핵심 파일 (수정 대상)
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/place/google/GooglePlacesClient.java:31 → FR-OBS-8/9 대상. 현재 구조화 로그(`api/op/duration_ms/outcome/cache`)만 있고 Micrometer 메트릭/캐시 없음. outcome 상수 5종(success/empty/rate_limited/timeout/error) 정의되어 있음. classifyOutcome/isTimeout 유틸 존재
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/scraper/instagram/InstagramScraperClient.java:19 → FR-OBS-11 대상. 3-stage(NO_UA/CHROME_UA/FULL_HEADERS) 루프 + 최종 OUTCOME_BLOCKED 발생 지점. 1h �window 50% 임계 추적 훅 필요
- backend/apps/wherewego-api/src/main/java/com/wherewego/config/cache/CacheConfig.java:15 → 신규 캐시 등록 지점. `GEMINI_RESPONSE_CACHE`와 동일 패턴(24h, maximumSize 2000)으로 `googlePlacesResponseCache` 추가
- backend/apps/wherewego-api/src/main/resources/application.yml:98 → `google.places.*` properties 확장. 신규 monitoring/threshold 키 추가 위치

### 핵심 파일 (신규 생성)
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/place/google/GooglePlacesMetrics.java → `GeminiUsageMetrics` 복제. `google_places.calls.total{outcome}` Counter + `google_places.call.duration{outcome}` Timer
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/place/google/GooglePlacesResponseCacheService.java → `GeminiResponseCacheService` 복제. SHA-256(keyword+filter) → 24h Caffeine. inner Optional 패턴 동일
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/monitoring/ThresholdMonitorScheduler.java (신규) → `@Scheduled` 시간 단위. Gemini 5xx 10%(1h) 임계값 체크 + 5분 쿨다운 `ConcurrentMap<String,AtomicLong>`. 실패해도 swallow + log.warn
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/scraper/instagram/InstagramBlockedRateTracker.java (신규) → 고정 1h 윈도우. attempts/blocked 카운트, 호출 0건 시 알림 생략, 50% 임계값 → `SlackNotifier.notifyFailure`

### 참조 파일 (복제 패턴)
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/gemini/GeminiUsageMetrics.java:31 → outcome 라벨 Counter/Timer 패턴 원본
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/gemini/GeminiResponseCacheService.java:21 → SHA-256 키 + Caffeine cache get/put 패턴 원본
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/gemini/GeminiPlaceClient.java:158 → metrics.recordCall/Duration + 캐시 hit/put + finally 구조화 로그의 통합 호출 순서 (try-catch 8단계 분기)
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/notify/slack/SlackNotifier.java:32 → 알림 발송 진입점. webhookUri 빈문자 no-op, 모든 예외 swallow. notifyFailure/notifyWarning 메서드 사용
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/PendingInstagramAutoSaveScheduler.java:27 → MDC `SCHEDULER` 마커 + try-catch swallow + @PreDestroy shutdown 패턴 참조

### 참조 파일 (호출자/의존성)
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/place/PlaceFallbackOrchestrator.java → GooglePlacesClient 호출자. 비동기 워커 MDC 캡처 패턴
- backend/apps/wherewego-api/src/main/java/com/wherewego/config/security/RequestIdFilter.java → MDC_KEY 상수. 스케줄러에서 참조
- backend/apps/wherewego-api/src/main/java/com/wherewego/config/security/RequestIdFilterConfig.java → `@EnableScheduling` 활성 위치 (이미 켜져 있음)

### 설정
- backend/apps/wherewego-api/src/main/resources/application.yml:113 → `slack.*` 설정. webhook-uri 빈문자열이면 no-op
- backend/modules/jpa/src/main/resources/jpa.yml → DB 설정 (PR-B 무관, 참고용)
- context/observability/status.md:21-24 → FR-OBS-8/9/10/11 요구사항 원문
- docs/operations/slack-alerts.md:129 → ThresholdMonitorScheduler 쿨다운 키 구조(`google_places.80`/`gemini.5xx`) 사전 정의

### 도메인 컨텍스트
- context/observability/README.md, architecture.md, glossary.md → PR-B 도메인 PRD/4-레이어 설계/용어
- context/place/status.md:22-23 → FR-PLC-9/10 (FR-OBS-8/9/11과 cross-link)
