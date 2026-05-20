# Slack 알림

> wherewego는 **두 개의 독립된 Slack 발송 경로**를 운영합니다. 목적과 트리거가 다르므로 혼동하지 않도록 정리.

---

## 두 경로 한눈에 비교

| 경로 | A: SlackNotifier (비즈니스 이벤트) | B: Logback Slack appender (시스템 ERROR) |
|------|----------------------------------|----------------------------------------|
| **트리거** | 코드에서 `slackNotifier.notify*()` 명시 호출 | `log.error(...)` 호출 시 자동 (ERROR 레벨 threshold) |
| **메시지 포맷** | Slack Block Kit (구조화 카드) | Plain text pattern |
| **레벨** | failure (🚨) / warning (⚠️) / notify (✅) 3-tier | ERROR 단일 |
| **포함 정보** | 비즈니스 컨텍스트 (Map 자유 구성) + 자동 requestId | log MDC (method/requestUri/traceId/spanId/clientIp) + stacktrace |
| **타임아웃** | 2초 (동기) | 비동기 (`ASYNC-SLACK`, 차단 없음) |
| **호출자 코드** | 명시적 — Service/Filter에서 직접 발송 | 암묵적 — log.error만 하면 자동 발송 |
| **활성 환경** | env `SLACK_WEBHOOK_URI` 설정 시 (모든 profile) | dev/prod profile만 |
| **미설정 시** | no-op (예외 없음) | appender 자체가 발송 안 함 |

**언제 어느 경로를?**

- **A (SlackNotifier)**: 비즈니스 의미가 있는 사건 — 외부 API 실패, 핀 저장 알림, 레이트리밋 초과, 임계값 도달 등. 운영자가 "왜 이런 일이 일어났나"를 즉시 알 수 있는 정형 메시지.
- **B (Logback appender)**: 예상 못 한 시스템 에러 — `RuntimeException`, `NullPointerException` 등 코드의 `log.error()` 가 자동으로 ERROR 레벨이라 따라옴. 스택트레이스 위주.

---

## 경로 A: SlackNotifier API

### 구현 위치

`backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/notify/slack/SlackNotifier.java`

### 3-tier 메서드

```java
@Component
public class SlackNotifier {
    void notifyFailure(String title, Map<String, Object> context);  // 🚨 빨강 — 즉시 대응 필요
    void notifyWarning(String title, Map<String, Object> context);  // ⚠️ 노랑 — 임계값 진입 등
    void notify(String title, Map<String, Object> context);          // ✅ 초록 — 긍정 신호
}
```

### MDC requestId 자동 동봉 (FR-OBS-12, PR-A)

`send()` 내부에서 `MDC.get("requestId")`를 자동 읽어 본문 **첫 키**로 prepend합니다. 호출자 코드 변경은 0건.

```java
private void send(String emoji, String title, Map<String, Object> context, String color) {
    Map<String, Object> enriched = new LinkedHashMap<>();
    String mdcRequestId = MDC.get(RequestIdFilter.MDC_KEY);
    enriched.put("requestId", mdcRequestId != null ? mdcRequestId : "n/a");
    if (context != null) enriched.putAll(context);
    // Block Kit fieldsSection에 enriched 적용
}
```

**MDC 값 시나리오**:

| 호출 컨텍스트 | MDC 값 | Slack 본문 표시 |
|--------------|--------|----------------|
| servlet 요청 흐름 | UUID | `requestId: 01988e2c-...` |
| `PlaceFallbackOrchestrator.runAsync` 워커 | UUID (TaskDecorator 패턴으로 전파) | `requestId: 01988e2c-...` |
| `PendingInstagramAutoSaveScheduler` 진입 | `"SCHEDULER"` 명시 | `requestId: SCHEDULER` |
| MDC 없음 (예외적) | null → `"n/a"` fallback | `requestId: n/a` |

### 호출 지점 (현재 5곳)

| 위치 | 메서드 | 조건 |
|------|--------|------|
| `PlaceFallbackOrchestrator:105` | `notifyFailure` | Google Places 동기 폴백 실패 |
| `PlaceFallbackOrchestrator:129` | `notifyFailure` | Google Places 비동기 폴백 실패 |
| `PlaceFallbackOrchestrator:148` | `notify` | 핀 저장 10건 달성 |
| `ChatbotRateLimitFilter:117` | `notifyWarning` | 챗봇 webhook 레이트리밋 초과 (5분 쿨다운) |
| `KakaoCallbackClient:86` | `notifyFailure` | 카카오 콜백 푸시 실패 |

### 환경 변수 / yaml 설정

`application.yml`:
```yaml
slack:
  username: ${SLACK_USERNAME:wherewego-prod}
  channel: ${SLACK_CHANNEL:#wherewego-alerts}
  webhook-uri: ${SLACK_WEBHOOK_URI:}
```

- `SLACK_WEBHOOK_URI` **빈 문자열이면 no-op**. `SlackNotifier`의 모든 메서드가 silent return.
- 운영 시 secrets로 주입 (EC2의 SSM Parameter Store 또는 `.env`).

### 사용 예시

```java
slackNotifier.notifyWarning("Google Places 한도 80% 도달", Map.of(
    "currentCalls", 8120,
    "dailyLimit", 10000,
    "ratio", "81.2%"
));
```

Slack 표시 (자동 동봉 포함):

```
⚠️ Google Places 한도 80% 도달

requestId:    SCHEDULER
currentCalls: 8120
dailyLimit:   10000
ratio:        81.2%
```

### 쿨다운 (호출자 책임)

`SlackNotifier` 자체에는 쿨다운이 없습니다. 호출자가 직접 관리:

```java
// ChatbotRateLimitFilter 패턴
private static final long ALERT_COOLDOWN_MS = 5 * 60 * 1000L;
private final AtomicLong lastAlertEpochMs = new AtomicLong(0);

void alertRateLimitIfNeeded(...) {
    long now = System.currentTimeMillis();
    long last = lastAlertEpochMs.get();
    if (now - last >= ALERT_COOLDOWN_MS && lastAlertEpochMs.compareAndSet(last, now)) {
        slackNotifier.notifyWarning("레이트리밋 초과 감지", ...);
    }
}
```

PR-B의 `ThresholdMonitorScheduler`(FR-OBS-10)는 단계별(`google_places.80`, `google_places.95`, `gemini.5xx`) 쿨다운을 `ConcurrentMap<String, AtomicLong>`로 관리할 예정.

---

## 경로 B: Logback Slack Appender

### 구현 위치

`backend/supports/logging/src/main/resources/appenders/slack-appender.xml`

```xml
<appender name="SLACK" class="com.github.maricn.logback.SlackAppender">
    <layout class="ch.qos.logback.classic.PatternLayout">
        <pattern>
            *Application:* *`${appName:-}`*
            *[%-5level]* *`%X{method:-}`* *`%X{requestUri:-}`* *Pod:* `${HOSTNAME:-}` *Trace ID:* `%X{traceId:-}` *Span ID:* `%X{spanId:-}` *Client IP:* `%X{clientIp:-}`%n%msg%n
        </pattern>
    </layout>
    <webhookUri>${SLACK_WEBHOOK_URI}</webhookUri>
    <username>${SLACK_USERNAME}</username>
    <channel>${SLACK_CHANNEL}</channel>
    <iconEmoji>:bell:</iconEmoji>
    <colorCoding>true</colorCoding>
</appender>

<appender name="ASYNC-SLACK" class="ch.qos.logback.classic.AsyncAppender">
    <appender-ref ref="SLACK"/>
    <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
        <level>ERROR</level>
    </filter>
</appender>
```

### 동작

- **자동 트리거**: 어디서든 `log.error(...)` 호출하면 자동으로 Slack에 발송됨.
- **임계값**: `ThresholdFilter level=ERROR` — WARN 이하는 발송 안 함.
- **비동기**: `AsyncAppender`로 감싸서 application 스레드 차단 없음.
- **MDC 키**: `method`, `requestUri`, `traceId`, `spanId`, `clientIp` — Spring Cloud Sleuth/Micrometer Tracing 컨벤션 (현재 코드에 자동 주입 안 됨, 빈 값으로 표시될 수 있음).
- **dev/prod에서만 활성**. local/test에는 등록 안 됨.

### Properties 파일

`backend/supports/logging/src/main/resources/properties/slack-log-prod.xml`:

```xml
<included>
    <springProperty scope="context" name="SLACK_USERNAME" source="slack.username" defaultValue="wherewego-prod"/>
    <springProperty scope="context" name="SLACK_CHANNEL" source="slack.channel" defaultValue="#wherewego-alerts"/>
    <springProperty scope="context" name="SLACK_WEBHOOK_URI" source="slack.webhook-uri" defaultValue=""/>
</included>
```

dev/prod 각각 별도 properties 파일이 있어 채널/사용자 구분 가능 (현재는 동일 환경 변수 참조).

### 한계

- `traceId`/`spanId`/`requestId` 미연동: 현재 Slack appender는 옛 Spring Cloud Sleuth 컨벤션. FR-OBS-6의 `requestId` MDC와는 별개로, 패턴에 추가하려면 `slack-appender.xml`의 pattern에 `%X{requestId:-}` 삽입 필요.
- 모든 `log.error`가 Slack으로 → 알림 폭주 가능. ERROR 레벨 사용을 신중히.

---

## 알림 폭주 방지 전략

### 즉시 (PR-A 완료)

| 메커니즘 | 적용 |
|---------|------|
| `SlackNotifier` 미설정 시 no-op | env 미주입 환경 (로컬/CI) |
| `ChatbotRateLimitFilter` 5분 쿨다운 | 챗봇 레이트리밋 알림 |
| `PlaceFallbackOrchestrator` 호출 지점 분리 | 단일 실패 1회 발송 |
| Logback appender ERROR threshold | WARN/INFO/DEBUG는 발송 안 함 |
| `ASYNC-SLACK` 큐 | 짧은 시간 다발 발생 시 비동기 흐름 |

### 추가 (PR-B 예정)

| 메커니즘 | 대상 |
|---------|------|
| `ThresholdMonitorScheduler` 단계별 쿨다운 (5분) | FR-OBS-10 임계값 알림 |
| Instagram 차단 감지 쿨다운 (5분) | FR-OBS-11 차단 알림 |

---

## 트러블슈팅

### Slack 알림이 안 옴

1. **`SLACK_WEBHOOK_URI` 설정 확인**: `echo $SLACK_WEBHOOK_URI` 또는 컨테이너 내부 `printenv | grep SLACK`
2. **프로파일 확인**: Logback appender는 dev/prod만. `local`이면 안 옴.
3. **레벨 확인**: Logback appender는 ERROR만. WARN을 보내려면 `log.error()`를 쓰거나 `SlackNotifier`를 사용.
4. **2초 타임아웃**: `SlackNotifier`는 동기 호출 2초 안에 응답 없으면 swallow하고 로그만 남김.

### Slack 알림이 너무 많이 옴

1. `log.error()` 사용처 점검: 정상 흐름에 ERROR 쓰는 경우가 있는지 (예: 캐치한 후 swallow 가능한 예외).
2. 호출자 쿨다운 추가: `AtomicLong + compareAndSet` 패턴.
3. Logback appender pattern에 `requestId` 추가하여 중복 알림 식별.

### 두 경로가 동시에 와서 중복돼 보임

이건 의도된 동작입니다. 같은 실패에 대해:
- SlackNotifier (구조화 알림) — 비즈니스 컨텍스트 강조
- Logback appender (자동 알림) — 스택트레이스 강조

너무 중복되면 호출자가 `log.error()` 대신 `log.warn()`을 쓰면 Logback 경로는 차단되고 SlackNotifier만 발송.

### MDC `traceId`/`spanId`가 빈 값으로 와요

현재 코드베이스에 Spring Cloud Sleuth/Micrometer Tracing이 활성화되지 않았기 때문입니다. PR-A는 `requestId`만 도입했고, 분산 추적(W3C Trace Context)은 별도 작업입니다. 운영 추적은 `requestId`로 충분합니다.

---

## PR-B 예정 변경

- `ThresholdMonitorScheduler` (FR-OBS-10) — `@Scheduled` 정시 실행 + 단계별 쿨다운 + Gemini 5xx 비율 알림
- `InstagramFailureTracker` (FR-OBS-11) — 1시간 윈도우 50% 임계값 → `notifyFailure`
- Logback appender pattern에 `%X{requestId:-}` 추가 검토 (Spring Cloud Sleuth MDC와 통합)

---

## 관련 자산

- 신규 도입: [Phase 2.11 PR-A](https://github.com/rnqhstmd/wherewego/pull/28)
- 로그 시스템: [logging.md](logging.md)
- Prometheus 메트릭 → Grafana: [grafana-monitoring.md](grafana-monitoring.md)
