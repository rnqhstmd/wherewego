# 운영 모니터링 가이드 (Operations)

> wherewego의 운영 가시성·알림·관측 자산 통합 인덱스. 운영자 1인(rnqhstmd) 기준.

이 문서는 **현재까지 구축된 운영 자산의 방법과 경우의 수**를 정리합니다. 신규 도입은 [Phase 2.11 PR-A](../../context/observability/README.md) (FR-OBS-6/7/12/13)에서 완성되었습니다.

---

## 운영 자산 3축

| 영역 | 책임 | 상세 문서 |
|------|------|----------|
| **로그 수집** | 애플리케이션 로그를 콘솔/파일/Slack 다중 적재 | [logging.md](logging.md) |
| **Slack 알림** | 비즈니스 이벤트(SlackNotifier) + 시스템 ERROR(Logback appender) 분리 발송 | [slack-alerts.md](slack-alerts.md) |
| **Grafana 모니터링** | JVM/HTTP 메트릭 Prometheus 스크레이프 + Grafana 시각화 | [grafana-monitoring.md](grafana-monitoring.md) |

---

## 한눈에 보는 환경별 동작

| 환경 (Spring profile) | 콘솔 로그 | 파일 로그 | Slack appender (ERROR) | SlackNotifier API | Prometheus | Grafana |
|----------------------|----------|----------|-----------------------|-------------------|------------|---------|
| `local` / `local-dev` / `test` | plain (DEBUG) | ✗ | ✗ | no-op (env 미설정) | ✗ (옵션) | ✗ (옵션) |
| `dev` | JSON (DEBUG) | ✓ (`/var/log/wherewego/`) | ✓ (ASYNC, ERROR↑) | ✓ (env 설정 시) | ✓ | ✓ |
| `prod` | JSON (INFO) | ✓ (`/var/log/wherewego/`) | ✓ (ASYNC, ERROR↑) | ✓ | ✓ | ✓ |

**핵심 차별**:
- `local/test`는 **콘솔만**. 파일/Slack 미적용으로 개발 편의 + 노이즈 0.
- `dev/prod`는 **3중 적재** (콘솔 + 파일 + Slack appender) + Prometheus 메트릭 노출.

---

## 빠른 시작 명령어

### EC2 운영 로그 조회 (배포 후 트러블슈팅)

```bash
# 최근 로그 (활성 파일)
sudo tail -f /var/log/wherewego/spring.log

# 특정 날짜 로그 (압축 상태로 조회)
zcat /var/log/wherewego/spring-2026-05-20.log.gz | less

# requestId로 역추적 (Slack 알림 본문 첫 키 복사 → grep)
grep "01988e2c-1234-..." /var/log/wherewego/spring*.log.gz

# Docker 컨테이너 로그 (json-file, 150MB 상한)
docker logs wherewego-api --tail 200 -f
```

### 로컬에서 모니터링 스택 띄우기

```bash
# Prometheus + Grafana (Docker Compose)
docker compose -f backend/docker/monitoring-compose.yml up -d

# Spring Boot 앱 (Actuator port 8081)
./gradlew :apps:wherewego-api:bootRun

# 검증 스크립트 (Prometheus target up + JVM heap 조회)
bash backend/docker/verify-observability.sh

# 브라우저로 Grafana 접속
# http://localhost:3000 (admin/admin)
```

### Slack Webhook 설정

```bash
# 환경 변수 (운영 시 secrets)
export SLACK_WEBHOOK_URI="https://hooks.slack.com/services/..."
export SLACK_CHANNEL="#wherewego-alerts"
export SLACK_USERNAME="wherewego-prod"

# 미설정 시 자동 no-op (예외 발생 안 함)
```

---

## 운영 시나리오 — 경우의 수

| 시나리오 | 무슨 일이 일어나는가 | 대응 / 안전장치 |
|---------|---------------------|---------------|
| **EC2 디스크 소진** | `/var/log/wherewego/` 파일 누적 | `maxHistory=90`일 + `totalSizeCap=5GB` 자동 삭제. Docker json-file은 `--log-opt max-size=50m max-file=3`로 150MB 상한 |
| **Slack 미설정 (로컬/테스트)** | `SLACK_WEBHOOK_URI` 빈 문자열 | `SlackNotifier`는 no-op, Logback Slack appender는 `defaultValue=""`로 발송 안 함. 예외 미발생 |
| **알림 폭주** | 같은 에러가 단시간 반복 | `SlackNotifier`(API)는 호출자 측 쿨다운 (예: `ChatbotRateLimitFilter` 5분). Logback ASYNC-SLACK은 비동기 + ERROR 임계값. PR-B FR-OBS-10에서 임계값 스케줄러 추가 예정 |
| **외부 `X-Request-Id` 헤더 주입** | 공격자가 임의 UUID 시도 | `RequestIdFilter`는 외부 헤더 무시, 항상 자체 UUID 발급 (스푸핑 차단) |
| **비동기 워커에서 Slack 발송** | servlet MDC 없음 | `PlaceFallbackOrchestrator.runAsync`는 명시 MDC 캡처 → 워커 setContextMap. `PendingInstagramAutoSaveScheduler`는 `MDC.put("SCHEDULER")` 명시 |
| **Docker 재배포** | 컨테이너 `docker stop/rm/run` | 호스트 `-v /var/log/wherewego` volume mount로 파일 보존. 인메모리 카운터(Gemini quota 등)는 리셋됨 (MVP 수용) |
| **JSON 로그 파싱** | dev/prod에서 logstash 포맷 | Spring Boot 3.4+ `structured-console-appender`가 MDC 전체를 자동 직렬화. 별도 인코더 의존성 0 |
| **로그 인젝션 시도** | URL 파라미터에 CRLF 포함 | `InstagramScraperClient`에서 `safeForLog(url)`로 `\r\n` → `_` 이스케이프 |
| **EC2 단일 장애점** | 인스턴스 죽으면 로그도 소실 | 현재는 수용. 향후 [FR-OBS-18 CloudWatch Logs](../../context/observability/status.md) 전환 검토 |

---

## 비용 / 인프라

- **추가 비용 0원**: Logback(파일), Caffeine(인메모리), Micrometer(기존 모듈), Slack Webhook(무료), Prometheus/Grafana(self-hosted) 모두 외부 결제 없음.
- **EC2 디스크**: 호스트 측 최대 5GB (Logback) + 150MB (Docker json-file) = ~5.15GB.
- **모니터링 스택**: 별도 호스트 또는 EC2 동일 인스턴스에 docker compose로 운영. 운영 비용은 호스트 비용에 포함.

---

## 향후 확장 (Phase 2.11 PR-B 및 Phase 3+)

| 항목 | 단계 | 비고 |
|------|------|------|
| Google Places 메트릭 + 캐시 | PR-B | FR-OBS-8/9 |
| Gemini 5xx 임계값 스케줄러 | PR-B | FR-OBS-10 (Gemini 부분만, Google Places 부분 보류) |
| Instagram 차단 감지 알림 | PR-B | FR-OBS-11 |
| Google Places rate limit | Phase 3 | FR-OBS-14 |
| Kakao Callback 재시도 | Phase 3 | FR-OBS-15 |
| Resilience4j Circuit Breaker | Phase 3 | FR-OBS-16 |
| CloudWatch Logs 전환 | EC2 단일 장애점 부담 시 | FR-OBS-18 |

---

## 관련 도메인 문서

- [context/observability/README.md](../../context/observability/README.md) — 도메인 PRD/배경
- [context/observability/architecture.md](../../context/observability/architecture.md) — 4-레이어 설계
- [context/observability/status.md](../../context/observability/status.md) — FR-OBS-1~18 구현 추적
- [context/observability/glossary.md](../../context/observability/glossary.md) — 용어 사전
