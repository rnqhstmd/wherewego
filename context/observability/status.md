# observability 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현

## 요구사항

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-OBS-1 | Spring Boot Actuator + Prometheus + Grafana JVM 대시보드 (인프라 메트릭 기반) | ✅ | [#1](https://github.com/rnqhstmd/wherewego/pull/1) — Phase 0, `supports/monitoring` 모듈 |
| FR-OBS-2 | Gemini 도메인 Micrometer 메트릭 (outcome별 Counter/Timer) | ✅ | [#15](https://github.com/rnqhstmd/wherewego/pull/15) — `GeminiUsageMetrics` |
| FR-OBS-3 | Gemini 사용자별 일일 쿼터 (Caffeine, 50회/일/사용자, `GEMINI_DAILY_QUOTA_PER_USER`) | ✅ | [#15](https://github.com/rnqhstmd/wherewego/pull/15) — `GeminiUserQuotaService` |
| FR-OBS-4 | Gemini 응답 SHA-256 캐시 (24h, 2000 항목) | ✅ | [#15](https://github.com/rnqhstmd/wherewego/pull/15) — `GeminiResponseCacheService` |
| FR-OBS-5 | Slack Incoming Webhook 3-tier (failure/warning/notify, Block Kit, 2초 타임아웃, env 미설정 시 no-op) | ✅ | (선행) — `SlackNotifier` |
| FR-OBS-6 | MDC RequestId 필터 (UUID 발급 + logback `%X{requestId}` 패턴) | ⬜ | — Phase 2.11 계획 (Phase 1 항목) |
| FR-OBS-7 | 외부 API 호출 공통 구조화 로그 (`api=`, `op=`, `duration_ms=`, `outcome=`, `cache=`) — 4곳: Gemini/Google Places/Kakao Callback/Instagram scraper | ⬜ | — Phase 2.11 계획 (Phase 1 항목) |
| FR-OBS-8 | Google Places Micrometer 메트릭 (outcome별 Counter/Timer, Gemini 패턴 복제) | ⬜ | — Phase 2.11 계획 (Phase 1 항목). [[place]] FR-PLC-9 참조 |
| FR-OBS-9 | Google Places 응답 Caffeine 캐시 (24h, 키워드+필터 SHA-256 키) | ⬜ | — Phase 2.11 계획 (Phase 1 항목) |
| FR-OBS-10 | 일일 합계 임계값 스케줄러 (`@Scheduled` 정시. Google Places 80%(8K)/95%(9.5K), Gemini 5xx 10%(1h)) | ⬜ | — Phase 2.11 계획 (Phase 2 항목). 5~30분 쿨다운 |
| FR-OBS-11 | Instagram scraper 3-stage 최종 실패율 추적 + 차단 감지 알림 (1시간 윈도우, 50% 임계값 → notifyFailure) | ⬜ | — Phase 2.11 계획 (Phase 2 항목). [[place]] FR-PLC-10 참조 |
| FR-OBS-12 | Slack 알림 본문에 RequestId 동봉 (FR-OBS-6 후행) | ⬜ | — Phase 2.11 계획 (Phase 2 항목) |
| FR-OBS-13 | 일별 로그 파일 회전 + 90일 보관 (Logback `TimeBasedRollingPolicy`, `/var/log/wherewego/spring-%d{yyyy-MM-dd}.log.gz`, `max-history=90`, `total-size-cap=5GB`, gzip 압축). Docker volume mount + Docker json-file `max-size=50m max-file=3` 이중 적재 제한. 로그 패턴에 `[%X{requestId:-}]` 포함으로 FR-OBS-6과 결합 | ⬜ | — Phase 2.11 계획 (Phase 1 항목). `deploy.yml` docker run 옵션 + `application.yml` `logging.file.name`/`logback.rollingpolicy` 설정 |

## 후속 작업 (Phase 3 이상)

- **FR-OBS-14**: Google Places 사용자별/IP별 rate limit (`ChatbotRateLimitFilter` 패턴 차용) — 트래픽 증가 후 검토
- **FR-OBS-15**: Kakao Callback 재시도 (5초/15초/45초 3회 backoff) — `KakaoCallbackClient.push():67` 한 곳 수정
- **FR-OBS-16**: Resilience4j 도입 (Circuit Breaker + Rate Limiter + Retry) — 외부 API 호출 5곳 이상 또는 트래픽 임계 시 검토
- **FR-OBS-17**: JSON 구조화 로그 포맷(logstash encoder) 전환 — 운영 로그 파싱 자동화 시점
- **FR-OBS-18**: CloudWatch Logs awslogs 드라이버 전환 + retention 90일 + IAM 권한 (`logs:CreateLogStream`, `logs:PutLogEvents`) — EC2 단일 장애점 제거 시점. FR-OBS-13의 자연스러운 진화 경로

## 트레이드오프 기록

- **AOP 도입 보류**: 외부 API 호출 지점이 4~5곳에 그쳐 명시적 로깅이 디버깅 용이. 10곳 이상 확장 시 AOP 재검토
- **Resilience4j 보류**: 1인 개발 + MVP 규모에서 Caffeine + Micrometer + Slack 재조합으로 충분 (외부 의존성/학습 비용 회피)
- **임계값 2단계 유지**: 80%/95% 동시 알림 + 5분 쿨다운으로 알림 피로 방지
- **Phase 0 메트릭 모듈 재활용**: `supports/monitoring` 모듈에 신규 Counter 추가 (단일 application 메트릭 네임스페이스 유지)
- **파일 로그 vs CloudWatch Logs**: MVP 규모에서는 비용 0의 Logback 파일 회전(FR-OBS-13)이 충분. EC2 단일 장애점이 사고 분석에 부담이 될 시점에 FR-OBS-18로 전환. 둘은 점진적 진화 경로이며 동시 도입은 비용 낭비
- **Docker stdout 이중 적재 제한**: Logback 파일 적재 시 Docker json-file에도 동일 라인이 적재됨 → `max-size=50m max-file=3`로 호스트 측 적재 150MB 상한. `docker logs` 명령은 그대로 동작
