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

- **수집 레이어**: `OncePerRequestFilter`(MDC), `GeminiUsageMetrics` 등 도메인별 Micrometer Counter/Timer, 외부 API 호출 지점의 구조화 로그
- **적재 레이어**: Logback `TimeBasedRollingPolicy` → `/var/log/wherewego/spring-%d.log.gz` (일별 회전, gzip, 90일/5GB 상한). EC2 host volume mount로 컨테이너 재배포에도 보존. Docker json-file은 `max-size=50m max-file=3`으로 이중 적재 제한 (`docker logs` 명령 호환 유지)
- **저장/시각화 레이어**: Prometheus 스크레이프(`management.endpoints.prometheus`, port 8081), Grafana 대시보드(JVM/HTTP 응답시간 백분위)
- **알림 레이어**: `SlackNotifier`(Block Kit, 3-tier: failure/warning/notify) + `@Scheduled` ThresholdMonitor + 쿨다운 가드
- **캐싱/보호 레이어**: `CacheConfig`(Caffeine), `GeminiUserQuotaService`(쿼터 가드), `ChatbotRateLimitFilter`(레이트 리밋)

## 호출 흐름 예시 (Google Places)

1. 사용자 요청 → `OncePerRequestFilter` UUID 발급 → MDC `requestId` 주입
2. `PlaceSearchService` → `GooglePlacesClient.searchByKeyword()` 호출
3. 호출 직전/직후 구조화 로그 발행 (`api=google_places, op=searchText, duration_ms=..., outcome=..., cache=hit/miss`)
4. `GooglePlacesUsageMetrics` Counter/Timer 증가
5. (옵션) `GooglePlacesResponseCache` 캐시 hit 시 외부 호출 생략
6. 매 정시 `@Scheduled` ThresholdMonitor가 일일 누적 Counter 읽기 → 80%/95% 도달 시 `SlackNotifier.notifyWarning/Failure` (RequestId 동봉 불가, 합계 모니터)

## 주제 문서

| 주제 | 설명 |
|------|------|
| (미작성) Slack 알림 정책 | 임계값/쿨다운/메시지 포맷 규약 — Phase 2.11에서 신설 예정 |
| (미작성) RequestId 전파 | 필터 → MDC → Slack 본문 전파 경로 |
| (미작성) 외부 API 메트릭 카탈로그 | Gemini 패턴 기반 outcome 분류 표준 |

## 연관 도메인

- [[place]] — Google Places/Gemini/Instagram scraper 호출 지점 (메트릭/쿼터 대상)
- [[chatbot]] — 카카오 Webhook + Callback 흐름 (Slack 알림의 주요 트리거)
- [[auth]] — Kakao OAuth (로그인 실패율 추적 대상)
