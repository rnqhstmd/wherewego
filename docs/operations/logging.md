# 로그 수집 (Logging)

> 콘솔/파일/Slack 다중 적재 방식과 환경별 차이, 회전·보관 정책, MDC 기반 추적 ID.

---

## 단일 진실원천

**`backend/supports/logging/src/main/resources/logback/logback.xml`**

`application.yml`에서 `logging.config: classpath:logback/logback.xml`로 명시 로딩되므로 Spring Boot의 자동 `logging.file.name`/`logging.logback.rollingpolicy.*` 바인딩은 **무시됩니다**. 모든 변경은 위 파일을 통해 합니다.

---

## 환경별 동작 (Spring profile)

### `local` / `local-dev` / `test`

```xml
<springProfile name="local,local-dev,test">
    <include resource="appenders/plain-console-appender.xml"/>
    <logger name="com.wherewego" level="DEBUG"/>
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</springProfile>
```

- **콘솔만 적재**. 파일/Slack 없음.
- `com.wherewego` 패키지는 DEBUG, 외부 라이브러리는 INFO.
- 패턴: Spring Boot 기본 `CONSOLE_LOG_PATTERN` + `[%X{requestId:-}]` 삽입.

### `dev` / `prod`

```xml
<springProfile name="dev">
    <include resource="properties/slack-log-dev.xml"/>
    <include resource="appenders/json-console-appender.xml"/>
    <include resource="appenders/file-rolling-appender.xml"/>
    <include resource="appenders/slack-appender.xml"/>
    <logger name="com.wherewego" level="DEBUG"/>
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
        <appender-ref ref="ASYNC-SLACK"/>
    </root>
</springProfile>
```

- **3중 적재**: JSON 콘솔 + 파일 + Slack appender.
- `prod`는 `com.wherewego` 레벨이 `INFO` (dev는 `DEBUG`).
- 파일/Slack 설정은 dev/prod 동일.

---

## Appender 4종

| Appender | 파일 | 활성 환경 | 역할 |
|----------|------|----------|------|
| `CONSOLE` (plain) | `appenders/plain-console-appender.xml` | local/test | 사람이 읽기 좋은 텍스트 |
| `CONSOLE` (json) | `appenders/json-console-appender.xml` | dev/prod | Spring Boot 3.4+ structured-logstash format. MDC 자동 직렬화 |
| `FILE` | `appenders/file-rolling-appender.xml` | dev/prod | 일별 회전 + 90일/5GB gzip 보관 |
| `SLACK` / `ASYNC-SLACK` | `appenders/slack-appender.xml` | dev/prod | ERROR 레벨 비동기 발송 |

> JSON 콘솔과 plain 콘솔은 둘 다 appender 이름이 `CONSOLE`이지만 다른 환경에서만 활성화됩니다 (동시 등록 충돌 없음).

---

## 파일 회전 정책 (FILE appender)

```xml
<appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>/var/log/wherewego/spring.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <fileNamePattern>/var/log/wherewego/spring-%d{yyyy-MM-dd}.log.gz</fileNamePattern>
        <maxHistory>90</maxHistory>
        <totalSizeCap>5GB</totalSizeCap>
        <cleanHistoryOnStart>true</cleanHistoryOnStart>
    </rollingPolicy>
    <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{requestId:-}] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
</appender>
```

**동작**:
- **활성 로그**: `/var/log/wherewego/spring.log` (실시간)
- **자정 회전**: `spring.log` → `spring-2026-05-19.log.gz` (gzip 압축)
- **삭제 트리거**: 보관 파일 수 > 90개 **또는** 총 크기 > 5GB (먼저 도달하는 조건)
- **재시작 정책**: `cleanHistoryOnStart=true` → 컨테이너 재배포 시 보관 정책 초과 파일 즉시 정리
- **압축률**: 일반적으로 10~20배 (텍스트 로그 기준)

**EC2 디스크 사용량 상한**:
- Logback 파일: 최대 5GB
- Docker json-file (`--log-opt max-size=50m max-file=3`): 최대 150MB (이중 적재)
- 합산 최대 ~5.15GB

---

## Docker 통합

`.github/workflows/deploy.yml`의 `docker run` 옵션:

```bash
docker run -d --name wherewego-api \
  --env-file /etc/wherewego/.env \
  --log-driver=json-file \
  --log-opt max-size=50m \
  --log-opt max-file=3 \
  -v /var/log/wherewego:/var/log/wherewego \
  -e JAVA_TOOL_OPTIONS="-Xmx512m -Xms256m" \
  -p 8080:8080 --memory 700m --restart unless-stopped \
  ghcr.io/rnqhstmd/wherewego:latest
```

**사전 작업** (1회):

```bash
sudo mkdir -p /var/log/wherewego
sudo chown 1000:1000 /var/log/wherewego   # Docker 컨테이너 java user UID
```

이 명령은 `deploy.yml`의 SSM `send-command`에 포함되어 매 배포 시 idempotent하게 실행됩니다.

---

## MDC RequestId (FR-OBS-6, PR-A)

### 발급 흐름

`backend/apps/wherewego-api/src/main/java/com/wherewego/config/security/RequestIdFilter.java`

```java
public final class RequestIdFilter extends OncePerRequestFilter {
    public static final String MDC_KEY = "requestId";
    public static final String RESPONSE_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(...) {
        String id = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);
        res.setHeader(RESPONSE_HEADER, id);
        try { chain.doFilter(req, res); }
        finally { MDC.clear(); }
    }
}
```

- **외부 헤더는 무시** (스푸핑 방지). 항상 자체 UUID.
- 응답 헤더에 echo (디버깅 편의).
- `finally MDC.clear()` 강제 — 스레드 풀 재사용 시 오염 차단.
- `FilterRegistrationBean` HIGHEST_PRECEDENCE로 모든 Servlet 요청 커버.

### 로그 패턴 결합

- **파일**: `[%X{requestId:-}]` 명시 포함 (없으면 빈 값).
- **JSON 콘솔**: Spring Boot 3.4+ structured-logstash format이 MDC 전체를 자동 직렬화.
- **plain 콘솔**: 패턴에 `[%X{requestId:-}]` 명시 포함.

### 비동기/스케줄러 전파

| 진입점 | 처리 방식 |
|--------|----------|
| `PlaceFallbackOrchestrator.runAsync` (raw `ThreadPoolExecutor`) | 호출자 측 `MDC.getCopyOfContextMap()` 스냅샷 캡처 → 워커 진입 시 `setContextMap` → finally `clear()` |
| `PendingInstagramAutoSaveScheduler` (`ScheduledExecutorService`) | task 진입 시 `MDC.put("requestId", "SCHEDULER")` + finally clear |
| `ThresholdMonitorScheduler` (PR-B 예정) | 동일하게 진입 시 `MDC.put("SCHEDULER")` 패턴 |

### 역추적 사용법

```
[Slack 알림] requestId: 01988e2c-1234-5678-abcd-ef0123456789
     ↓ 복사
[로그 파일] grep "01988e2c-1234-..." /var/log/wherewego/spring*.log.gz
     ↓ 매칭 라인
2026-05-20 14:23:01.123 [http-nio-8080-exec-3] [01988e2c-...] ERROR ...
```

---

## 외부 API 공통 구조화 로그 (FR-OBS-7, PR-A)

Gemini / Google Places / Kakao Callback / Instagram scraper 4곳에서 finally 블록에 1줄 발행:

```java
log.info("api={} op={} duration_ms={} outcome={} cache={}",
        "google_places", "searchText", elapsed, outcome, cache);
```

### 필드 정의

| 필드 | 값 |
|------|---|
| `api` | `google_places` / `gemini` / `kakao_callback` / `instagram` |
| `op` | 메서드 또는 외부 API 작업명 (`searchText`, `extractPlaceName`, `push`, `fetchHtml` 등) |
| `duration_ms` | 호출 시작부터 finally까지 elapsed (long) |
| `outcome` | `success` / `empty` / `cached` / `rate_limited` / `timeout` / `error` / `blocked` |
| `cache` | `hit` / `miss` / `n/a` (캐시 미적용 메서드는 `n/a`) |

### outcome 분류 규칙

- `success`: 정상 응답 + 결과 존재
- `empty`: 정상 응답 + 결과 0건
- `cached`: 캐시 hit (외부 호출 없음)
- `rate_limited`: 429 응답
- `timeout`: 데드라인 초과 / SocketTimeoutException
- `error`: 4xx/5xx/예외
- `blocked`: Instagram 3-stage 모두 실패

---

## 트러블슈팅

### 로그 파일이 생성되지 않음

1. `/var/log/wherewego/` 디렉토리 존재 확인: `ls -la /var/log/wherewego/`
2. 권한 확인: `stat /var/log/wherewego/` → owner가 UID 1000인지
3. Docker volume 마운트 확인: `docker inspect wherewego-api | grep -A5 Mounts`
4. Spring profile 확인: `dev` 또는 `prod`여야 함. `local`/`test`는 파일 미적용.

### requestId가 로그에 비어 있음 (n/a)

1. `RequestIdFilter`가 등록됐는지 확인: `/actuator/health` 호출 후 응답 헤더 `X-Request-Id` 확인.
2. 비동기 워커 케이스: MDC 캡처 패턴이 적용된 경로인지 확인 (`PlaceFallbackOrchestrator`, `PendingInstagramAutoSaveScheduler`).
3. 스케줄러 진입점이라면 `SCHEDULER` 고정값으로 나와야 함.

### 파일이 너무 빨리 사라짐

1. `cleanHistoryOnStart=true` 설정 때문. 재배포 시점에 90일/5GB 초과분 즉시 삭제됨.
2. `application.yml`의 logging 설정은 무시되므로 변경하려면 `file-rolling-appender.xml`을 수정.

### Docker `docker logs`는 어떻게 보나

`--log-driver=json-file`이라 기존과 동일하게 동작:

```bash
docker logs wherewego-api --tail 200 -f
docker logs wherewego-api --since 30m
```

호스트 측 적재 상한 150MB로 무한 증식 차단.

---

## PR-B 예정 변경

- Gemini 멀티 추출 메서드(`extractCandidatesInternal`, `extractPlaceNames`)에 캐시 통합 → 현재 `cache=n/a` → `cache=hit/miss`
- Google Places Caffeine 캐시 도입 (FR-OBS-9) → `GooglePlacesClient`에 `cache=hit/miss` 분기 추가
- Instagram failure tracker (FR-OBS-11) → 별도 카운터 (구조화 로그와 독립)

---

## 관련 자산

- 신규 도입: [Phase 2.11 PR-A](https://github.com/rnqhstmd/wherewego/pull/28)
- 도메인: [context/observability/](../../context/observability/)
- Slack 알림 상세: [slack-alerts.md](slack-alerts.md)
