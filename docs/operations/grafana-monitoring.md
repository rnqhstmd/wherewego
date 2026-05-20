# Grafana 모니터링 + Prometheus + Actuator

> JVM/HTTP 메트릭을 Spring Boot Actuator로 노출 → Prometheus가 스크레이프 → Grafana가 시각화.

---

## 전체 구조

```
┌──────────────────────────┐
│  wherewego-api (port 8080) │ — 비즈니스 API
│  Actuator    (port 8081)   │ — 메트릭/health 노출
└──────────────────────────┘
            │
            │  GET /actuator/prometheus  (30s 주기)
            ▼
┌──────────────────────────┐
│  Prometheus  (port 9090)   │
│  - host.docker.internal:8081 scrape
│  - jvm_*, http_server_*, gemini_*
└──────────────────────────┘
            │
            │  PromQL
            ▼
┌──────────────────────────┐
│  Grafana     (port 3000)   │
│  - JVM Overview 대시보드
│  - admin/admin
└──────────────────────────┘
```

---

## 빠른 시작

### 로컬 (Docker Compose)

```bash
# 1) Prometheus + Grafana 띄우기
docker compose -f backend/docker/monitoring-compose.yml up -d

# 2) Spring Boot 앱 띄우기 (Actuator port 8081)
./gradlew :apps:wherewego-api:bootRun

# 3) 검증
bash backend/docker/verify-observability.sh

# 4) 접속
# Prometheus:  http://localhost:9090
# Grafana:     http://localhost:3000  (admin/admin)
```

### 정지

```bash
docker compose -f backend/docker/monitoring-compose.yml down
# 데이터까지 삭제: -v 플래그 (현재는 volume 미설정, 재시작 시 메트릭 휘발)
```

---

## Docker Compose 구성

`backend/docker/monitoring-compose.yml`:

```yaml
version: '3'
services:
  prometheus:
    image: prom/prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./grafana/prometheus.yml:/etc/prometheus/prometheus.yml

  grafana:
    image: grafana/grafana
    ports:
      - "3000:3000"
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning
    environment:
      - GF_SECURITY_ADMIN_USER=admin
      - GF_SECURITY_ADMIN_PASSWORD=admin

networks:
  default:
    driver: bridge
```

**핵심 결정**:
- volume 미사용 → 컨테이너 재시작 시 Prometheus TSDB 휘발 (단기 모니터링용).
- `host.docker.internal`로 호스트의 Spring Boot 앱(`localhost:8081`) 접근 (macOS/Windows).
- Linux EC2 운영 시 `host.docker.internal` → `172.17.0.1` 또는 호스트 IP 명시.

---

## Prometheus 설정

`backend/docker/grafana/prometheus.yml`:

```yaml
global:
  scrape_interval: 30s

scrape_configs:
  - job_name: 'spring-boot-app'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8081']
```

- **30초 주기**: TSDB 부하 vs 실시간성 균형. 1초까지 줄여도 무방하나 인메모리 Counter라 그렇게까지 자주 보지 않음.
- **타겟**: `host.docker.internal:8081` — Actuator 전용 포트. 비즈니스 API(8080)와 분리.

---

## Spring Boot Actuator 설정

`backend/supports/monitoring/src/main/resources/monitoring.yml`:

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
    tags:
      application: ${spring.application.name}
  endpoints:
    web:
      exposure:
        include:
          - health
          - prometheus
  endpoint:
    health:
      probes:
        enabled: true
      group:
        liveness:
          show-components: always
          include:
            - livenessState
        readiness:
          show-components: always
    prometheus:
      access: read_only
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true
  server:
    port: 8081
  observations:
    annotations:
      enabled: true
    key-values:
      application: ${spring.application.name}
```

**노출 엔드포인트**:
- `GET http://localhost:8081/actuator/health` — liveness/readiness probe
- `GET http://localhost:8081/actuator/prometheus` — 모든 메트릭 (Prometheus 형식)

**`http.server.requests`** percentile histogram 활성화 — Grafana에서 p50/p95/p99 응답시간 시각화 가능.

### Actuator 보안

`backend/apps/wherewego-api/src/main/java/com/wherewego/config/security/ActuatorIpRestrictionFilter.java`:

- **운영(prod)에서는 localhost만 허용** — 외부에서 메트릭 노출 차단.
- Prometheus가 같은 EC2에서 돌면 OK. 다른 EC2에서 스크레이프하려면 IP allowlist 확장 필요.

---

## 메트릭 카탈로그

### JVM (Actuator 기본 — micrometer-jvm)

| 메트릭 | 설명 |
|--------|------|
| `jvm_memory_used_bytes{area="heap"}` | Heap 사용량 |
| `jvm_memory_used_bytes{area="nonheap"}` | Non-heap (Metaspace, CodeCache) |
| `jvm_memory_max_bytes` | 최대 할당 |
| `jvm_gc_pause_seconds` | GC 정지 시간 (Timer) |
| `jvm_threads_live_threads` | 활성 스레드 수 |
| `jvm_threads_states_threads` | 스레드 상태별 (runnable/blocked/waiting) |
| `process_cpu_usage` | 프로세스 CPU 사용률 |
| `system_load_average_1m` | 시스템 1분 부하 |

### HTTP (Actuator http.server.requests)

| 메트릭 | 라벨 | 설명 |
|--------|------|------|
| `http_server_requests_seconds_count` | `uri`, `method`, `status` | 요청 카운트 |
| `http_server_requests_seconds_sum` | 동일 | 누적 응답시간 |
| `http_server_requests_seconds_max` | 동일 | 최대 응답시간 |
| `http_server_requests_seconds_bucket` | `le` (히스토그램) | p50/p95/p99 계산용 |

### 도메인 메트릭 (현재: Gemini만)

`backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/gemini/GeminiUsageMetrics.java`:

| 메트릭 | 라벨 | 설명 |
|--------|------|------|
| `gemini_calls_total` | `outcome` ∈ {success, empty, cached, rate_limited, timeout, error} | 호출 카운트 |
| `gemini_call_duration_seconds` | `outcome` 동일 | 응답시간 |

### PR-B 추가 예정

- `google_places_api_calls_total{outcome=...}` (FR-OBS-8)
- `google_places_api_duration_seconds{outcome=...}` (FR-OBS-8)
- (옵션) Instagram scrape outcome — `InstagramFailureTracker`가 별도 카운터로 두는 게 자연스러움

---

## Grafana 대시보드

### 자동 등록 (Provisioning)

`backend/docker/grafana/provisioning/`:

```
provisioning/
├── datasources/
│   └── datasource.yml      # Prometheus 연결 자동 등록
└── dashboards/
    ├── dashboard.yml       # 대시보드 자동 로딩
    └── jvm-overview.json   # JVM 메트릭 대시보드
```

컨테이너 시작 시 Grafana가 위 폴더를 스캔하여:
1. Prometheus를 데이터소스로 자동 등록.
2. JSON 대시보드를 "Dashboards" 메뉴에 자동 노출.

### 현재 대시보드

**JVM Overview** (`jvm-overview.json`):
- Heap 메모리 사용량/최대치
- GC 정지 시간 추이
- 스레드 수
- CPU 사용률
- 프로세스 메모리 매트릭스

### 추가 대시보드 만들기

1. Grafana UI에서 새 대시보드 생성 (PromQL 쿼리 작성).
2. "Dashboard settings" → "JSON Model" 복사.
3. `backend/docker/grafana/provisioning/dashboards/{이름}.json`으로 저장.
4. `docker compose down && up -d` 또는 Grafana 재시작 → 자동 로딩.

**예시 PromQL**:

```promql
# Heap 사용률 (%)
100 * jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}

# HTTP p95 응답시간 (5분 윈도우)
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))

# Gemini 에러율 (1분 윈도우)
sum(rate(gemini_calls_total{outcome=~"error|timeout"}[1m])) / sum(rate(gemini_calls_total[1m]))

# Gemini 응답시간 평균
rate(gemini_call_duration_seconds_sum[5m]) / rate(gemini_call_duration_seconds_count[5m])
```

---

## 검증 스크립트

`backend/docker/verify-observability.sh`:

```bash
# 사전 조건:
# 1. docker compose -f backend/docker/infra-compose.yml up -d
# 2. docker compose -f backend/docker/monitoring-compose.yml up -d
# 3. ./gradlew :apps:wherewego-api:bootRun
# 4. 로컬에 curl, jq 설치
```

**검증 항목**:
- **AC-14**: Prometheus의 `spring-boot-app` target이 `up` 상태인가?
- **AC-15**: `jvm_memory_used_bytes{area="heap"}` 쿼리가 데이터를 반환하는가?

**실행**:
```bash
bash backend/docker/verify-observability.sh
```

성공 출력:
```
[verify-observability] AC-14 PASS: spring-boot-app target이 'up' 상태입니다
[verify-observability] AC-15 PASS: heap memory 지표 조회 성공
[verify-observability] 모든 검증 통과 (AC-14, AC-15)
```

---

## 운영 환경 배포

### EC2에서 운영 시 옵션

| 옵션 | 장점 | 단점 |
|------|------|------|
| **동일 EC2에 docker compose** | 비용 0, 단순 | EC2 죽으면 모니터링도 같이 죽음 |
| **별도 EC2 또는 EKS** | 격리, 가용성 | 추가 비용·관리 |
| **AWS Managed Prometheus + Grafana** | 매니지드, 가용성 | 비용 (월 ~$10+) |

**현재 정책**: 동일 EC2 또는 로컬 개발 시점에만 모니터링 활성. 운영 시 모니터링 필요성이 명확해지면 별도 EC2로 분리.

### Actuator 인증

운영(prod)에서는 `ActuatorIpRestrictionFilter`로 **localhost만 허용**:
- 같은 EC2에서 Prometheus 스크레이프 OK.
- 외부 접근은 차단됨. SSH 터널로 Grafana만 외부 노출.

---

## 트러블슈팅

### Prometheus target이 "DOWN" 상태

1. `curl http://localhost:8081/actuator/prometheus` 응답 확인.
2. Spring Boot 앱이 8081로 떠 있는지: `lsof -i :8081`
3. `prometheus.yml`의 `host.docker.internal` 해석 확인 (Linux의 경우 `172.17.0.1` 또는 호스트 IP로 변경).
4. `ActuatorIpRestrictionFilter`가 Prometheus 컨테이너 IP를 차단했는지 — `application.yml`에서 allowlist 추가.

### Grafana 대시보드가 비어 있음

1. 데이터소스 연결: Grafana UI → "Connections" → Prometheus → "Test"
2. Prometheus 쿼리: `up` 직접 입력 → 결과 1이 나와야 함.
3. 시간 범위: 우상단 시간 선택기가 미래 시각이 아닌지.
4. provisioning 파일 권한: 컨테이너에서 마운트된 파일이 읽기 가능한지.

### `jvm_memory_used_bytes` 값이 안 나옴

1. Actuator에 `prometheus` 엔드포인트 노출됐는지 — `management.endpoints.web.exposure.include`에 `prometheus` 포함.
2. Spring profile이 dev/prod인지 (monitoring.yml은 메인 application.yml에 import되어야 활성).
3. Micrometer 의존성 — `supports/monitoring` 모듈이 `apps/wherewego-api`에 포함되어 있어야 함.

---

## PR-B 이후 확장 예정

- **Google Places 대시보드 신설**: outcome별 호출량 + 응답시간 + 에러율
- **알림 통합**: Prometheus AlertManager 도입 검토 (현재는 Spring 코드 측 SlackNotifier로 충분)
- **분산 추적**: Spring Cloud Sleuth / Micrometer Tracing 도입 시 `traceId`/`spanId` 메트릭 추가

---

## 관련 자산

- 신규 도입: [Phase 2.11 PR-A](https://github.com/rnqhstmd/wherewego/pull/28) — RequestId/구조화 로그/Slack 동봉/파일 회전
- Phase 0 모니터링 기반: PR [#1](https://github.com/rnqhstmd/wherewego/pull/1)
- 로그 시스템: [logging.md](logging.md)
- Slack 알림: [slack-alerts.md](slack-alerts.md)
- 도메인: [context/observability/architecture.md](../../context/observability/architecture.md)
