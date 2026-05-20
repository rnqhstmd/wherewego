# observability 용어 사전

| 용어 | 설명 |
|------|------|
| MDC | Mapped Diagnostic Context. SLF4J에서 스레드별 컨텍스트 키를 로그 패턴에 자동 삽입 (예: `%X{requestId}`) |
| RequestId | 요청별 UUID. `OncePerRequestFilter`에서 발급하여 MDC와 Slack 알림 본문에 동봉 |
| outcome | 외부 API 호출 결과 분류. `success`/`empty`/`cached`/`quota_exceeded`/`rate_limited`/`timeout`/`error` (Gemini 패턴) |
| 쿨다운(cooldown) | 같은 종류의 Slack 알림 중복 발송을 막는 인터벌. `ChatbotRateLimitFilter`는 5분 사용 |
| 임계값(threshold) | 메트릭 누적값이 도달 시 알림을 트리거하는 기준. Google Places 일일 호출 80%/95% 2단계 |
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
