# observability 용어 사전

| 용어 | 설명 |
|------|------|
| MDC | Mapped Diagnostic Context. SLF4J에서 스레드별 컨텍스트 키를 로그 패턴에 자동 삽입 (예: `%X{requestId}`) |
| RequestId | 요청별 UUID. `OncePerRequestFilter`에서 발급하여 MDC와 Slack 알림 본문에 동봉 |
| outcome | 외부 API 호출 결과 분류. `success`/`empty`/`cached`/`quota_exceeded`/`rate_limited`/`timeout`/`error`/`server_error`/`disabled` (Gemini/Google Places 공통). PR-B에서 `server_error`(HTTP 5xx만)를 `error`에서 분리 |
| server_error outcome | Gemini API의 HTTP 500/502/503/504 응답으로 한정한 outcome 라벨. `OUTCOME_SERVER_ERROR = "server_error"`. 4xx(429 제외)는 `OUTCOME_ERROR` 유지. PR-B에서 `onStatus(is5xxServerError)` 분리로 도입 |
| 쿨다운(cooldown) | 같은 종류의 Slack 알림 중복 발송을 막는 인터벌. `ChatbotRateLimitFilter` 5분 + PR-B `ThresholdMonitorScheduler` 5분(Gemini/Instagram 모두). `ConcurrentHashMap<String,Long>` Epoch ms |
| 임계값(threshold) | 메트릭 누적값이 도달 시 알림을 트리거하는 기준. PR-B 현재 임계: Gemini server_error 10%/1h, Instagram 차단율 50%/1h. Google Places 80%/95%는 Phase 3 보류 |
| ThresholdMonitorScheduler | `infrastructure.monitoring` 패키지의 `@Scheduled(fixedRate=1h, initialDelay=1h)` 컴포넌트. 1시간 윈도우로 Gemini server_error 비율과 Instagram 차단율을 평가, 각 check를 개별 try-catch(Exception)로 격리 |
| InstagramBlockedRateTracker | `synchronized` 단일 락으로 `attempts`/`blocked`/`lastBlockedUrl` 세 필드의 증가·스냅샷·리셋을 한 락에서 원자 스왑하는 1시간 윈도우 상태 저장소. `@Scheduled` 없음 — 스케줄러가 `flushWindow()` 호출로 윈도우 종료 트리거 |
| flushWindow | 윈도우 종료 시 호출되는 메서드. 현재 스냅샷을 캡처하고 즉시 모든 카운터를 0으로 리셋한 뒤 `Snapshot(attempts, blocked, lastBlockedUrl)`을 반환. BR-5 "판단 → 발송 → 리셋" 순서를 락 안에서 단일 원자 연산으로 보장 |
| initialDelay | `@Scheduled(initialDelay=...)`로 첫 tick을 지연시키는 옵션. PR-B는 `WINDOW_MS`(1h)로 설정하여 배포 직후 누적 카운터가 첫 윈도우 델타로 잡혀 오탐되는 것을 방지 |
| WINDOW_MS | ThresholdMonitorScheduler의 윈도우 길이 코드 상수(1시간, 3,600,000ms). yml 외부화하지 않고 `@Scheduled(fixedRate)`와 단일 진실 공급원으로 묶어 운영 중 어긋남 위험 차단 |
| MeterRegistry 델타 차분 | Counter는 단조 증가 누적값이므로 1h 델타를 얻으려면 직전 스냅샷과의 차분 필요. `previousGeminiSnapshot` Map에 outcome별 누적값 보관 + `getOrDefault(outcome, 0.0)`로 lazy 등장 outcome 안전 처리 |
| List.copyOf immutability | Caffeine 캐시 put/get 시 `List.copyOf`로 immutable 복사본을 만들어 외부 변형이 캐시 내부를 오염시키지 못하도록 차단하는 패턴. PR-B `GooglePlacesResponseCacheService` |
| 캐시 격리 | 관측/알림 코드(메트릭 발급, 캐시 조회/적재, Slack 발송, 트래커 호출, 스케줄러 실행)의 RuntimeException이 본 기능(외부 API 호출, 사용자 응답)을 차단하거나 서버를 다운시키지 않도록 try-catch로 분리하는 정책. PR-B NFR-1~6 |
| 한도(quota) | 외부 API 제공자가 정한 무료 사용 상한. Gemini는 사용자별 일일 50회, Google Places는 월 $200 |
| 폴백(fallback) | 외부 API 실패 시 대체 경로. 동기→비동기, 메인 API→폴백 API |
| circuit breaker | 연속 실패 시 일정 시간 호출 자체를 차단하는 패턴. Phase 3 이상에서 Resilience4j 도입 검토 |
| backoff | 재시도 간격을 점진적으로 늘리는 전략. `KakaoCallbackClient` 5초/15초/45초 3회 계획 |
| 구조화 로그 | key=value 또는 JSON 형식으로 파싱 가능한 로그. 현재 메시지 템플릿(`cause={}`)만 사용 |
| notifyFailure / notifyWarning / notify | `SlackNotifier`의 3-tier 알림 메서드 (🚨 빨강 / ⚠️ 노랑 / ✅ 초록) |
| 3-tier 알림 | Slack 메시지를 심각도별로 분리하는 정책. failure(즉시 대응), warning(임계값 진입), notify(긍정 신호) |
| 사각지대(blind spot) | 기존 대시보드/메트릭으로 관측되지 않는 영역. 본 도메인은 외부 API 호출 사각지대를 우선 해소 |
| TimeBasedRollingPolicy | Logback의 시간 기반 로그 회전 정책. `%d{yyyy-MM-dd}` 패턴으로 매일 자정 자동 회전 + `max-history`로 보관 일수 제한 + 압축 지원 |
| max-history / total-size-cap | Logback의 보관 정책 옵션. 본 프로젝트는 90일/5GB. 둘 중 먼저 도달하는 조건에서 오래된 파일 삭제 |
| 이중 적재 | Logback이 파일에 쓰면서 동시에 stdout으로도 출력 → Docker json-file 드라이버가 같은 라인을 또 적재하는 현상. `--log-opt max-size/max-file`로 호스트 측 적재 상한 설정 |
| awslogs 드라이버 | Docker의 CloudWatch Logs 통합 로그 드라이버. EC2 IAM 역할 권한(`logs:CreateLogStream`, `logs:PutLogEvents`) 필요 |
