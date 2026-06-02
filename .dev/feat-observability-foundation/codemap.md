## 코드 맵: Phase 2.11 observability foundation

> Phase 1 (가시성): MDC RequestId, 공통 구조화 로그, Google Places 메트릭+캐시, 일별 로그 회전+90일 보관
> Phase 2 (임계값 알림): 일일 합계 스케줄러, Instagram 차단 감지, Slack 본문 RequestId 동봉

### 핵심 파일 (수정/신규 대상)
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/notify/slack/SlackNotifier.java — Slack Incoming Webhook Block Kit 3-tier 알림 (FR-OBS-12 RequestId 동봉 대상)
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/gemini/GeminiUsageMetrics.java — Gemini Micrometer Counter/Timer (FR-OBS-8 Google Places 메트릭의 패턴 원형)
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/gemini/GeminiUserQuotaService.java — Caffeine 일일 쿼터 (FR-OBS-3 완료 — 복제 참조용)
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/gemini/GeminiResponseCacheService.java — SHA-256 응답 캐시 (FR-OBS-9 Google Places 캐시의 패턴 원형)
- backend/apps/wherewego-api/src/main/java/com/wherewego/config/cache/CacheConfig.java — Caffeine Cache Manager (FR-OBS-9 캐시 등록 추가 위치)

### 참조 파일 (호출 지점/관련 로직)
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/place/google/GooglePlacesClient.java — Google Places 호출 (FR-OBS-7/8/9 적용 대상)
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/chatbot/callback/KakaoCallbackClient.java — Kakao Callback 호출 (FR-OBS-7 공통 로그 적용)
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/scraper/instagram/InstagramScraperClient.java — Instagram 3-stage 폴백 (FR-OBS-11 차단 감지 대상)
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/place/PlaceFallbackOrchestrator.java — Slack 호출 지점 3곳 (RequestId 전파 필요)
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/chatbot/ChatbotRateLimitFilter.java — 5분 쿨다운 패턴 (FR-OBS-10 스케줄러 쿨다운 차용)
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/ApiControllerAdvice.java — GlobalExceptionHandler (RequestId 로깅 대상)

### 설정
- backend/apps/wherewego-api/src/main/resources/application.yml — Slack URI, Gemini quota, logging 설정 (FR-OBS-13 logging.file/rollingpolicy 추가)
- backend/supports/monitoring/src/main/resources/monitoring.yml — Actuator + Prometheus 설정 (FR-OBS-1 완료, 변경 없음)
- .github/workflows/deploy.yml — docker run 옵션 (FR-OBS-13 volume mount + json-file rotation 추가)

### 참조 산출물
- context/observability/README.md — PRD 초안 (Phase 2.11 배경/규모/임계값/성공 기준)
- context/observability/architecture.md — 설계 초안 (수집/적재/시각화/알림 4-레이어)
- context/observability/status.md — 요구사항 목록 (FR-OBS-6~13 + 후속 14~18)
- context/observability/glossary.md — 도메인 용어 (MDC, RequestId, outcome, TimeBasedRollingPolicy 등)
- .dev/feat-observability-foundation/prd.md — 확정 PRD (Q1~Q3 반영)

### 추가 발견 (design-critic + architect)
- backend/supports/logging/src/main/resources/logback/logback.xml — **로깅 설정 유일 진실원천**. application.yml `logging.config: classpath:logback/logback.xml`로 명시 로딩 → `logging.file.name`/`logging.logback.rollingpolicy.*` 무시됨. FR-OBS-13 변경 위치 (MUST-ADDRESS-3)
- backend/supports/logging/src/main/resources/logback/appenders/json-console-appender.xml — dev/prod JSON 콘솔 appender. 패턴에 `%X{requestId}` 추가 필요
- backend/supports/logging/src/main/resources/logback/properties/slack-log-dev.xml / slack-log-prod.xml — Logback 레벨 Slack appender (FR-OBS-12와 별개, SlackNotifier API와 분리)
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/place/PlaceSearchService.java — `GooglePlacesClient` 다른 호출 경로. FR-OBS-7/8/9 자동 적용 검증 대상
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/place/PlaceV1Controller.java — 웹 검색 호출자. 캐시 hit 비율 평가 대상
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/PendingInstagramAutoSaveScheduler.java — 별도 스케줄러 스레드. MDC 전파 추가 대상 (MUST-ADDRESS-1)
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/InstagramLinkHandler.java — `runAsync` 호출자. 비동기 MDC 전파 검증 지점
