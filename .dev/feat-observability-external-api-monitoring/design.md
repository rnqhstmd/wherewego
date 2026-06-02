# 설계서 v2: Phase 2.11 PR-B — Google Places 메트릭/캐시 + Gemini 5xx 임계값 스케줄러 + Instagram 차단 감지

## 설계 규모
**중형** — 신규 4 / 수정 5. AtomicLong 두 카운터의 동시 스왑(synchronized), Counter 누적 델타 추출, outcome lazy 등록 대응.

## 컴포넌트 책임

- **GooglePlacesMetrics**: outcome 라벨 Counter/Timer 발급. GeminiUsageMetrics 패턴 복제
- **GooglePlacesResponseCacheService**: SHA-256(keyword) Caffeine. put/get 시 `List.copyOf` immutable 복사본
- **ThresholdMonitorScheduler**: 단일 진입점 `runMonitoringTick`. Gemini 5xx + Instagram 차단 모두 처리. MDC SCHEDULER 마커 1회
- **InstagramBlockedRateTracker**: synchronized 단일 원자 스왑 상태 저장소. @Scheduled 없음

## 변경 범위

**신규**:
- `infrastructure/place/google/GooglePlacesMetrics.java`
- `infrastructure/place/google/GooglePlacesResponseCacheService.java`
- `infrastructure/monitoring/ThresholdMonitorScheduler.java` (신규 패키지)
- `infrastructure/scraper/instagram/InstagramBlockedRateTracker.java`

**수정**:
- `infrastructure/place/google/GooglePlacesClient.java` — 의존성 2개 + 캐시/메트릭 통합
- `infrastructure/scraper/instagram/InstagramScraperClient.java` — tracker 주입
- `infrastructure/gemini/GeminiPlaceClient.java` — `OUTCOME_SERVER_ERROR` 상수 + 3곳 catch 변경
- `config/cache/CacheConfig.java` — googlePlacesResponseCache 등록
- `resources/application.yml` — `monitoring.threshold.*` properties

## 상세 설계

### 1. GooglePlacesMetrics
GeminiUsageMetrics 패턴 그대로:
- `google_places.calls.total{outcome}` Counter
- `google_places.call.duration{outcome}` Timer
- `recordCall(outcome)`, `recordDuration(durationMs, outcome)`
- outcome: success/empty/rate_limited/timeout/error/cached

### 2. GooglePlacesResponseCacheService
- `String hashKey(String keyword)` — SHA-256 hex
- `Optional<List<PlaceSearchHit>> get(String keywordHash)` — wrapper.get()을 `List.copyOf` 복사 후 반환
- `void put(String keywordHash, List<PlaceSearchHit> hits)` — null 가드, `cache.put(key, List.copyOf(hits))`

### 3. GooglePlacesClient 수정
- 생성자에 `GooglePlacesResponseCacheService responseCache`, `GooglePlacesMetrics metrics` 주입
- 상수: `OUTCOME_CACHED = "cached"`, `CACHE_HIT = "hit"`, `CACHE_MISS = "miss"`
- `searchByKeyword()` 진입부:
  ```
  String keyHash = null;
  try { keyHash = responseCache.hashKey(keyword); } catch (RuntimeException) { log.warn }
  if (keyHash != null) {
      Optional<List<PlaceSearchHit>> cached;
      try { cached = responseCache.get(keyHash); } catch (RuntimeException) { cached = empty; log.warn }
      if (cached.isPresent()) {
          try { metrics.recordCall(OUTCOME_CACHED); } catch (RuntimeException) { log.warn }
          log.info("api=google_places op=searchText duration_ms=0 outcome=cached cache=hit");
          return cached.get();
      }
  }
  ```
- 기존 try-catch-finally:
  - success/empty 분기에서 `try { responseCache.put(keyHash, hits); cachePut=true } catch (RuntimeException) { log.warn }`
  - finally에서 `try { metrics.recordCall(outcome); metrics.recordDuration(elapsed, outcome) } catch (RuntimeException) { log.warn }`
  - finally 구조화 로그 `cache=` 값: `cachePut ? "miss" : "n/a"`

### 4. GeminiPlaceClient 수정 (FR-OBS-8-pre)
- **4-1**: 상수 `OUTCOME_SERVER_ERROR = "server_error"` 추가 (line ~124)
- **4-2**: `extractPlaceName` catch (GeminiResponseException) → outcome=OUTCOME_SERVER_ERROR
- **4-3**: `extractCandidatesInternal` catch (GeminiResponseException) → outcome=OUTCOME_SERVER_ERROR
- **4-4**: `extractPlaceNames` catch (GeminiResponseException) → outcome=OUTCOME_SERVER_ERROR
- 다른 catch 블록(RestClientException/JSON 파싱 등)의 OUTCOME_ERROR는 불변

### 5. CacheConfig 수정
- 상수: `public static final String GOOGLE_PLACES_RESPONSE_CACHE = "googlePlacesResponseCache"`
- registerCustomCache 추가:
  ```
  manager.registerCustomCache(GOOGLE_PLACES_RESPONSE_CACHE,
      Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofHours(24))
          .maximumSize(1_000)
          .build());
  ```

### 6. InstagramBlockedRateTracker (synchronized 단일 원자 스왑)

```java
@Component
public final class InstagramBlockedRateTracker {
    private final Object lock = new Object();
    private long attempts = 0L;
    private long blocked = 0L;
    private String lastBlockedUrl = null;

    public void recordAttempt() {
        synchronized (lock) { attempts++; }
    }
    public void recordBlocked(String url) {
        synchronized (lock) {
            blocked++;
            lastBlockedUrl = safeForLog(url);
        }
    }
    public Snapshot flushWindow() {
        synchronized (lock) {
            Snapshot s = new Snapshot(attempts, blocked, lastBlockedUrl);
            attempts = 0L; blocked = 0L; lastBlockedUrl = null;
            return s;
        }
    }
    public record Snapshot(long attempts, long blocked, String lastBlockedUrl) {}

    private static String safeForLog(String v) {
        return v == null ? null : v.replace('\r', '_').replace('\n', '_');
    }
}
```

정합성:
- 스냅샷+리셋이 같은 락 안 → 두 카운터 race 구조적 제거
- recordAttempt/recordBlocked가 같은 락 공유 → flushWindow 중 대기
- BR-5 "판단→발송→리셋": flushWindow가 캡처+리셋만, 발송은 호출자(스케줄러)가 반환된 Snapshot으로

### 7. InstagramScraperClient 수정
- 생성자에 `InstagramBlockedRateTracker tracker` 주입
- finally 최상단에 `try { tracker.recordAttempt(); } catch (RuntimeException) { log.warn }`
- BLOCKED 분기에 `try { tracker.recordBlocked(url); } catch (RuntimeException) { log.warn }`
- 반환값(Optional) 절대 변경 금지

### 8. ThresholdMonitorScheduler

```java
@Component
public class ThresholdMonitorScheduler {
    private static final long WINDOW_MS = 3_600_000L;
    private static final String COOLDOWN_KEY_GEMINI_5XX = "gemini.5xx";

    private final MeterRegistry meterRegistry;
    private final InstagramBlockedRateTracker tracker;
    private final SlackNotifier slackNotifier;
    private final MonitoringThresholdProperties props;

    private final Map<String, Double> previousGeminiSnapshot = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldownEpochMs = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = WINDOW_MS)
    public void runMonitoringTick() {
        MDC.put(RequestIdFilter.MDC_KEY, "SCHEDULER");
        try {
            try { checkGeminiServerErrorRate(); }
            catch (Exception e) { log.error("Gemini 5xx check failed", e); }

            try { checkInstagramBlockedRate(); }
            catch (Exception e) { log.error("Instagram blocked check failed", e); }
        } finally {
            MDC.clear();
        }
    }
}
```

#### Gemini 5xx 분모 계산 알고리즘

```
Map<String, Double> current = snapshotGeminiCounters();   // find("gemini.calls.total").counters() 순회
Map<String, Double> delta = new HashMap<>();
for (var e : current.entrySet()) {
    double prev = previousGeminiSnapshot.getOrDefault(e.getKey(), 0.0);  // null → 0
    delta.put(e.getKey(), Math.max(0.0, e.getValue() - prev));
}
previousGeminiSnapshot.clear();
previousGeminiSnapshot.putAll(current);

double totalDelta = delta.values().stream().mapToDouble(Double::doubleValue).sum();
double disabledDelta = delta.getOrDefault("disabled", 0.0);
double effectiveTotal = totalDelta - disabledDelta;
if (effectiveTotal < 1.0) return;

double serverErrorDelta = delta.getOrDefault("server_error", 0.0);
double ratio = serverErrorDelta / effectiveTotal;
if (ratio > props.gemini().serverErrorRate()
        && cooldownPassed(COOLDOWN_KEY_GEMINI_5XX, System.currentTimeMillis())) {
    slackNotifier.notifyWarning("Gemini 5xx 비율 임계 초과", Map.of(
        "windowHours", 1,
        "serverError", (long) serverErrorDelta,
        "total", (long) effectiveTotal,
        "ratioPct", String.format("%.1f%%", ratio * 100)
    ));
    cooldownEpochMs.put(COOLDOWN_KEY_GEMINI_5XX, System.currentTimeMillis());
}
```

#### Instagram 차단 알고리즘

```
Snapshot snap = tracker.flushWindow();
if (snap.attempts() < 1) return;
double rate = snap.blocked() / (double) snap.attempts();
if (rate > props.instagram().blockedRate()) {
    slackNotifier.notifyFailure("Instagram 차단율 임계 초과", Map.of(
        "windowHours", 1,
        "attempts", snap.attempts(),
        "blocked", snap.blocked(),
        "ratePct", String.format("%.1f%%", rate * 100),
        "lastBlockedUrl", snap.lastBlockedUrl() == null ? "n/a" : snap.lastBlockedUrl()
    ));
}
```

### 9. application.yml (window-hours 키 제거)

```yaml
monitoring:
  threshold:
    gemini:
      server-error-rate: 0.10
      cooldown-minutes: 5
    instagram:
      blocked-rate: 0.50
```

```java
@ConfigurationProperties(prefix = "monitoring.threshold")
public record MonitoringThresholdProperties(Gemini gemini, Instagram instagram) {
    public record Gemini(double serverErrorRate, int cooldownMinutes) {}
    public record Instagram(double blockedRate) {}
}
```

## 장애 격리 매핑

| NFR | 위치 | 방법 |
|---|---|---|
| NFR-1 | SlackNotifier 기존 swallow | 우회 호출 신규 추가 금지 |
| NFR-2 | GooglePlacesClient 진입부 cache.get + put | try-catch(RuntimeException), miss로 대체 |
| NFR-3 | GooglePlacesClient finally + cached 분기 metrics 호출 | try-catch(RuntimeException) |
| NFR-4 | ThresholdMonitorScheduler 각 check 개별 try-catch | 한쪽 실패가 다른쪽 막지 않음 |
| NFR-5 | InstagramScraperClient tracker 호출 | try-catch(RuntimeException). Optional 불변 |
| NFR-6 | googlePlacesResponseCache maximumSize=1000 | Caffeine LRU |

## 구현 순서

배치1 (병렬): 1·CacheConfig, 2·GooglePlacesMetrics, 4-1·GeminiPlaceClient 상수, 5·InstagramBlockedRateTracker, 6·application.yml + MonitoringThresholdProperties
배치2 (병렬): 3·GooglePlacesResponseCacheService (1 후), 4-2·extractPlaceName / 4-3·extractCandidatesInternal / 4-4·extractPlaceNames (4-1 후, 서로 독립), 8·InstagramScraperClient (5 후)
배치3: 7·GooglePlacesClient (2, 3 후)
배치4: 9·ThresholdMonitorScheduler (4-1, 5, 6 후)
배치5: 10·테스트 보강

## 테스트 전략

Unit:
1. GooglePlacesMetricsTest — SimpleMeterRegistry로 Counter/Timer 검증
2. GooglePlacesResponseCacheServiceTest — get/put + List.copyOf immutability
3. GooglePlacesClientTest 보강 — 캐시 hit/miss, AC-15 (cache get RE → miss로 진행), AC-16 (metrics RE → 반환 불변)
4. GeminiPlaceClientServerErrorTest 신규 — 3개 메서드 각각 WireMock 5xx → server_error +1, error 불변. AC-6 ~ AC-7 보강
5. InstagramBlockedRateTrackerConcurrencyTest — 100 thread × 1000 recordAttempt → attempts == 100,000
6. InstagramBlockedRateTrackerTest — recordAttempt/recordBlocked → flushWindow + 재 flushWindow 시 0
7. ThresholdMonitorSchedulerTest:
   - 7-a: 첫 호출 lazy outcome → MUST-ADDRESS 2 검증
   - 7-b: outcome 신규 등장 시나리오
   - 7-c: 5분 쿨다운
   - 7-d: effectiveTotal<1 → 알림 생략 (AC-9)
   - 7-e: Gemini check 예외 시 Instagram check 정상 실행 (AC-17)
8. InstagramScraperClientTest 보강 — tracker RuntimeException → fetchHtml Optional 불변 (AC-18)

## 위험 / 미해결

| 항목 | 대응 |
|---|---|
| 다중 인스턴스 시 윈도우/쿨다운 분리 | 현재 1대 가정. 다중 인스턴스화 시 Redis 분산 카운터로 마이그레이션 (Phase 3) |
| Slack webhook 빈문자 시 쿨다운 정책 | 발송 시도 결과 무관하게 cooldown 업데이트 (dev/test만 영향, 운영 영향 없음) |
| Gemini 분모 정의 | disabled 제외 명시. 외 outcome은 분모 포함 |
| OUTCOME_CACHED finally dead code | 가독성 위해 가드 유지. 주석으로 명시 |
| 서버 재시작 시 윈도우 유실 | 현 규모 허용 |

## 트레이드오프 / 결정

| 결정 | 근거 |
|---|---|
| infrastructure.monitoring 신규 패키지 | ThresholdMonitorScheduler가 Gemini + Instagram cross-cut |
| Tracker synchronized | AtomicReference compareAndSet 대비 가독성. 1h 1회 flush로 락 경합 무영향 |
| WINDOW 외부화 제거 | yml/스케줄러 발사 주기 어긋남 위험 구조적 제거 |
| List.copyOf in cache | 외부 변형이 캐시 내부 오염 방지 |
| 각 check 개별 try-catch | Gemini 실패가 Instagram 알림 누락시키지 않도록 분리 |
