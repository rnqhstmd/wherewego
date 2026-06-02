# PRD: Phase 2.11 PR-B — Google Places 메트릭/캐시 + Gemini 5xx 임계값 스케줄러 + Instagram 차단 감지

## 배경

PR-A(#28)로 4개 외부 API 호출 지점에 구조화 로그(api/op/duration_ms/outcome/cache)와 RequestId MDC가 적용되었다. 그러나 로그는 사후 조회 수단이며, 다음 세 가지 운영 사각지대가 여전히 존재한다.

**현재 제품 상태:**
- Google Places: 구조화 로그만 있음. 호출 빈도/지연/실패율을 Grafana에서 추적 불가. 동일 키워드 반복 호출 시 매번 외부 API 비용 발생
- Gemini API: 메트릭/캐시는 구현되어 있으나(FR-OBS-2/4), 5xx 비율이 10%를 넘어도 운영자에게 실시간 알림이 없음. 현재 `error` outcome에 5xx와 기타 오류가 혼재되어 있어 정밀 추적 불가
- Instagram scraper: 3-stage 우회 전략이 모두 차단되는 상황(BLOCKED)을 운영자가 인지할 수단이 없음. Instagram의 차단 정책 강화가 서비스에 무음으로 영향을 미칠 수 있음

## 목표

**Goals:**
- Google Places 호출의 Micrometer 메트릭 수집으로 Grafana 대시보드 가시성 확보
- Google Places 응답 Caffeine 캐시 도입으로 동일 키워드 중복 API 호출 제거
- Gemini API의 HTTP 5xx 응답을 `server_error` outcome으로 분리하여 5xx만 정밀 추적
- Gemini 5xx 비율 10% 초과 시 운영자 Slack 경고 자동 발송 (1시간 윈도우, 5분 쿨다운)
- Instagram 차단율 50% 초과 시 운영자 Slack 실패 알림 자동 발송 (1시간 윈도우)
- 관측/알림 코드의 어떤 실패도 본 기능(외부 API 호출, 사용자 응답)을 차단하거나 서버를 다운시키지 않을 것

**Non-Goals:**
- Google Places 80%/95% 사용량 임계값 알림 — Phase 3 보류 (일일 트래픽 30건으로 8K 무료 한도 도달 불가. FR-OBS-14 rate limit과 함께 재검토)
- CloudWatch Logs 전환 — Phase 3 (FR-OBS-18)
- AOP/Resilience4j 도입 — MVP 규모에서 보류
- Instagram scraper 재시도/backoff 로직
- Kakao Callback 임계값 모니터링

---

## 요구사항

### 기능 요구사항

**FR-OBS-8: Google Places Micrometer 메트릭**
- [Must] FR-8-1: `GooglePlacesMetrics` 컴포넌트 신규. `google_places.calls.total{outcome}` Counter + `google_places.call.duration{outcome}` Timer. GeminiUsageMetrics 패턴 복제
- [Must] FR-8-2: outcome 라벨: `success`/`empty`/`rate_limited`/`timeout`/`error`/`cached`
- [Must] FR-8-3: `GooglePlacesClient.searchByKeyword()` finally에서 Counter/Timer 기록. 캐시 히트 시 finally 도달 전 조기 반환이므로 히트 시점에 `cached` Counter만 기록
- [Should] FR-8-4: `cached` outcome은 Timer 미기록, Counter만

**FR-OBS-9: Google Places 응답 Caffeine 캐시**
- [Must] FR-9-1: `GooglePlacesResponseCacheService` 신규. 키 = SHA-256(keyword) 단일. languageCode/regionCode 미포함 (ko/KR 하드코딩)
- [Must] FR-9-2: 캐시 이름 `googlePlacesResponseCache`, TTL 24h, maximumSize 1,000
- [Must] FR-9-3: 빈 결과(`empty`)도 캐싱
- [Must] FR-9-4: `rate_limited`/`timeout`/`error`는 캐싱 안 함
- [Must] FR-9-5: 캐시 히트 시 구조화 로그 `cache=hit`
- [Should] FR-9-6: 캐시 미스 후 put 성공 시 `cache=miss`

**FR-OBS-10: 임계값 스케줄러 — Gemini 5xx 비율**
- [Must] FR-10-1: `ThresholdMonitorScheduler` 신규. `@Scheduled(fixedRate = 3600000)` (1h). MDC `SCHEDULER` 마커
- [Must] FR-10-2: 분자 = `gemini.calls.total{outcome=server_error}` 1h 델타. 분모 = 전체 outcome 합계 - `disabled` 델타. 분모 1건 미만이면 계산 건너뜀
- [Must] FR-10-3: 분모 1건 이상 + 비율 10% 초과 → `SlackNotifier.notifyWarning`
- [Must] FR-10-4: 키 `gemini.5xx` 5분 쿨다운. `ConcurrentHashMap<String, Long>` (Epoch ms)
- [Should] FR-10-5: 알림 컨텍스트: 윈도우 기간, server_error 건수, 전체 호출 건수(disabled 제외), 비율(%)

**FR-OBS-11: Instagram 차단 감지 + 알림**
- [Must] FR-11-1: `InstagramBlockedRateTracker` 신규. `synchronized` 단일 락으로 보호되는 `long attempts`/`long blocked` 필드를 두어 카운터 증가·스냅샷·리셋을 한 락에서 원자 스왑한다 (`@Scheduled` 없음 — 상태 저장소만, 윈도우 종료는 `ThresholdMonitorScheduler`가 `flushWindow()` 호출로 처리)
- [Must] FR-11-2: `InstagramScraperClient.fetchHtml()` finally에서 `tracker.recordAttempt()`, BLOCKED 확정 시 `tracker.recordBlocked(url)`
- [Must] FR-11-3: attempts=0이면 알림 생략, 카운터만 리셋
- [Must] FR-11-4: blocked/attempts > 50% → `SlackNotifier.notifyFailure`. 컨텍스트에 attempts, blocked, rate(%), 직전 blocked URL 1건
- [Should] FR-11-5: `flushWindow()`가 캡처+리셋을 원자 스왑한 뒤 반환된 스냅샷으로 임계 판단/발송. 알림 발송 여부와 무관하게 리셋은 캡처와 동시에 수행되며, 발송은 캡처된 스냅샷 값으로만 이루어진다

**FR-OBS-8-pre: GeminiPlaceClient server_error outcome 분리** (PR-B 범위 추가)
- [Must] FR-pre-1: `GeminiResponseException` throw 분기의 outcome 라벨을 `error` → `server_error`로 변경
- [Must] FR-pre-2: 기존 `error` outcome은 JSON 파싱 실패/기타 RuntimeException 등 비-5xx만 담당. 동작 변경 없음
- [Must] FR-pre-3: `gemini.calls.total{outcome=server_error}` 카운터가 Micrometer에 발급됨

### 비즈니스 규칙

- [Must] BR-1: **Gemini 5xx 정의** — HTTP 500/502/503/504 → `GeminiResponseException` 변환 케이스만. timeout/rate_limited/quota_exceeded/disabled/empty/error는 5xx에서 제외. outcome 라벨 = `server_error`
- [Must] BR-2: **캐시 격리** — `googlePlacesResponseCache` 독립 캐시 인스턴스. 기존 캐시와 이름 충돌 없음
- [Must] BR-3: **쿨다운 경계** — 5분(300,000ms) 미만 재발송 없음. 5분 경과 후 즉시 재발송
- [Must] BR-4: **알림 폭주 방지** — 정상 운영 최대 1회/시간. 5분 쿨다운은 스케줄러 재기동 등 연속 실행 보호
- [Must] BR-5: **윈도우 리셋 정합성** — `flushWindow()`가 스냅샷 캡처와 카운터 리셋을 단일 락에서 원자 스왑한 뒤, 반환된 스냅샷 값으로 임계 판단/발송을 수행한다. 리셋 후 발생한 새 increment는 다음 윈도우로 귀속되며, 리셋 후 상태의 새 데이터로 알림이 발송되는 일은 발생하지 않는다
- [Must] BR-6: **Google Places 캐시 키 단순화** — SHA-256(keyword). lang/region 가변화 시 마이그레이션 필요 (PR 범위 외)

### 품질 기대

- [Should] QE-1: 동일 키워드 반복 시 `google_places.calls.total{outcome=cached}` 증가 추세를 Grafana 확인 가능
- [Should] QE-2: Slack 알림 내용으로 Gemini 5xx vs Instagram 차단 즉시 구분 가능
- [Should] QE-3: `disabled` 분모 제외로 feature flag off 상태에서 오탐 없음

---

## 비기능 요구사항 — 장애 격리 (CRITICAL)

- [Must] NFR-1: **Slack 발송 실패 격리** — SlackNotifier가 이미 swallow + log.warn. 우회 호출 신규 추가 금지
- [Must] NFR-2: **캐시 조회/적재 실패 격리** — get()/put() RuntimeException catch → 미스로 대체. 본 흐름 계속
- [Must] NFR-3: **메트릭 기록 실패 격리** — recordCall/recordDuration RuntimeException catch. searchByKeyword() 반환값/예외 전파 불변
- [Must] NFR-4: **스케줄러 1회 실패 격리** — @Scheduled 본문 최상위 try-catch(Exception). swallow + log.error. PendingInstagramAutoSaveScheduler 패턴
- [Must] NFR-5: **트래커 호출 실패 격리** — fetchHtml 내 tracker 호출 개별 try-catch(RuntimeException). 반환값 불변
- [Must] NFR-6: **캐시 메모리 한계** — maximumSize 1,000. Caffeine LRU eviction

---

## 사용자 시나리오

(상세 5개 시나리오 — 정상 흐름 3, 엣지 케이스 2 — 본문 참조)

---

## 영향 범위

**수정 파일**: GeminiPlaceClient, GooglePlacesClient, InstagramScraperClient, CacheConfig, application.yml
**신규 파일**: GooglePlacesMetrics, GooglePlacesResponseCacheService, ThresholdMonitorScheduler, InstagramBlockedRateTracker

**하위 호환성**:
- 구조화 로그 `cache` 필드 (Google Places): `n/a` → `hit`/`miss`/`n/a`(오류 시)
- Grafana `gemini.calls.total{outcome=error}`에서 5xx가 `server_error`로 분리

---

## 수용 기준 (AC-1 ~ AC-19)

| # | 수용 기준 | 연결 |
|---|----------|------|
| AC-1 | Google Places 호출 후 `google_places.calls.total{outcome=success\|empty}` Counter +1 | FR-8-1, FR-8-3 |
| AC-2 | 동일 keyword 24h 이내 2번째 호출 시 외부 HTTP 미발생 + `{outcome=cached}` 증가 | FR-9-1, FR-9-3, FR-8-3 |
| AC-3 | 빈 결과도 캐싱 → 재호출 시 캐시 반환 | FR-9-3 |
| AC-4 | rate_limited/timeout/error는 캐싱 안 됨 → 재호출 시 외부 API 호출 | FR-9-4 |
| AC-5 | 캐시 히트 시 `cache=hit`, 미스+put 성공 시 `cache=miss` | FR-9-5, FR-9-6 |
| AC-6 | Gemini 5xx 호출에서 `{outcome=server_error}` +1, `{outcome=error}`는 불변 | FR-pre-1, FR-pre-2, BR-1 |
| AC-7 | Gemini timeout/429/quota/disabled에서 `server_error` 불변 | FR-pre-2, BR-1 |
| AC-8 | server_error 비율 > 10% + 분모 ≥ 1 → notifyWarning. 컨텍스트에 건수/비율 포함 | FR-10-2, FR-10-3, FR-10-5 |
| AC-9 | feature flag off (disabled 전체) → 알림 미발송 | FR-10-2, QE-3 |
| AC-10 | 5분 이내 동일 조건 재발송 없음 | FR-10-4, BR-3 |
| AC-11 | Instagram BLOCKED 종료 시 blocked +1. fetchHtml finally 시 attempts +1 | FR-11-2 |
| AC-12 | 1h 차단율 > 50% + attempts ≥ 1 → notifyFailure. 직전 blocked URL 포함 | FR-11-3, FR-11-4 |
| AC-13 | attempts=0 → 알림 미발송 | FR-11-3 |
| AC-14 | `flushWindow()`는 스냅샷 캡처와 카운터 리셋을 단일 락에서 원자 스왑한다. 리셋 직후 attempts=0, blocked=0이며 발송은 캡처된 스냅샷 값으로 수행된다 | BR-5, FR-11-5 |
| AC-15 | CacheService.get() RuntimeException → 미스로 대체. 호출자 정상 진행 | NFR-2 |
| AC-16 | Metrics.recordCall() RuntimeException → searchByKeyword 반환/예외 불변 | NFR-3 |
| AC-17 | @Scheduled RuntimeException → 다음 1h 정상 재실행 | NFR-4 |
| AC-18 | tracker 호출 RuntimeException → fetchHtml 반환값(Optional) 불변 | NFR-5 |
| AC-19 | googlePlacesResponseCache maximumSize = 1,000 | NFR-6 |

---

## 트레이드오프 / 결정

| 결정 | 근거 |
|------|------|
| Gemini 5xx만 임계값 모니터링 | 트래픽 30건/일로 Google Places 8K 한도 도달 불가. FR-OBS-14와 함께 재검토 |
| `server_error` outcome 분리 | BR-1 정의와 일치하는 정밀 추적. GeminiPlaceClient 1줄 변경 |
| SHA-256(keyword) 단일 캐시 키 | ko/KR 하드코딩으로 히트율 동일. 가독성 우선 |
| 1h 고정 윈도우 | 운영자 직관. 슬라이딩 윈도우 대비 단순. 현 트래픽에서 충분 |
| Instagram 50% 임계 | 3-stage 특성상 1~2건 차단은 정상. 50%는 정책 변화 실질 신호 |
| Gemini 10% 임계 | 안정 API에서 1건 반복도 주목. 노이즈/심각 장애 균형 |
| 5분 쿨다운 | 정상 1회/h. 재기동 연속 발생 시 폭주 방지 |
| maximumSize 1,000 | GeminiResponseCache(2,000)의 절반. 키워드 다양성 < 캡션 다양성 |

---

## 위험 / 미해결

| 항목 | 대응 |
|------|------|
| 윈도우 경계 동시성 (AtomicLong 두 카운터의 스냅샷 미세 어긋남) | 각 카운터 원자성 보장. 오차 1건 수준 허용 |
| 서버 재시작 시 윈도우 유실 | 현 규모 허용. Prometheus에 메트릭 지속 적재됨 |
| Gemini 5xx 분모 정의 | `disabled` 제외 명시. 그 외 outcome은 분모 포함 |
| Google Places 캐시 키 마이그레이션 | lang/region 가변화 시 캐시 이름 변경(googlePlacesResponseCacheV2)으로 자연 재빌드 |

---

## 운영 체크리스트

- `SLACK_WEBHOOK_URI` 환경변수 설정 확인 (미설정 시 알림 no-op)
- Grafana 패널 추가: `google_places.calls.total`, `google_places.call.duration`
- Grafana `gemini.calls.total{outcome=error}` 기존 패널 → `server_error` 시리즈 추가 인지
