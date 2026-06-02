# PRD: Phase 2.11 observability foundation

## 한 줄 설명

외부 API(Google Places, Instagram, Kakao Callback, Gemini) 호출 전반에 가시성을 부여하고, 임계값 기반 Slack 사전 경고로 운영자 1인의 사후 발견을 사전 감지로 전환한다.

---

## 배경

Phase 0에서 JVM/인프라 메트릭(Actuator + Prometheus + Grafana)이, Phase 2.5에서 Gemini 도메인의 메트릭/캐시/쿼터 3종 세트가 완성되었다. 그러나 동일한 핀 등록 흐름에 함께 등장하는 **Google Places**(월 $200 한도), **Instagram 캡션 스크래퍼**(3-stage 폴백), **Kakao Callback**은 메트릭·캐싱·구조화 로그가 0개다.

현재 Slack 알림 5곳(`PlaceFallbackOrchestrator`, `ChatbotRateLimitFilter`, `KakaoCallbackClient`)은 모두 단건 실패 즉시 발송 방식이며, MDC/RequestId 미사용으로 Slack 알림과 로그 사이 역추적이 불가능하다.

---

## 안 하면 어떻게 되는가

| 사각지대 | 사업 영향 |
|----------|-----------|
| Google Places 월 $200 한도 소진 | 핀 등록 흐름 무음 실패 → 챗봇 핀 자동 등록 70% 성공률 SLA 위반 위험 |
| Instagram scraper 차단 | 캡션 추출 자동화 가치 즉시 소실. 3-stage 폴백 최종 실패율 추적 없어 차단 패턴 사후 발견 |
| Kakao Callback 실패 | 재시도 0회, 비동기 폴백 결과 유실 → 사용자 "검색 결과를 받지 못함" 무음 발생 |
| RequestId 미발급 | Slack 알림 수신 후 로그 역추적 불가 → MTTR 폭증 |

---

## 사용자와 규모

- **알림 수신자**: rnqhstmd 1명, Slack 단일 채널(`SLACK_WEBHOOK_URI`)
- **일일 트래픽 추정**:
  - 챗봇 Webhook: ~100건/일
  - Google Places: ~30건/일 (월 ~900건, 무료 한도 ~10K req/$200)
  - Gemini: ~100건/일, 사용자별 50회 한도
  - Kakao OAuth: 로그인당 2회 호출

---

## 요구사항

### Must — Phase 2.11 핵심 (Phase 1: 가시성)

- **FR-OBS-6**: 모든 HTTP 요청에 UUID RequestId 발급. `OncePerRequestFilter`에서 MDC `requestId` 키 주입, Logback 패턴에 `%X{requestId:-}` 포함. 스레드 종료 시 MDC 정리(`finally` 블록 `MDC.clear()` 필수).

- **FR-OBS-7**: 외부 API 호출 4곳에 공통 구조화 로그 발행. 호출 완료 직후 `api={name} op={operation} duration_ms={ms} outcome={result} cache={hit|miss|n/a}` 형식. 적용 대상: Gemini, Google Places, Kakao Callback, Instagram scraper.

- **FR-OBS-8**: Google Places 도메인에 Micrometer Counter/Timer 추가. Gemini의 `GeminiUsageMetrics` 패턴을 그대로 복제. outcome별 Counter(`google_places.api.calls`) + Timer(`google_places.api.duration`). `supports/monitoring` 모듈에 등록.

- **FR-OBS-9**: Google Places 응답 Caffeine 캐시 도입. 캐시 키: 키워드 + 필터 파라미터 SHA-256 해시. TTL: 24시간. `GeminiResponseCacheService` 패턴 복제. `CacheConfig`에 캐시 등록 추가.

- **FR-OBS-13**: Logback `TimeBasedRollingPolicy` 기반 일별 로그 파일 회전.
  - 파일 경로: `/var/log/wherewego/spring-%d{yyyy-MM-dd}.log.gz`
  - 보관: `max-history=90`일, `total-size-cap=5GB` (둘 중 먼저 도달하는 조건으로 오래된 파일 삭제)
  - 압축: gzip
  - Docker volume mount: EC2 호스트 경로 → `/var/log/wherewego`
  - Docker json-file 이중 적재 제한: `--log-opt max-size=50m --log-opt max-file=3`
  - 로그 패턴에 `[%X{requestId:-}]` 포함 (FR-OBS-6 결합)
  - 적용 파일: `deploy.yml` docker run 옵션 + `backend/supports/logging/src/main/resources/logback/logback.xml` (커스텀 logback.xml 로딩 시 Spring Boot `logging.*` 자동 바인딩이 미적용되므로 `application.yml`은 사용하지 않음)

### Must — Phase 2.11 핵심 (Phase 2: 임계값 알림)

- **FR-OBS-10**: 일일 합계 임계값 스케줄러. `@Scheduled` cron으로 정시 실행. 집계 방식: **고정 윈도우** (매 정시 스케줄러 실행 시 직전 1시간 누적값 기준).
  - Google Places: 일일 누적 호출 **80%(8,000건) → `notifyWarning`**, **95%(9,500건) → `notifyFailure`**
  - Gemini: **전체 호출 기준** 1시간 내 5xx/timeout 비율 ≥ **10%** → `notifyWarning` (사용자 구분 없이 전체 집계)
  - 쿨다운: **5분** (`ChatbotRateLimitFilter` 패턴 차용). 동일 임계값 단계 기준으로 적용. 80%와 95%는 서로 다른 단계이므로 별도 쿨다운 상태 관리, 동시 발송 가능.
  - 알림 본문: 임계값 종류, 현재 누적값, 한도 대비 비율 포함.

- **FR-OBS-11**: Instagram scraper 3-stage 최종 실패율 추적 + 차단 감지 알림.
  - 집계 방식: **고정 윈도우** — 매 정시 스케줄러 실행 시 직전 1시간 집계. FR-OBS-10 스케줄러와 동일 패턴.
  - 임계값: 3-stage 최종 실패율 ≥ **50%** → `notifyFailure`
  - **호출 0건 처리**: 집계 기간 내 Instagram 호출이 0건이면 실패율 미정의 상태로 간주하여 알림 생략 (오탐 방지)
  - 쿨다운: 5분
  - 알림 본문: 직전 1시간 총 시도 건수, 최종 실패 건수, 실패율 포함.

- **FR-OBS-12**: Slack 알림 본문 전체(3-tier 모두)에 현재 MDC `requestId` 동봉. FR-OBS-6 구현 이후 적용.
  - 단건 실패 알림(`PlaceFallbackOrchestrator` 등): MDC에서 requestId 읽어 본문에 포함.
  - 스케줄러 발송(FR-OBS-10/11): HTTP 요청 컨텍스트 밖에서 실행되므로 requestId 자리에 `SCHEDULER` 고정값 명시.

### Could — Phase 3 이상 (이번 범위 제외)

- **FR-OBS-14**: Google Places 사용자별/IP별 rate limit (트래픽 증가 시)
- **FR-OBS-15**: Kakao Callback 재시도 backoff (5초/15초/45초 3회)
- **FR-OBS-16**: Resilience4j Circuit Breaker + Rate Limiter + Retry
- **FR-OBS-17**: JSON 구조화 로그 포맷(logstash encoder) 전환
- **FR-OBS-18**: CloudWatch Logs awslogs 드라이버 전환 (FR-OBS-13의 점진적 진화 경로)

---

## 수용 기준

| ID | 수용 기준 | 검증 방법 | 연결 FR |
|----|----------|----------|---------|
| AC-1 | 모든 HTTP 요청의 로그에 UUID 형식 requestId가 존재한다 | 임의 API 호출 후 로그에서 `requestId` 키 확인 | FR-OBS-6 |
| AC-2 | 연속 요청 2건의 requestId가 서로 다른 UUID이며, 이전 요청의 requestId가 다음 요청 로그에 오염되지 않는다 | 연속 호출 후 로그 2건의 requestId 값 비교 | FR-OBS-6 |
| AC-3 | Google Places, Gemini, Kakao Callback, Instagram scraper 호출 로그에 `api=`, `op=`, `duration_ms=`, `outcome=`, `cache=` 5개 필드가 모두 존재한다 | 각 외부 API 호출 후 로그 라인 파싱 | FR-OBS-7 |
| AC-4 | `/actuator/prometheus` 엔드포인트에 `google_places_api_calls_total`(outcome 레이블 포함)과 `google_places_api_duration_seconds`가 노출된다 | Prometheus 메트릭 엔드포인트 직접 조회 | FR-OBS-8 |
| AC-5 | 동일 키워드+필터 파라미터로 Google Places를 2회 연속 호출 시, 두 번째 호출의 구조화 로그에 `cache=hit`이 기록되고 외부 네트워크 요청이 발생하지 않는다 | 로그 확인 + WireMock 또는 네트워크 트레이스 | FR-OBS-9 |
| AC-6 | 캐시 TTL 24시간 경과(또는 캐시 강제 만료) 후 재호출 시 `cache=miss`가 기록된다 | 캐시 만료 시뮬레이션 후 로그 확인 | FR-OBS-9 |
| AC-7 | `/var/log/wherewego/` 디렉토리에 `spring-yyyy-MM-dd.log.gz` 파일이 자정마다 생성된다 | 다음날 자정 이후 파일 목록 확인 또는 `RolloverTrigger` 단위 테스트 | FR-OBS-13 |
| AC-8 | 보관 파일 수 90개 초과 또는 총 크기 5GB 초과 시 오래된 파일이 자동 삭제된다 | `backend/supports/logging/src/main/resources/appenders/file-rolling-appender.xml`의 `<maxHistory>90</maxHistory>`, `<totalSizeCap>5GB</totalSizeCap>` 설정값 코드 검토 | FR-OBS-13 |
| AC-9 | Docker 컨테이너 재배포 후에도 이전 날짜 로그 파일이 호스트 볼륨에 보존된다 | `deploy.yml` `-v` 옵션 확인 및 컨테이너 재배포 후 파일 잔존 확인 | FR-OBS-13 |
| AC-10 | Google Places 일일 누적 호출이 8,000건 도달 시 `notifyWarning`이, 9,500건 도달 시 `notifyFailure`가 발송된다 | Counter 값을 8,000/9,500으로 조작한 단위 테스트 | FR-OBS-10 |
| AC-11 | 동일 임계값 단계(예: 80%)에서 5분 이내 재발송이 차단된다. 80%와 95%는 서로 다른 단계이므로 동시 발송이 허용된다 | 쿨다운 단위 테스트: 동일 단계 연속 2회 트리거 시 Slack 발송 1회만 확인. 80%+95% 동시 트리거 시 2회 발송 확인 | FR-OBS-10 |
| AC-12 | Gemini 1시간 내 전체 호출 기준 5xx/timeout 비율이 10% 이상일 때 `notifyWarning`이 발송된다 | 전체 호출 10건 중 5xx 2건(20%) 시나리오 단위 테스트 | FR-OBS-10 |
| AC-13 | Instagram scraper 직전 1시간 고정 윈도우 내 최종 실패율이 50% 이상일 때 `notifyFailure`가 발송된다 | 직전 1시간 내 총 10건 중 최종 실패 5건(50%) 시나리오 단위 테스트 | FR-OBS-11 |
| AC-14 | Instagram scraper 직전 1시간 내 호출 건수가 0건이면 알림이 발송되지 않는다 | 호출 0건 상태에서 스케줄러 실행 시 Slack 미발송 확인 | FR-OBS-11 |
| AC-15 | 단건 실패 Slack 알림 본문에 requestId 필드가 포함된다 | 로컬 Slack 웹훅 Mock 환경에서 알림 본문 JSON 확인 | FR-OBS-12 |
| AC-16 | 스케줄러 발송 Slack 알림 본문의 requestId 항목이 `SCHEDULER`로 표시된다 | 스케줄러 Slack 발송 Mock 테스트에서 본문 내 `SCHEDULER` 문자열 확인. **PR-A는 MDC 주입(`MDC.put("SCHEDULER")`) + SlackNotifier 자동 동봉의 기반 구조만 충족 — 실제 스케줄러→Slack 발송 트리거(`ThresholdMonitorScheduler`)는 PR-B 범위이므로 AC-16의 최종 검증은 PR-B에서 수행한다.** | FR-OBS-12 (PR-B) |

---

## 비기능 요구사항

| 항목 | 기준 | 근거 |
|------|------|------|
| 추가 비용 | 0원 | Logback(파일), Caffeine(인메모리), Micrometer(기존 모듈) 모두 추가 인프라 없음 |
| 외부 API 호출 부가 지연 | 구조화 로그 + 메트릭 기록으로 인한 지연 ≤ 1ms | 인메모리 Counter/로그 쓰기, 네트워크 없음 |
| 쿨다운 | 동일 임계값 단계 기준 5분 인터벌 | 알림 피로 방지. `ChatbotRateLimitFilter` 기존 패턴과 일치 |
| 로그 보관 디스크 상한 | 호스트 디스크 최대 5GB (90일 기준) | `total-size-cap=5GB` 설정으로 자동 관리 |
| Docker json-file 상한 | 호스트 측 최대 150MB (`50m × 3`) | 이중 적재 현상으로 인한 호스트 디스크 낭비 방지. `docker logs` 명령 호환성 유지 |
| Slack 발송 실패 | `SLACK_WEBHOOK_URI` 미설정 시 no-op (기존 `SlackNotifier` 동작 유지) | 로컬/테스트 환경에서 예외 미발생 |
| 알림 정확도 | Google Places 95% 도달 알림 오탐 ≤ 1건/월 | Counter 값 기반 단순 비교로 오탐 요인 최소화 |

---

## 영향도

| 도메인 | 영향 내용 | 해당 FR |
|--------|----------|---------|
| **place** | `GooglePlacesClient` 구조화 로그·메트릭·캐시 추가, `PlaceFallbackOrchestrator` Slack 알림에 requestId 전파 | FR-OBS-7/8/9/12 |
| **chatbot** | `KakaoCallbackClient` 구조화 로그 추가, Instagram scraper 차단 감지 알림 신설 | FR-OBS-7/11 |
| **auth** | Kakao OAuth는 이번 범위 제외. Kakao Callback(챗봇 흐름)만 FR-OBS-7 적용 대상 | FR-OBS-7 |
| **공통 인프라** | MDC 필터 전체 요청 적용, Logback 설정 변경, `deploy.yml` docker run 옵션 변경 | FR-OBS-6/13 |
| **supports/monitoring** | Google Places Micrometer 메트릭 신규 등록 | FR-OBS-8 |

**기존 동작 영향 없는 항목**: `SlackNotifier` 3-tier 메서드 시그니처 변경 없음 (requestId는 알림 본문 내 추가 필드). Gemini 기존 메트릭/캐시/쿼터 동작 변경 없음.

---

## 일정/순서

### Phase 1 — 가시성 (PR-1 권고)

순서 의존성이 있으므로 아래 순서로 구현:

1. **FR-OBS-6**: MDC RequestId 필터 — 이후 모든 로그에 requestId가 붙으므로 최우선
2. **FR-OBS-13**: Logback 일별 회전 설정 — FR-OBS-6과 결합하여 파일 로그에 requestId 포함
3. **FR-OBS-7**: 외부 API 4곳 구조화 로그 — FR-OBS-6 이후 requestId 자동 포함
4. **FR-OBS-8**: Google Places Micrometer 메트릭
5. **FR-OBS-9**: Google Places Caffeine 캐시

### Phase 2 — 임계값 알림 (PR-2 권고, Phase 1 머지 이후)

6. **FR-OBS-12**: Slack 알림 본문 requestId 동봉 (FR-OBS-6 선행 필수)
7. **FR-OBS-10**: 일일 합계 임계값 스케줄러 (Google Places + Gemini)
8. **FR-OBS-11**: Instagram scraper 차단 감지 알림

**PR 분할 권고 이유**: Phase 1은 인프라/가시성 변경, Phase 2는 알림 정책 변경으로 성격이 다름. `deploy.yml` 변경(FR-OBS-13)이 포함된 PR-1은 단독 배포 검증 필요.

---

## 위험과 트레이드오프

| 항목 | 내용 |
|------|------|
| AOP 도입 보류 | 외부 API 호출 지점이 4~5곳에 그쳐 명시적 로깅이 디버깅에 유리. 10곳 이상 확장 시 재검토 |
| Resilience4j 보류 | 1인 개발 + MVP 규모에서 Caffeine + Micrometer + Slack 재조합으로 충분. 외부 의존성/학습 비용 회피 |
| 파일 로그 vs CloudWatch Logs | MVP 규모에서 비용 0의 Logback 파일 회전(FR-OBS-13)으로 충분. EC2 단일 장애점이 사고 분석에 부담이 될 시점에 FR-OBS-18로 점진적 전환 |
| Docker 이중 적재 | Logback 파일 + Docker json-file에 같은 라인 동시 적재. `max-size=50m max-file=3`으로 호스트 측 150MB 상한 관리. `docker logs` 호환성 유지 |
| 임계값 2단계 쿨다운 충돌 | 80%/95% 알림이 같은 실행 사이클 내 동시 발생 가능. 별도 쿨다운 상태로 관리, 양쪽 모두 발송 허용 (AC-11 검증) |
| 스케줄러 requestId 부재 | 정시 스케줄러는 HTTP 요청 컨텍스트 밖에서 실행. 알림 본문에 `SCHEDULER` 고정값으로 명시하여 혼동 방지 (AC-16 검증) |
| Docker 재배포 시 인메모리 Counter 초기화 | 카운터가 0으로 리셋되어 배포 직후 첫 스케줄 사이클의 임계값 알림 신뢰도 저하 가능. 영구 저장 없이 MVP에서 수용 |
| 고정 윈도우 집계 지연 | 고정 윈도우는 정시 직전 발생한 차단을 최대 59분 후에 감지. 슬라이딩 윈도우보다 감지 지연이 크나, MVP 규모(~100건/일)에서 구현 단순성을 우선 |

---

## 3관점 자가 검증

### 유저 경험 (운영자 rnqhstmd 관점)

- Google Places 한도 80% 도달 → Slack `notifyWarning` 수신 → 본문의 현재 누적값/비율 확인 → 사용 패턴 점검 순서가 자연스럽다.
- 95% 도달 `notifyFailure` 수신 → 즉시 핀 등록 흐름 점검 가능. requestId 없어도 `SCHEDULER` 명시로 혼동 없다.
- Instagram 차단 의심 `notifyFailure` 수신 → 직전 1시간 내 실패 건수/비율이 본문에 포함되어 즉시 상황 판단 가능.
- 단건 실패 Slack 수신 → 알림 본문 requestId 복사 → 로그 파일 `grep requestId` → 역추적 1-step. FR-OBS-6/7/12 결합 목표 달성.

### 해석 여지 제거

- "80%"의 기준: 일일 10,000건 무료 한도 기준 8,000건. AC-10에 수치 명시.
- "쿨다운 5분": 동일 임계값 **단계** 기준. 80%와 95%는 별도 단계이므로 동시 발송 가능. AC-11로 검증.
- "고정 윈도우": 매 정시 스케줄러 실행 시 직전 1시간 집계. FR-OBS-10/11 모두 동일 방식.
- "전체 집계": Gemini 5xx 비율은 사용자 구분 없이 1시간 내 전체 호출 기준. AC-12로 검증.
- "최종 실패율": Instagram 3-stage(NO_UA → CHROME_UA → FULL_HEADERS) 모두 실패한 건 기준. 중간 단계 실패는 미포함.
- "호출 0건 알림 생략": 분모 0 상태를 오탐으로 간주. AC-14로 검증.
- "SCHEDULER 고정값": 스케줄러 발송 Slack 알림에서 requestId 자리에 표시. AC-16으로 검증.

### 엣지케이스 커버리지

| 케이스 | 처리 방식 |
|--------|----------|
| Slack 미설정(`SLACK_WEBHOOK_URI` 없음) | 기존 `SlackNotifier` no-op 동작 유지. 로컬/테스트 환경 안전 |
| 80%/95% 동시 도달 (같은 스케줄 실행 사이클) | 별도 쿨다운 상태로 관리, 양쪽 모두 발송 (AC-11) |
| 5분 쿨다운 중 임계값 재초과 | 쿨다운 만료 후 다음 정시 실행에서 발송. 즉시 재발송 없음 |
| 알림 폭주 (매 정시마다 95% 초과 유지) | 5분 쿨다운으로 최소 5분 인터벌 보장 |
| Docker 재배포 시 인메모리 Counter 초기화 | 배포 후 첫 사이클 임계값 알림 신뢰도 저하 가능. MVP에서 수용 |
| MDC clear 누락 시 requestId 오염 | `OncePerRequestFilter` `finally` 블록에서 `MDC.clear()` 필수. AC-2로 검증 |
| Instagram 호출 0건 (야간/저트래픽) | 실패율 미정의 상태로 알림 생략. 오탐 방지 (AC-14) |
| 로그 디렉토리 미생성 | `/var/log/wherewego/` 디렉토리가 호스트에 없으면 Logback 오류. `deploy.yml`에서 `mkdir -p` 또는 volume 선언으로 사전 생성 필요 |
| Gemini 호출 0건 (1시간 내) | 분모 0 → 5xx 비율 미정의. FR-OBS-11과 동일 정책 적용: 호출 0건이면 알림 생략 |
