# observability 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

```
                ┌──────────────────────────────────────────┐
                │  Spring Boot Application (wherewego-api) │
                └──────────────────────────────────────────┘
                                  │
       ┌──────────────────────────┼──────────────────────────┐
       │                          │                          │
   ┌───▼────┐              ┌─────▼─────┐              ┌─────▼─────┐
   │ Filter │              │ Micrometer│              │  Slack    │
   │  + MDC │              │  Counter/ │              │  Notifier │
   │RequestId              │  Timer    │              │ (Block Kit│
   └───┬────┘              └─────┬─────┘              │  3-tier)  │
       │                         │                    └─────┬─────┘
       │ %X{requestId}           │                          │
       ▼                         │                          │ 임계값 초과
   logback                       │ /actuator/prometheus     │
   (콘솔/파일)                    ▼                          │
                          ┌──────────────┐                  │
                          │  Prometheus  │                  │
                          │  (scrape)    │                  │
                          └──────┬───────┘                  │
                                 │                          │
                          ┌──────▼───────┐           ┌──────▼──────┐
                          │   Grafana    │           │   Slack     │
                          │  (JVM/HTTP)  │           │  (#channel) │
                          └──────────────┘           └─────────────┘
                                                            ▲
                                                            │
                                                  ┌─────────┴─────────┐
                                                  │  @Scheduled       │
                                                  │  ThresholdMonitor │
                                                  │  (일일/시간 윈도우)│
                                                  └───────────────────┘
```

## 레이어 분리

- **수집 레이어**: `OncePerRequestFilter`(MDC), 도메인별 Micrometer Counter/Timer(`GeminiUsageMetrics`, `GooglePlacesMetrics`), 외부 API 호출 지점의 구조화 로그
- **적재 레이어**: Logback `TimeBasedRollingPolicy` → `/var/log/wherewego/spring-%d.log.gz` (일별 회전, gzip, 90일/5GB 상한). EC2 host volume mount로 컨테이너 재배포에도 보존. Docker json-file은 `max-size=50m max-file=3`으로 이중 적재 제한 (`docker logs` 명령 호환 유지)
- **저장/시각화 레이어**: Prometheus 스크레이프(`management.endpoints.prometheus`, port 8081), Grafana 대시보드(JVM/HTTP 응답시간 백분위)
- **모니터링 레이어** (PR-B 신설, `infrastructure.monitoring`): `ThresholdMonitorScheduler`가 1h 윈도우로 Gemini server_error 비율과 Instagram 차단율을 평가. `InstagramBlockedRateTracker`(synchronized 단일 락 상태 저장소). 각 check 개별 try-catch(Exception) 격리(NFR-4)
- **알림 레이어**: `SlackNotifier`(Block Kit, 3-tier: failure/warning/notify) + 5분 쿨다운(Gemini/Instagram 키별 `ConcurrentHashMap<String,Long>`)
- **캐싱/보호 레이어**: `CacheConfig`(Caffeine), `GeminiResponseCacheService`/`GooglePlacesResponseCacheService`(외부 API 응답 24h 캐시, SHA-256 키), `GeminiUserQuotaService`(쿼터 가드), `ChatbotRateLimitFilter`(레이트 리밋)

## 호출 흐름 예시 (Google Places, PR-B 반영)

1. 사용자 요청 → `OncePerRequestFilter` UUID 발급 → MDC `requestId` 주입
2. `PlaceSearchService` → `GooglePlacesClient.searchByKeyword()` 호출
3. 진입부에서 `GooglePlacesResponseCacheService.get(SHA-256(keyword))` — hit 시 `metrics.recordCall("cached")` + 구조화 로그(`cache=hit, duration_ms=0`) + 조기 return (외부 HTTP 미발생)
4. miss 시 외부 RestClient 호출. success/empty 분기에서만 `responseCache.put(keyHash, hits)`. rate_limited/timeout/error는 캐싱 안 함(FR-9-4)
5. finally 블록에서 `metrics.recordDuration → recordCall(outcome)` + 구조화 로그 발행 (`api=google_places, op=searchText, duration_ms=..., outcome=..., cache=miss|n/a`). 캐시/메트릭 호출은 try-catch로 격리(NFR-2/3)
6. 매 1시간 `@Scheduled` `ThresholdMonitorScheduler`가 `MeterRegistry.find("gemini.calls.total").counters()` 스냅샷 + 직전 스냅샷과 델타 차분 → server_error 비율 평가. 10% 초과 + 5분 쿨다운 통과 시 `SlackNotifier.notifyWarning` (MDC `SCHEDULER` 마커 자동 동봉)
7. 동일 tick에서 `InstagramBlockedRateTracker.flushWindow()` 호출 → 단일 락 안에서 스냅샷 + 리셋 → 차단율 50% 초과 시 `notifyFailure`(직전 blocked URL 동봉)

## 주제 문서

| 주제 | 설명 |
|------|------|
| [Slack 알림 정책](../../docs/operations/slack-alerts.md) | `SlackNotifier` 3-tier + `ThresholdMonitorScheduler` 5분 쿨다운(Gemini/Instagram 키별) + Logback Slack appender 정책. PR-A/B 완료 |
| [로그 적재 정책](../../docs/operations/logging.md) | Logback `TimeBasedRollingPolicy` + Docker volume + json-file 이중 적재 제한 + MDC `requestId` 패턴 |
| [Grafana 모니터링](../../docs/operations/grafana-monitoring.md) | Prometheus 스크레이프 + Grafana 패널(JVM/HTTP) — PR-B에서 `google_places.calls.total{outcome}` 패널 추가 필요 |
| (미작성) RequestId 전파 | 필터 → MDC → Slack 본문 전파 경로 (필요 시 신설) |
| (미작성) 외부 API 메트릭 카탈로그 | Gemini/Google Places outcome 분류 표준(success/empty/cached/rate_limited/timeout/error/server_error/disabled) |

## 연관 도메인

- [[place]] — Google Places/Gemini/Instagram scraper 호출 지점 (메트릭/쿼터 대상)
- [[chatbot]] — 카카오 Webhook + Callback 흐름 (Slack 알림의 주요 트리거)
- [[auth]] — Kakao OAuth (로그인 실패율 추적 대상)
