# 설계서: Phase 2.11 observability foundation

## 설계 규모

**중형(축소)**. CONSIDER 7건 반영으로 신규 클래스 10개 → **6개**, 신규 패키지 3개 → **0개**, 수정 12개. 횡단 변경(필터/logback/deploy.yml)이 여전해 PR-A 단독 배포 검증이 필요. 도메인 신규 비즈니스 로직은 없으며 Gemini 3종 세트 패턴 복제 기반.

---

## 전체 구조

### 새 패키지 구조

**신규 패키지 없음**. 기존 패키지에 동거시킨다 (CONSIDER-7).

```
com.wherewego/
├── config/
│   └── security/
│       ├── RequestIdFilter.java                # 신규 — FR-OBS-6
│       └── RequestIdFilterConfig.java          # 신규 — FilterRegistrationBean + @EnableScheduling + mdcTaskDecorator
├── infrastructure/
│   ├── notify/
│   │   ├── slack/SlackNotifier.java            # 수정 — MDC 자동 동봉
│   │   └── ThresholdMonitorScheduler.java      # 신규 — FR-OBS-10(Gemini만) + FR-OBS-11
│   ├── place/google/
│   │   ├── GooglePlacesClient.java             # 수정 — FR-OBS-7/9
│   │   └── GooglePlacesResponseCacheService.java # 신규 — FR-OBS-9
│   ├── scraper/instagram/
│   │   ├── InstagramScraperClient.java         # 수정 — FR-OBS-7/11
│   │   └── InstagramFailureTracker.java        # 신규 — AtomicInteger 2개
│   ├── chatbot/callback/
│   │   └── KakaoCallbackClient.java            # 수정 — FR-OBS-7
│   └── gemini/
│       └── GeminiPlaceClient.java              # 수정 — FR-OBS-7
└── config/cache/
    └── CacheConfig.java                        # 수정 — GOOGLE_PLACES_RESPONSE_CACHE 등록
```

### 데이터 흐름

```
HTTP 요청
   └─ RequestIdFilter (FilterRegistrationBean HIGHEST_PRECEDENCE)
        ├─ MDC.put("requestId", UUID.randomUUID().toString())
        └─ chain.doFilter → 응답 → finally MDC.clear()

@Scheduled 진입점
   └─ MDC.put("requestId", "SCHEDULER") + finally MDC.clear()

PlaceFallbackOrchestrator.runAsync (raw ThreadPoolExecutor)
   └─ 명시적 MDC.getCopyOfContextMap() 캡처 → 워커 setContextMap → finally clear

SlackNotifier.notifyXxx
   └─ send() 내부에서 MDC.get("requestId") 자동 읽기 → 본문 첫 키 prepend
```

---

## FR별 구현 설계

### FR-OBS-6: MDC RequestId 필터

**신규 1**: `config/security/RequestIdFilter.java`
```java
public final class RequestIdFilter extends OncePerRequestFilter {
    public static final String MDC_KEY = "requestId";
    public static final String RESPONSE_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) {
        String id = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);
        res.setHeader(RESPONSE_HEADER, id);
        try { chain.doFilter(req, res); } finally { MDC.clear(); }
    }
}
```
**MUST-2 반영**: 외부 헤더 무시. 항상 자체 발급.

**신규 2**: `config/security/RequestIdFilterConfig.java`
```java
@Configuration
@EnableScheduling
class RequestIdFilterConfig {
    @Bean RequestIdFilter requestIdFilter() { return new RequestIdFilter(); }

    @Bean FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration(RequestIdFilter f) {
        var reg = new FilterRegistrationBean<>(f);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }

    @Bean static TaskDecorator mdcTaskDecorator() {
        return runnable -> {
            Map<String, String> snapshot = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (snapshot != null) MDC.setContextMap(snapshot); else MDC.clear();
                try { runnable.run(); }
                finally { if (previous != null) MDC.setContextMap(previous); else MDC.clear(); }
            };
        };
    }
}
```

**테스트**: 응답 헤더 UUID, MDC put/clear, 연속 호출 격리(AC-1/2).

### FR-OBS-7: 외부 API 4곳 공통 구조화 로그

**CONSIDER-6 반영**: 별도 유틸 없이 4곳 직접 작성.

**공통 포맷**:
```java
log.info("api={} op={} duration_ms={} outcome={} cache={}",
        api, op, elapsed, outcome, cacheMarker);
```
logback 패턴 `[%X{requestId:-}]` 가 자동 prepend.

**적용**:
1. `GooglePlacesClient.searchByKeyword` — outcome: success/empty/cached/rate_limited/timeout/error, cache: hit/miss
2. `InstagramScraperClient.fetchHtml` — 3-stage 종료 후 1줄, cache=n/a
3. `KakaoCallbackClient.push` — cache=n/a
4. `GeminiPlaceClient.extractPlaceName/extractPlaceCandidates/extractPlaceNames` — 기존 metrics 옆 1줄 추가

**메서드 시그니처 변경 없음**.

### FR-OBS-9: Google Places Caffeine 응답 캐시

**신규**: `infrastructure/place/google/GooglePlacesResponseCacheService.java`
```java
@Component
public class GooglePlacesResponseCacheService {
    public String hashKey(String keyword, int size, String regionCode, String languageCode);
    public Optional<List<PlaceSearchHit>> get(String cacheKey);
    public void put(String cacheKey, List<PlaceSearchHit> hits);  // empty list 미적재 (CONSIDER-9)
}
```

**CacheConfig 수정**: `GOOGLE_PLACES_RESPONSE_CACHE` 상수 + Caffeine 24h/2000 등록 (Gemini 패턴 일치).

**GooglePlacesClient 통합**: 캐시 hit → outcome=cached, miss → 외부 호출 후 put.

### FR-OBS-10: Gemini 5xx 비율 임계값 (Google Places 보류)

**CONSIDER-8**: Google Places 80%/95% **Phase 3 보류**. Gemini 5xx 1시간 10%만 구현.

**신규**: `infrastructure/notify/ThresholdMonitorScheduler.java`
```java
@Component
public class ThresholdMonitorScheduler {
    private static final double GEMINI_ERROR_RATIO = 0.10;
    private static final double INSTAGRAM_FAILURE_RATIO = 0.50;
    private static final long COOLDOWN_MS = 5 * 60 * 1_000L;

    private final ConcurrentMap<String, AtomicLong> lastAlertMs = new ConcurrentHashMap<>();
    private double previousGeminiTotal = 0d;
    private double previousGeminiError = 0d;

    @Scheduled(cron = "0 0 * * * *")
    void runHourly() {
        MDC.put(RequestIdFilter.MDC_KEY, "SCHEDULER");
        try {
            checkGeminiErrorRatio();
            checkInstagramFailureRatio();
        } finally { MDC.clear(); }
    }

    private boolean tryEnterCooldown(String stageKey);  // ChatbotRateLimitFilter compareAndSet
}
```

**Gemini 비율 산출**: snapshot delta 방식. previous*값을 필드 유지, 매 정시 currentTotal/Error 읽어 차이 계산. delta=0이면 알림 생략.

### FR-OBS-11: Instagram 차단 감지

**신규**: `infrastructure/scraper/instagram/InstagramFailureTracker.java`
```java
@Component
public class InstagramFailureTracker {
    private final AtomicInteger total = new AtomicInteger(0);
    private final AtomicInteger failure = new AtomicInteger(0);

    public void recordOutcome(boolean finalFailure);
    public Snapshot snapshotAndReset();  // getAndSet(0)
    public record Snapshot(int total, int failure) { public OptionalDouble ratio(); }
}
```

**race 트레이드오프**: getAndSet 두 호출 사이 1~2건 손실 가능, MVP 수용.

### FR-OBS-12: Slack 본문 requestId 자동 동봉

**CONSIDER-4 반영**: `SlackContextEnricher` 폐기. `SlackNotifier.send` 내부에서 MDC 직접 읽기.

**SlackNotifier 수정**:
```java
private void send(String emoji, String title, Map<String, Object> context, String color) {
    Map<String, Object> enriched = new LinkedHashMap<>();
    String mdcRequestId = MDC.get(RequestIdFilter.MDC_KEY);
    enriched.put("requestId", mdcRequestId != null ? mdcRequestId : "n/a");
    if (context != null) enriched.putAll(context);
    // ... 기존 fieldsSection에 enriched 사용
}
```

**호출자 코드 변경 0** (Best). 효과:
- servlet 요청 → UUID
- TaskDecorator 전파된 비동기 워커 → UUID 보존
- ThresholdMonitorScheduler → "SCHEDULER"
- MDC 없는 경우 → "n/a"

**비동기 진입점 3곳 (MUST-1)**:
1. `PlaceFallbackOrchestrator.runAsync` — 옵션 A 채택. 명시적 캡처:
   ```
   Map<String,String> snap = MDC.getCopyOfContextMap();
   executor.execute(() -> {
       if (snap != null) MDC.setContextMap(snap); else MDC.clear();
       try { processAsync(...); } finally { MDC.clear(); }
   });
   ```
2. `PendingInstagramAutoSaveScheduler` — 메서드 진입 시 `MDC.put("SCHEDULER")` + finally clear
3. `ThresholdMonitorScheduler.runHourly` — 위 코드에 포함

### FR-OBS-13: Logback 일별 회전

**MUST-3 반영**: `application.yml` 변경 **없음**. `logback.xml` 단일 진실원천.

**신규**: `backend/supports/logging/src/main/resources/appenders/file-rolling-appender.xml`
```xml
<included>
  <property name="LOG_DIR" value="/var/log/wherewego"/>
  <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>${LOG_DIR}/spring.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>${LOG_DIR}/spring-%d{yyyy-MM-dd}.log.gz</fileNamePattern>
      <maxHistory>90</maxHistory>
      <totalSizeCap>5GB</totalSizeCap>
      <cleanHistoryOnStart>true</cleanHistoryOnStart>
    </rollingPolicy>
    <encoder>
      <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{requestId:-}] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>
</included>
```

**logback.xml 수정**: dev/prod springProfile에 `<include resource="appenders/file-rolling-appender.xml"/>` + `<appender-ref ref="FILE"/>`. local/test는 콘솔만.

**plain-console-appender.xml / json-console-appender.xml 수정**: 패턴/JSON 키에 `[%X{requestId:-}]` 또는 `"requestId":"%X{requestId:-}"` 추가.

**deploy.yml 수정**:
```
"sudo mkdir -p /var/log/wherewego && sudo chown 1000:1000 /var/log/wherewego",
("docker run -d --name wherewego-api --env-file /etc/wherewego/.env -e JAVA_TOOL_OPTIONS=\"-Xmx512m -Xms256m\" -p 8080:8080 --memory 700m --restart unless-stopped --log-driver=json-file --log-opt max-size=50m --log-opt max-file=3 -v /var/log/wherewego:/var/log/wherewego " + $img + ":latest"),
```

---

## PR 재분할 (MUST-4)

### PR-A: MTTR 단축
**사업 가치**: Slack 알림 → requestId 복사 → 로그 grep → 역추적 1-step.

**포함 FR**: FR-OBS-6, FR-OBS-7, FR-OBS-12, FR-OBS-13

**작업**:
1. RequestIdFilter + Config (FR-OBS-6, TaskDecorator 빈)
2. logback.xml + file-rolling-appender.xml + 콘솔 패턴 수정 (FR-OBS-13)
3. deploy.yml docker 옵션 수정 (FR-OBS-13)
4. SlackNotifier.send MDC 자동 동봉 (FR-OBS-12)
5. GooglePlacesClient/InstagramScraperClient/KakaoCallbackClient/GeminiPlaceClient 구조화 로그 (FR-OBS-7)
6. PlaceFallbackOrchestrator runAsync MDC 명시 전파 (MUST-1)
7. PendingInstagramAutoSaveScheduler MDC.put("SCHEDULER") (MUST-1)

### PR-B: 사전 감지
**사업 가치**: 캐싱 + Instagram 차단/Gemini 5xx 임계값 감지.

**포함 FR**: FR-OBS-9, FR-OBS-10(Gemini만), FR-OBS-11

**작업**:
1. CacheConfig + GooglePlacesResponseCacheService (FR-OBS-9)
2. GooglePlacesClient 캐시 통합 (FR-OBS-9)
3. InstagramFailureTracker + InstagramScraperClient 트래커 호출 (FR-OBS-11)
4. ThresholdMonitorScheduler — Gemini 5xx + Instagram 두 임계값 (FR-OBS-10/11)

---

## PRD 정정 필요 사항

설계 결정에 따라 PRD에 두 가지 정정이 필요하다 (사용자 별도 작업).

### 1. MUST-3: FR-OBS-13의 application.yml 문구 무효
- PRD 58라인: "`application.yml` `logging.file.name`/`logback.rollingpolicy` 설정" → **`backend/supports/logging/src/main/resources/logback/logback.xml`** 로 정정 필요. 커스텀 logback.xml 사용 시 Spring Boot `logging.*` 자동 바인딩 미적용.
- AC-8 검증 위치도 동일하게 정정.

### 2. CONSIDER-8: FR-OBS-10에서 Google Places 부분 제외
- PRD 64라인 "Google Places 80%/95%" 항목 → Phase 3 보류 표기.
- AC-10/11 보류 표기.
- FR-OBS-8(Google Places 메트릭) + AC-4 도 함께 보류 (임계값 알림과 결합되어 의미가 있으므로 단독으로 구현하지 않음).

---

## 변경 범위

| 분류 | 파일 | 변경 내용 | 연결 FR | PR |
|------|------|----------|---------|----|
| 신규 | `config/security/RequestIdFilter.java` | MDC put/clear, 응답 헤더 echo | FR-OBS-6 | PR-A |
| 신규 | `config/security/RequestIdFilterConfig.java` | Filter 등록 + @EnableScheduling + mdcTaskDecorator | FR-OBS-6/MUST-1 | PR-A |
| 신규 | `backend/supports/logging/src/main/resources/appenders/file-rolling-appender.xml` | RollingFileAppender 90일/5GB | FR-OBS-13 | PR-A |
| 신규 | `infrastructure/place/google/GooglePlacesResponseCacheService.java` | SHA-256 캐시 (empty 미적재) | FR-OBS-9 | PR-B |
| 신규 | `infrastructure/scraper/instagram/InstagramFailureTracker.java` | AtomicInteger 2개 | FR-OBS-11 | PR-B |
| 신규 | `infrastructure/notify/ThresholdMonitorScheduler.java` | Gemini 5xx + Instagram 임계값 | FR-OBS-10/11 | PR-B |
| 수정 | `infrastructure/notify/slack/SlackNotifier.java` | send 내부 MDC 자동 동봉 | FR-OBS-12 | PR-A |
| 수정 | `infrastructure/place/google/GooglePlacesClient.java` | 구조화 로그(+캐시 PR-B) | FR-OBS-7/9 | PR-A/B |
| 수정 | `infrastructure/scraper/instagram/InstagramScraperClient.java` | 구조화 로그(+트래커 PR-B) | FR-OBS-7/11 | PR-A/B |
| 수정 | `infrastructure/chatbot/callback/KakaoCallbackClient.java` | 구조화 로그 | FR-OBS-7 | PR-A |
| 수정 | `infrastructure/gemini/GeminiPlaceClient.java` | 구조화 로그 3 메서드 | FR-OBS-7 | PR-A |
| 수정 | `domain/place/PlaceFallbackOrchestrator.java` | runAsync 명시 MDC 전파 | MUST-1 | PR-A |
| 수정 | `domain/chatbot/PendingInstagramAutoSaveScheduler.java` | 진입 시 MDC.put("SCHEDULER") | MUST-1 | PR-A |
| 수정 | `config/cache/CacheConfig.java` | GOOGLE_PLACES_RESPONSE_CACHE 등록 | FR-OBS-9 | PR-B |
| 수정 | `backend/supports/logging/src/main/resources/logback/logback.xml` | dev/prod FILE include | FR-OBS-6/13 | PR-A |
| 수정 | `backend/supports/logging/src/main/resources/logback/appenders/plain-console-appender.xml` | 패턴에 `[%X{requestId:-}]` | FR-OBS-6 | PR-A |
| 수정 | `backend/supports/logging/src/main/resources/logback/appenders/json-console-appender.xml` | JSON 키 `requestId` | FR-OBS-6 | PR-A |
| 수정 | `.github/workflows/deploy.yml` | docker run -v + --log-opt + mkdir | FR-OBS-13 | PR-A |

**합계**: 신규 6개 / 수정 12개

---

## 구현 순서

### PR-A: MTTR 단축
```
1. RequestIdFilter + Config (의존 없음)
2. file-rolling-appender.xml + logback.xml + 콘솔 패턴 (의존: 1)
3. deploy.yml docker 옵션 (의존: 2)
4. SlackNotifier MDC 자동 동봉 (의존: 1)
5~8. GooglePlacesClient/InstagramScraperClient/KakaoCallbackClient/GeminiPlaceClient 구조화 로그 (의존: 1, 병렬 가능)
9. PlaceFallbackOrchestrator MDC 캡처 (의존: 1)
10. PendingInstagramAutoSaveScheduler MDC.put("SCHEDULER") (의존: 1)
```

### PR-B: 사전 감지 (PR-A 머지 이후)
```
11. CacheConfig 수정 (의존 없음)
12. GooglePlacesResponseCacheService 신규 (의존: 11)
13. GooglePlacesClient 캐시 통합 (의존: 12, PR-A 5 위에 빌드)
14. InstagramFailureTracker 신규 (의존 없음)
15. InstagramScraperClient 트래커 호출 (의존: 14, PR-A 6 위에 빌드)
16. ThresholdMonitorScheduler 신규 (의존: 14, @EnableScheduling은 PR-A에서 활성화)
```

---

## 트레이드오프

| 결정 | 채택 | 근거 |
|------|------|------|
| X-Request-Id 헤더 | 거부, 자체 발급 (MUST-2) | AC-2 강건성, 스푸핑 차단 |
| Logback 설정 위치 | logback.xml 단일 진실원천 (MUST-3) | application.yml 자동 바인딩 미적용 |
| 비동기 MDC 전파 | 명시적 캡처 + TaskDecorator 빈 (MUST-1) | raw Executor 유지하면서 빈 재사용 |
| 스케줄러 진입점 | MDC.put("SCHEDULER") 명시 | SlackNotifier 자동 동봉과 정합 |
| PR 분할 | 사업 가치 단위 (MUST-4) | 독립 검증/롤백 |
| CooldownGate | 폐기, 스케줄러 내부 ConcurrentMap (CONSIDER-3) | ChatbotRateLimitFilter 패턴 차용 |
| SlackContextEnricher | 폐기, SlackNotifier 내부 (CONSIDER-4) | 호출자 변경 0 |
| InstagramFailureTracker | AtomicInteger 2개 (CONSIDER-5) | 고정 윈도우 명세 정합 |
| ExternalApiLogger | 폐기, 직접 작성 (CONSIDER-6) | 4곳뿐 |
| 신규 패키지 | 없음 (CONSIDER-7) | 도메인 동거 |
| Google Places 임계값 | Phase 3 보류 (CONSIDER-8) | 트래픽 30/일 |
| Google Places empty 캐싱 | 미적재 (CONSIDER-9) | 24h 차단 회피 |
| cron | `0 0 * * * *` (Q5) | 매 정시 |
| Gemini 윈도우 | snapshot delta (Q6) | 메모리 ~수 KB |
| 일 1회 게이트 | 미적용 (Q7) | 5분 쿨다운만 |
| Counter 영속성 | 인메모리 (재기동 리셋) | MVP 수용 |
