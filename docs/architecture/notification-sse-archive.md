# Phase 8 SSE 인앱 알림 인프라 — 재도입 아카이브

> **목적**: Phase 8(PR [#40](https://github.com/rnqhstmd/wherewego/pull/40))에서 처음 구현했던 SSE 풀스택 알림 인프라를 옵션 B 다운그레이드(2026-05-21)로 제거했다. 사용자 100명+ 시점 또는 실시간 피드백 요구가 누적될 때 재도입하기 위해, **구현 코드 / 결정 근거 / 의문점 / 검증 시 발견된 실제 버그 / 재도입 체크리스트**를 보존한다.
>
> 옵션 B 다운그레이드 후엔 코드가 사라지므로(`NotificationSseRegistry`, `sseClient.ts`, BFF stream route 등) **이 문서가 SSE 구현의 단일 소스**가 된다. 처음부터 다시 설계하지 말 것 — 본문의 코드를 그대로 복원한 뒤 의문점 항목만 staging에서 재검증하면 된다.
>
> 보조 자료:
> - 원본 PRD: [`.dev/feat-phase-8-notifications/prd.md`](../../.dev/feat-phase-8-notifications/prd.md)
> - 원본 설계서: [`.dev/feat-phase-8-notifications/design.md`](../../.dev/feat-phase-8-notifications/design.md)
> - PR #40 diff 전체: [`.dev/feat-phase-8-notifications/diff.txt`](../../.dev/feat-phase-8-notifications/diff.txt)
> - 운영 가이드(옵션 B 반영 예정): [`docs/ops/phase-8-notifications.md`](../ops/phase-8-notifications.md)
> - 진화 로드맵: [`docs/ops/notification-scaling-roadmap.md`](../ops/notification-scaling-roadmap.md)

---

## 1. 왜 SSE를 선택했는가 (Phase 8 원본 결정 근거)

### 배제한 대안
| 대안 | 배제 이유 |
|------|----------|
| 카카오톡 푸시 | 비즈니스 결정으로 사용자 OS 푸시 미사용 (Phase 2 시점 명시) |
| 브라우저 Push API | OS/브라우저 권한 요구, 인프라(VAPID/서비스워커) 부담 |
| WebSocket | 양방향 불필요, 운영 복잡도 ↑ (라이브러리/프록시 설정) |
| Redis Pub/Sub | 단일 EC2 환경에서 외부 인프라 미도입(ADR-0001 일관) |
| 폴링(setInterval) | 배터리/네트워크 비용, 체감 지연 30s+ |

### SSE를 선택한 핵심 이유
1. **단방향**: 서버→클라이언트만 필요. WebSocket의 양방향은 과함.
2. **표준 HTTP**: 별도 프로토콜 없이 long-lived HTTP/1.1로 가능. Spring `SseEmitter`로 즉시 사용.
3. **저비용**: 추가 인프라 0 (Redis/Kafka 등 없음). 단일 EC2 t3.micro에서 ~50 동시 연결 가능.
4. **체감 지연 0초**: AFTER_COMMIT 이벤트 즉시 push.

### 트레이드오프 (Phase 8 시점에서 수용)
- 단일 EC2 SseEmitter — 수평 확장 불가 (ADR-0001 유지)
- `@TransactionalEventListener` 동기 호출 — 수신자 N명 시 스레드 점유
- 운영 도메인 SameSite/cross-origin EventSource — staging 검증 필요
- Vercel/Cloudflare 프록시 SSE 버퍼링 — 미검증

---

## 2. 왜 옵션 B로 다운그레이드했는가 (2026-05-21)

### 배경
- MVP 사용자 규모 2인 커플 그룹, 출시 전
- 1인 개발자 운영 환경
- staging에서 실측 필요한 의문점 3건 (§7 참조) 부담

### 옵션 비교
| 옵션 | 백엔드 | 프론트 | 체감 | 운영 부담 |
|------|--------|--------|------|----------|
| A: SSE 풀스택 (원본) | Registry/Heartbeat/Listener/stream | sseClient + BFF route | 즉시 | 의문점 3건 / 단일 EC2 / 프록시 미검증 |
| **B: REST + visibility fetch** (선택) | REST API만 | `useNotifications` visibilitychange/focus | 탭 활성화 시 | 0 |
| C: polling 30s | REST API만 | `setInterval` | 30초 이내 | 약간 |
| D: 전체 보류 | 도메인 자체 없음 | 없음 | — | 0 |

### B를 선택한 이유
- 백엔드 알림 도메인(테이블 + 트리거 + REST API)은 **재활용 가능 자산**으로 살리고, **SSE만 분리 제거**하여 매몰비용 50% 회수
- 의문점 3건 중 2건(`request.signal`, `preflightAuth`)이 즉시 소멸
- 사용자 100명+ 시점에 SSE 재도입 시 본 문서 기준 복원

---

## 3. 아키텍처 전체 흐름 (SSE 버전)

```
[ PinV1Controller / 챗봇 Handler ] (트랜잭션 밖)
       │
       │  try { notificationService.createFor*(...) }
       │  catch (RuntimeException e) { log.warn(...) }   ← BR-3 호출자 격리
       ▼
[ NotificationService ] @Transactional (REQUIRED)
       │  1) findOtherActiveMemberIds(groupId, registeredBy)
       │  2) 각 receiverId마다 Notification + NotificationPin[] insert
       │  3) eventPublisher.publishEvent(NotificationCreatedEvent)
       ▼  (메서드 종료 = 트랜잭션 커밋)

[ NotificationSsePushListener ] @TransactionalEventListener(AFTER_COMMIT)
       │   ※ "COMMIT"은 NotificationService 내부 트랜잭션 기준
       ▼
[ NotificationSseRegistry.push(receiverId, event) ]
       │  ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>
       ▼
[ 각 SseEmitter.send(event:"notification") ]
       └─ IOException / IllegalStateException → removeEmitter + completeWithError

[ NotificationHeartbeatScheduler ] @Scheduled(fixedRate=30s)
       └─ registry.broadcastHeartbeat() — comment ": heartbeat"
```

### 클라이언트 측

```
[ useNotifications 훅 mount ]
       │
       ▼
[ createNotificationSseClient ] (sseClient.ts)
       │  1) preflightAuth: fetch로 401 사전 감지 (재시도 회피)
       │  2) new EventSource(url, { withCredentials: true })
       │  3) onopen / "connected" event → retryCount=0
       │  4) "notification" event → onNotification(payload)
       │  5) onerror → 지수 백오프 2→4→8→16→30s, 최대 5회
       ▼
[ BFF SSE 라우트 ] /api/v1/notifications/stream/route.ts (Node runtime)
       │  fetch(backend, { signal: request.signal, headers: { Cookie } })
       │  401/4xx → Connection: close (재연결 약화)
       │  200 → upstream.body 직접 파이프 + X-Accel-Buffering: no
       ▼
[ 백엔드 GET /api/v1/notifications/stream ] → SseRegistry.register
```

---

## 4. 백엔드 구현 상세 (전체 소스 보존)

### 4.1 파일 위치

```
backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/
├── NotificationSseRegistry.java       ← 핵심: emitter 라이프사이클 관리
├── NotificationHeartbeatScheduler.java ← 30초 heartbeat
├── NotificationSsePushListener.java   ← AFTER_COMMIT → push
├── NotificationCreatedEvent.java      ← 도메인 이벤트 record
└── (이하 옵션 B에서도 유지)
    ├── Notification.java
    ├── NotificationPin.java
    ├── NotificationType.java
    ├── NotificationService.java       ← publishEvent 호출 부분만 옵션 B에서 제거
    └── NotificationRepository.java

backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/notification/
├── NotificationV1Controller.java      ← stream 메서드만 옵션 B에서 제거
├── NotificationV1ApiSpec.java         ← stream 시그니처만 옵션 B에서 제거
└── NotificationV1Dto.java             ← NotificationStreamEvent record만 제거 가능
```

### 4.2 NotificationSseRegistry.java (전체)

핵심 자료구조 `ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>`. 락 없이 register/push/heartbeat 동시 안전.

```java
package com.wherewego.domain.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 사용자별 SseEmitter 레지스트리.
 * - userId → emitter 목록 (다중 탭 지원, FR-9)
 * - 5분 타임아웃 (BR-6)
 * - onCompletion/onTimeout/onError → 자동 제거 (QE-2)
 * - emit 실패(IOException/IllegalStateException) → 제거 + completeWithError
 *
 * 동시성: ConcurrentHashMap + CopyOnWriteArrayList → 락 없이 register/push/heartbeat 안전.
 */
@Component
public class NotificationSseRegistry {

    private static final Logger log = LoggerFactory.getLogger(NotificationSseRegistry.class);
    private static final long EMITTER_TIMEOUT_MS = 5 * 60 * 1000L;
    /** 사용자별 최대 동시 SSE 연결 수 (DoS 방지). 초과 시 가장 오래된 emitter 부터 complete. */
    private static final int MAX_EMITTERS_PER_USER = 10;

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(Long userId) {
        CopyOnWriteArrayList<SseEmitter> list =
                emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());

        // 사용자별 최대 emitter 수 제한 (DoS 방지). 가장 오래된 것 부터 정리.
        while (list.size() >= MAX_EMITTERS_PER_USER) {
            SseEmitter oldest = list.get(0);
            removeEmitter(userId, oldest);
            try { oldest.complete(); } catch (Exception ignore) { /* noop */ }
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        list.add(emitter);

        Runnable remove = () -> removeEmitter(userId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> {
            log.debug("SSE error user={} cause={}", userId, t.getMessage());
            remove.run();
        });

        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            log.debug("SSE initial connected failed user={} cause={}", userId, e.getMessage());
            remove.run();
            try { emitter.completeWithError(e); } catch (Exception ignore) { /* noop */ }
        }
        return emitter;
    }

    public void push(Long userId, NotificationCreatedEvent event) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event().name("notification").data(event));
            } catch (IOException | IllegalStateException ex) {
                log.debug("SSE push failed user={} cause={}", userId, ex.getMessage());
                removeEmitter(userId, e);
                try { e.completeWithError(ex); } catch (Exception ignore) {}
            }
        }
    }

    public void broadcastHeartbeat() {
        emitters.forEach((uid, list) -> {
            for (SseEmitter e : list) {
                try {
                    e.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | IllegalStateException ex) {
                    removeEmitter(uid, e);
                    try { e.completeWithError(ex); } catch (Exception ignore) { /* noop */ }
                }
            }
        });
    }

    /**
     * emitter 를 registry 에서 제거한다.
     * 테스트 가시성을 위해 package-private. 운영 코드에서는 콜백/예외 경로에서만 호출.
     */
    void removeEmitter(Long userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(userId, list); // race-safe compare-and-remove
            }
        }
    }

    public int activeEmitterCount(Long userId) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
        return list == null ? 0 : list.size();
    }

    public int totalEmitterCount() {
        return emitters.values().stream().mapToInt(CopyOnWriteArrayList::size).sum();
    }
}
```

**왜 이 구조인가**:
- `ConcurrentHashMap`: userId 키별 동시 접근에 락 없이 안전
- `CopyOnWriteArrayList`: 동일 userId의 다중 탭 emitter 목록에 push 중 register/remove 동시 발생해도 ConcurrentModificationException 없음
- `MAX_EMITTERS_PER_USER=10`: DoS 가드 (재연결 폭주 시). 초과 시 가장 오래된 emitter complete
- `EMITTER_TIMEOUT_MS=5분`: 좀비 방지 — 클라이언트가 disconnect 신호 못 보내도 5분 후 자동 정리
- `removeEmitter` 내부 `remove(key, value)` 비교 삭제: 빈 리스트 발견 시점과 새 emitter 추가 시점 race 방어

### 4.3 NotificationHeartbeatScheduler.java (전체)

```java
package com.wherewego.domain.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SSE 연결 유지를 위한 heartbeat 스케줄러.
 *
 * - 30초마다 모든 SseEmitter에 comment ":heartbeat" 발사 (FR-6).
 * - 발사 실패한 emitter는 registry 내부에서 자동 제거 (QE-2).
 * - @EnableScheduling은 RequestIdFilterConfig.java:23에 이미 활성화됨.
 */
@Component
@RequiredArgsConstructor
public class NotificationHeartbeatScheduler {

    private final NotificationSseRegistry registry;

    @Scheduled(fixedRate = 30_000L)
    public void heartbeat() {
        registry.broadcastHeartbeat();
    }
}
```

**왜 30초인가**:
- 프록시/로드밸런서 일반 idle timeout 60s~120s 안전 마진
- 사용자 100명+ 시점엔 60s로 늘려도 됨 (진화 로드맵 단계 3)

**`@EnableScheduling` 위치 주의**: 새 Application 클래스 아닌 `backend/.../config/RequestIdFilterConfig.java:23`에 이미 적용되어 있음. 재도입 시 중복 추가 금지.

### 4.4 NotificationSsePushListener.java (전체)

```java
package com.wherewego.domain.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * NotificationCreatedEvent를 NotificationService 트랜잭션 커밋 후 수신하여 SSE push.
 *
 * AFTER_COMMIT의 "COMMIT"은 NotificationService 내부 @Transactional의 커밋 기준이지,
 * 호출자(PinV1Controller, 챗봇 핸들러)의 트랜잭션이 아니다.
 * 호출자는 트랜잭션 밖에서 NotificationService를 호출한다.
 */
@Component
@RequiredArgsConstructor
public class NotificationSsePushListener {

    private final NotificationSseRegistry registry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(NotificationCreatedEvent event) {
        registry.push(event.receiverId(), event);
    }
}
```

**왜 AFTER_COMMIT인가**:
- DB row가 커밋되기 전 push하면 클라이언트가 fetch했을 때 데이터 부재 → race condition
- AFTER_COMMIT은 NotificationService 내부 트랜잭션 커밋 직후 발화 → DB 일관성 보장

**동기/비동기**: 본 구현은 동기 호출. 수신자 N명일 때 응답 스레드를 점유. MVP 2인에서 무해. 사용자 100명+ 시점에 `@Async` 도입 검토.

### 4.5 NotificationCreatedEvent.java (전체)

```java
package com.wherewego.domain.notification;

import java.time.Instant;

/**
 * 알림 생성 도메인 이벤트.
 * NotificationService.createForXxx 트랜잭션 커밋 후
 * NotificationSsePushListener가 @TransactionalEventListener(AFTER_COMMIT)으로 수신하여 SSE push.
 */
public record NotificationCreatedEvent(
        Long receiverId,
        Long notificationId,
        NotificationType type,
        Long registeredBy,
        String registeredByNickname,
        String firstPlaceName,
        int totalPinCount,
        Instant createdAt
) {
}
```

**페이로드 설계**: 클라이언트가 toast 노출에 필요한 최소 필드만 포함 (닉네임/첫 장소명/N개 카운트). 추가 핀 메타는 `GET /{id}`로 별도 조회. 페이로드 비대화 회피.

### 4.6 NotificationV1Controller.stream (해당 메서드만)

```java
private final NotificationService notificationService;
private final NotificationSseRegistry sseRegistry;

/**
 * SSE 스트림. 프록시 환경에서 응답 버퍼링 방지를 위해
 * X-Accel-Buffering: no + Cache-Control: no-cache 헤더 추가.
 */
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@Override
public SseEmitter stream(@AuthUser Long userId, HttpServletResponse response) {
    response.setHeader("X-Accel-Buffering", "no");
    response.setHeader("Cache-Control", "no-cache");
    return sseRegistry.register(userId);
}
```

**왜 두 헤더가 필요한가**:
- `X-Accel-Buffering: no`: nginx/Vercel/Cloudflare가 응답 본문을 모아 버퍼링하면 SSE의 30초 heartbeat가 즉시 도달 못함. 클라이언트가 timeout으로 끊김 인식 → 무한 재연결 루프
- `Cache-Control: no-cache`: 캐시 레이어가 SSE 응답을 캐싱하지 않도록 명시

### 4.7 NotificationService — `publishEvent` 부분만 옵션 B에서 제거

옵션 B에서는 `NotificationService.createForManualPin` / `createForChatbotBatch` 내부의 `eventPublisher.publishEvent(NotificationCreatedEvent.of(...))` 호출만 제거하면 됨. DB 저장 로직은 그대로 유지.

재도입 시 복원할 코드 패턴:
```java
@RequiredArgsConstructor
public class NotificationService {
    private final ApplicationEventPublisher eventPublisher;
    // ...

    @Transactional
    public void createForManualPin(Long groupId, Long registeredBy, Long pinId) {
        // (수신자 조회 + insert 로직)
        for (Long receiverId : receivers) {
            Notification n = repository.save(Notification.create(groupId, receiverId, registeredBy, MANUAL_PIN));
            repository.saveAllPins(List.of(NotificationPin.link(n.getId(), pinId, 0)));

            eventPublisher.publishEvent(new NotificationCreatedEvent(
                receiverId, n.getId(), MANUAL_PIN,
                registeredBy, registeredByNickname,
                placeName, 1, n.getCreatedAt()
            ));
        }
    }
}
```

---

## 5. 프론트엔드 구현 상세 (전체 소스 보존)

### 5.1 파일 위치

```
frontend/src/app/api/v1/notifications/stream/
└── route.ts                    ← BFF SSE 전용 라우트 (Node runtime)

frontend/src/lib/notifications/
├── sseClient.ts                ← EventSource 래퍼 + 재연결 정책
├── useNotifications.ts         ← 훅 (SSE 구독 부분이 핵심)
├── api.ts                      ← REST 호출 + NOTIFICATION_SSE_URL 상수
├── types.ts                    ← ConnectionState, NotificationStreamEvent 등
└── (테스트)
    ├── sseClient.test.ts
    └── useNotifications.test.ts
```

### 5.2 BFF SSE 전용 라우트 (route.ts 전체)

`frontend/src/app/api/v1/notifications/stream/route.ts`:

```typescript
import type { NextRequest } from "next/server";

/**
 * SSE 전용 BFF 프록시 라우트.
 *
 * <p>catch-all 라우트(`/api/[...path]`)는 fetch-then-respond 패턴과
 * `AbortSignal.timeout(5000)`을 사용하므로 long-lived SSE 스트림에 부적합하다.
 * 본 라우트는 Node.js runtime의 streaming 응답으로 백엔드 SSE를 그대로 파이프한다.</p>
 *
 * <p>Next.js App Router는 더 구체적인 라우트가 catch-all보다 우선하므로
 * 별도의 가드 없이 본 라우트가 `/api/v1/notifications/stream` 요청을 받는다.</p>
 */

const BACKEND_BASE_URL =
  process.env.BACKEND_BASE_URL ?? "http://localhost:8080";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const backendUrl = `${BACKEND_BASE_URL}/api/v1/notifications/stream`;

  // 쿠키 헤더 그대로 전달 (catch-all 라우트와 동일한 인증 방식)
  const cookieHeader = request.headers.get("cookie") ?? "";

  const upstream = await fetch(backendUrl, {
    method: "GET",
    headers: {
      Accept: "text/event-stream",
      "Cache-Control": "no-cache",
      Cookie: cookieHeader,
    },
    // 클라이언트 disconnect 시 upstream fetch 즉시 종료
    signal: request.signal,
  });

  if (!upstream.ok || !upstream.body) {
    return new Response(null, {
      status: upstream.status,
      // EventSource 재연결 시그널 약화 (401/403 시 빠른 종료 유도)
      headers: { Connection: "close" },
    });
  }

  // 응답 헤더 그대로 forwarding + 프록시 버퍼링 방지
  return new Response(upstream.body, {
    status: upstream.status,
    headers: {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      "X-Accel-Buffering": "no",
      Connection: "keep-alive",
    },
  });
}
```

**왜 BFF 전용 라우트가 필요한가**:
- 기존 catch-all `/api/[...path]/route.ts`는 `fetch-then-respond` + `AbortSignal.timeout(5000)`으로 5초 안에 응답 완료 가정. SSE는 영구 스트림이라 부적합
- Vercel은 `nodejs` runtime에서 streaming 응답 지원. `edge` runtime은 일부 제약
- `dynamic = "force-dynamic"`: Next.js의 캐시/정적 분석을 끔. SSE는 항상 동적

**`signal: request.signal` 핵심**: 클라이언트가 탭을 닫으면 Next.js가 `request.signal`에 abort 발화 → upstream fetch도 abort → 백엔드 SseEmitter `onError` 발화 → 즉시 emitter 제거. **단 Next.js 16에서 이 동작이 보장되는지 의문점 1 참조**.

### 5.3 sseClient.ts (전체)

```typescript
import type { NotificationStreamEvent, ConnectionState } from "./types";

export interface SseClientOptions {
  url: string;
  onConnected?: () => void;
  onNotification: (payload: NotificationStreamEvent) => void;
  onStateChange?: (state: ConnectionState) => void;
}

export interface SseClient {
  start(): void;
  stop(): void;
}

const INITIAL_BACKOFF_MS = 2_000;
const MAX_BACKOFF_MS = 30_000;
const MAX_RETRIES = 5;

/**
 * EventSource 기반 알림 SSE 클라이언트.
 *
 * 재연결 정책 (FR-8):
 * - 초기 backoff 2초, 매 실패마다 2배 (2 → 4 → 8 → 16 → 30 cap)
 * - 최대 5회 재시도 — 5회 실패 시 failed 상태로 영구 중단
 * - 정상 연결(onopen 또는 connected event) 시 카운터 reset
 * - stop() 호출 시 closed 상태 + 더 이상 재연결 안 함
 *
 * SSR 가드: typeof window !== "undefined" 호출 전 호출자가 보장.
 * 모든 에러는 silent — 호출자는 onStateChange로 상태 변화 관찰.
 */
export function createNotificationSseClient(options: SseClientOptions): SseClient {
  let eventSource: EventSource | null = null;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;
  let retryCount = 0;
  let stopped = false;
  let preflightDone = false;

  function setState(state: ConnectionState): void {
    options.onStateChange?.(state);
  }

  function cleanupEventSource(): void {
    if (eventSource) {
      eventSource.close();
      eventSource = null;
    }
  }

  function scheduleReconnect(): void {
    if (stopped) return;
    if (retryCount >= MAX_RETRIES) {
      setState("failed");
      return;
    }
    const delay = Math.min(
      INITIAL_BACKOFF_MS * Math.pow(2, retryCount),
      MAX_BACKOFF_MS,
    );
    retryCount += 1;
    setState("connecting");
    retryTimer = setTimeout(() => {
      void connect();
    }, delay);
  }

  /**
   * EventSource는 응답 status code를 직접 노출하지 않으므로,
   * 첫 연결 전에 fetch로 인증 상태를 미리 확인한다.
   * 401/403이면 5회 재시도 없이 즉시 failed 상태로 수렴한다.
   */
  async function preflightAuth(): Promise<"ok" | "unauthorized" | "error"> {
    try {
      const controller = new AbortController();
      const res = await fetch(options.url, {
        method: "GET",
        headers: { Accept: "text/event-stream" },
        credentials: "include",
        signal: controller.signal,
      });
      // status를 확인했으므로 즉시 종료 (SSE 연결을 점유하지 않도록)
      controller.abort();
      if (res.status === 401 || res.status === 403) return "unauthorized";
      if (!res.ok) return "error";
      return "ok";
    } catch (e) {
      // abort에 의한 AbortError는 정상 (이미 status를 받았으므로)
      if (e instanceof Error && e.name === "AbortError") return "ok";
      return "error";
    }
  }

  async function connect(): Promise<void> {
    if (stopped) return;
    retryTimer = null;
    cleanupEventSource();
    setState("connecting");

    // 첫 진입 시에만 preflight (이후 재연결은 EventSource onerror 흐름)
    if (!preflightDone) {
      const auth = await preflightAuth();
      if (stopped) return;
      preflightDone = true;
      if (auth === "unauthorized") {
        setState("failed");
        return;
      }
      // "error"인 경우는 일시적 네트워크 이슈일 수 있으므로 EventSource 시도 진행
    }

    try {
      eventSource = new EventSource(options.url, { withCredentials: true });
    } catch {
      scheduleReconnect();
      return;
    }

    eventSource.onopen = () => {
      retryCount = 0;
      setState("open");
    };

    eventSource.addEventListener("connected", () => {
      retryCount = 0;
      setState("open");
      options.onConnected?.();
    });

    eventSource.addEventListener("notification", (ev: MessageEvent) => {
      try {
        const payload = JSON.parse(ev.data) as NotificationStreamEvent;
        options.onNotification(payload);
      } catch {
        // payload 파싱 실패는 무시 (다음 이벤트로 진행)
      }
    });

    eventSource.onerror = () => {
      cleanupEventSource();
      if (stopped) {
        setState("closed");
        return;
      }
      scheduleReconnect();
    };
  }

  return {
    start(): void {
      stopped = false;
      retryCount = 0;
      preflightDone = false;
      if (retryTimer) {
        clearTimeout(retryTimer);
        retryTimer = null;
      }
      void connect();
    },
    stop(): void {
      stopped = true;
      if (retryTimer) {
        clearTimeout(retryTimer);
        retryTimer = null;
      }
      cleanupEventSource();
      setState("closed");
    },
  };
}
```

**왜 preflightAuth가 필요한가**:
- EventSource는 응답 status code를 onerror에서도 노출하지 않음. 401이든 503이든 동일하게 onerror 발화
- preflight 없이 401 응답 → onerror → 재연결 → 또 401 → ... 5회 반복 후에야 failed 상태
- preflight로 첫 진입에서 401 즉시 감지 → failed 직진 (UX와 백엔드 부담 둘 다 절약)

**왜 첫 진입에만 preflight하는가**:
- 두 번째 이후 재연결은 EventSource onerror 흐름에서 처리 (401이면 backoff 후 다시 시도하다 failed)
- 매 재연결마다 preflight하면 백엔드 emitter 2개 등록 race가 더 빈번해짐 (의문점 2 참조)

### 5.4 useNotifications.ts SSE 부분 (전체)

`frontend/src/lib/notifications/useNotifications.ts`. 옵션 B에서 `// SSE 구독` 블록을 `// visibilitychange/focus 트리거 fetch`로 교체한다.

```typescript
'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  fetchNotifications,
  fetchNotificationDetail,
  markAllNotificationsRead,
  NOTIFICATION_SSE_URL,
} from './api';
import { createNotificationSseClient } from './sseClient';
import type {
  ConnectionState,
  NotificationDetail,
  NotificationItem,
  NotificationStreamEvent,
} from './types';

const MAX_ITEMS = 50;
const TOAST_DURATION_MS = 5_000;

export interface UseNotificationsState {
  items: NotificationItem[];
  unreadCount: number;
  connectionState: ConnectionState;
  toast: { id: number; payload: NotificationStreamEvent } | null;
  isPanelOpen: boolean;
}

export interface UseNotificationsActions {
  openPanel: () => Promise<void>;
  closePanel: () => void;
  markAllRead: () => Promise<void>;
  refreshList: () => Promise<void>;
  dismissToast: () => void;
  loadDetail: (notificationId: number) => Promise<NotificationDetail>;
}

export function useNotifications(): UseNotificationsState & UseNotificationsActions {
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [unreadCount, setUnreadCount] = useState<number>(0);
  const [connectionState, setConnectionState] = useState<ConnectionState>('connecting');
  const [toast, setToast] = useState<{ id: number; payload: NotificationStreamEvent } | null>(null);
  const [isPanelOpen, setIsPanelOpen] = useState<boolean>(false);

  const shownToastIds = useRef<Set<number>>(new Set());
  const isPanelOpenRef = useRef<boolean>(false);
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Sync ref with state for use in SSE callback
  useEffect(() => {
    isPanelOpenRef.current = isPanelOpen;
  }, [isPanelOpen]);

  // 초기 fetch
  const refreshList = useCallback(async () => {
    try {
      const res = await fetchNotifications();
      setItems(res.items.slice(0, MAX_ITEMS));
      setUnreadCount(res.unreadCount);
    } catch (e) {
      // silent fail (네트워크 일시 끊김 등)
    }
  }, []);

  const markAllRead = useCallback(async () => {
    try {
      await markAllNotificationsRead();
      setUnreadCount(0);
      setItems((prev) =>
        prev.map((it) => (it.readAt ? it : { ...it, readAt: new Date().toISOString() })),
      );
    } catch (e) {
      // silent fail
    }
  }, []);

  // (openPanel/closePanel/dismissToast/loadDetail 생략 — 옵션 B에서도 동일)

  // 마운트 시 초기 로드
  useEffect(() => {
    refreshList();
  }, [refreshList]);

  // === 옵션 A: SSE 구독 ===
  useEffect(() => {
    if (typeof window === 'undefined') return;

    const client = createNotificationSseClient({
      url: NOTIFICATION_SSE_URL,
      onStateChange: setConnectionState,
      onNotification: (payload) => {
        // items prepend (50건 cap)
        setItems((prev) => {
          const newItem: NotificationItem = {
            id: payload.id,
            type: payload.type,
            registeredBy: null,
            registeredByNickname: payload.registeredByNickname,
            firstPlaceName: payload.firstPlaceName,
            totalPinCount: payload.totalPinCount,
            createdAt: payload.createdAt,
            readAt: null,
          };
          // 중복 id 차단 (재연결 시 동일 알림 재수신 가능)
          if (prev.some((it) => it.id === payload.id)) {
            return prev;
          }
          return [newItem, ...prev].slice(0, MAX_ITEMS);
        });
        setUnreadCount((prev) => prev + 1);

        // AC-16: 동일 알림은 한 번만 toast 노출 대상으로 등록
        if (shownToastIds.current.has(payload.id)) {
          if (isPanelOpenRef.current) {
            markAllRead();
          }
          return;
        }
        shownToastIds.current.add(payload.id);

        if (isPanelOpenRef.current) {
          // 패널 열림: toast 미노출, 즉시 read-all 재호출 (AC-17)
          markAllRead();
        } else {
          // 패널 닫힘: toast 노출 + 5초 자동 닫힘 (FR-15)
          if (toastTimerRef.current) {
            clearTimeout(toastTimerRef.current);
          }
          setToast({ id: payload.id, payload });
          toastTimerRef.current = setTimeout(() => {
            setToast(null);
            toastTimerRef.current = null;
          }, TOAST_DURATION_MS);
        }
      },
    });

    client.start();

    return () => {
      client.stop();
      if (toastTimerRef.current) {
        clearTimeout(toastTimerRef.current);
        toastTimerRef.current = null;
      }
    };
  }, [markAllRead]);

  return { /* ... */ };
}
```

**`shownToastIds` Set 패턴**: SSE 재연결 후 동일 알림이 다시 수신될 수 있음(서버 측 미보존 큐). 동일 id에 대해 두 번 노출되지 않도록 클라이언트 측 Set에 기록.

**`isPanelOpenRef` 패턴**: SSE 콜백 클로저가 stale `isPanelOpen`을 참조하지 않도록 ref로 동기화. 단, state 변경 직후 다음 effect 발화 전까지 stale 가능성 (의문점 — Q3, MVP 수용).

---

## 6. 핵심 결정 (Q&A 정리)

| # | 질문 | 결정 | 사유 |
|---|------|------|------|
| Q1 | 알림 보관 기간 | 영구 보관 | MVP 규모 DB 부담 없음. 만료 정책은 후속 |
| Q2 | 챗봇 PlaceSelectionHandler 트리거 포함 여부 | 포함 (`CHATBOT_PINS`) | 사용자 명시 선택도 알림 가치 있음 |
| Q3 | 패널 열림 중 새 알림 수신 | 상단 추가 + read-all 재호출 (AC-17) | 사용자가 보고 있는 상태에서 자동 갱신 |
| Q4 | 데스크탑 벨 아이콘 | 이번 Phase 포함 | 데스크탑 사용자 형평성 |
| Q5 | 말풍선 자동 닫힘 | 5초 + 외부 탭 (둘 중 먼저) | 강제 인지 vs 방해 균형 |
| Q6 | 삭제된 핀 표시 | `placeName` 유지 + 좌표/주소 null + `deleted=true` | 이력 보존 vs 잘못된 좌표 방지 |
| Q7 | `@EnableScheduling` 활성 위치 | `RequestIdFilterConfig.java:23` 기존 | 중복 활성 회피 |
| Q8 | 회색 점(연결 끊김) 시각 상태 | 표시 + tooltip "연결 끊김" | 사용자 인지 가능성 |

---

## 7. 의문점 (재도입 시 staging 검증 필요)

### 의문점 1 — `signal: request.signal`이 Next.js 16에서 정말 동작하나?

**위치**: BFF SSE 라우트(§5.2)

**의도된 동작**:
```
사용자 탭 닫음 → 브라우저가 EventSource 종료
→ Next.js가 request.signal에 abort 발화
→ fetch(backendUrl)도 abort
→ 백엔드 SseEmitter의 onError 콜백 발화
→ NotificationSseRegistry.removeEmitter() 즉시 실행
```

**문제**: Next.js App Router의 `request.signal`이 클라이언트 disconnect 시 실제 abort된다는 보장이 공식 문서에 명확하지 않음. 런타임(Node vs Edge), Vercel deployed vs 로컬 dev에 따라 동작이 다를 수 있음.

**최악 시나리오**: `request.signal`이 abort 안 되면 → upstream fetch 유지 → 백엔드 SseEmitter는 5분 timeout까지 좀비로 남음 → 사용자가 자주 새로고침하면 emitter 누적.

**검증 방법**:
1. 백엔드 `NotificationSseRegistry.register/removeEmitter`에 timestamp 로그 추가
2. 브라우저에서 SSE 연결 → 탭 닫기 → 로그에서 제거까지 걸린 시간 측정
3. 즉시 제거(<1초) ✅ / heartbeat 후(30초) ⚠️ / timeout 후(5분) ❌

**fallback (만약 ❌)**: 30초 주기 cleanup 스케줄러 추가 — 마지막 send 성공 시점 추적 + 60s+ 무응답 emitter 강제 complete.

### 의문점 2 — `preflightAuth`가 SSE 점유 없이 안전한가?

**위치**: sseClient.ts `preflightAuth` 함수(§5.3)

**의도**: EventSource는 응답 status를 직접 노출하지 않음. 별도 fetch로 401만 미리 감지하여 불필요한 5회 재시도 회피.

**문제 A — 일시적 emitter 2개 등록**:
```
preflight fetch  → 백엔드 emitter 1번 생성
controller.abort() → 백엔드 onError → 제거 (시간 차이)
EventSource(url) → 백엔드 emitter 2번 생성
```
타이밍에 따라 일시적으로 동일 사용자가 emitter 2개 점유 → MAX 10개 가드 한 칸 차지.

**문제 B — 브라우저 호환성**:
- Chrome/Firefox/Edge: AbortController + fetch 정상
- Safari 14 이하: AbortController-fetch 연동 일부 불완전

**문제 C — fetch가 SSE response를 받기 시작함**:
백엔드는 `Accept: text/event-stream`을 보면 SseEmitter를 즉시 생성하고 `connected` 이벤트 발사. preflight abort 전에 백엔드가 이미 emitter 등록 + 메시지 전송 시도. **다행히 abort된 클라이언트에 send 시도 시 IOException → completeWithError로 안전 정리**.

**검증 방법**:
1. 백엔드 로그: register/removeEmitter 호출 카운트
2. SSE 페이지 진입 → 정상이면 register 2 / removeEmitter 1 (안정 시 1개)
3. 이상하면 register 2 / removeEmitter 0 → preflight emitter가 좀비

**fallback (만약 ⚠️)**: `HEAD` 메서드로 preflight 시도. 백엔드에서 `HEAD /stream`은 emitter 생성 없이 401만 응답.

### 의문점 3 — V007 외래키가 실제로 적용되나? (옵션 B에서도 유효)

**위치**: `backend/.../db/migration/V007__create_notifications.sql`

이 의문점은 옵션 B에서도 그대로 유효. V007은 SSE와 무관한 DB 마이그레이션. 옵션 A 재도입 시 추가 검증 불필요.

---

## 8. Phase 8 PR #40 발견된 실제 버그와 수정 이력 (재도입 시 미리 적용)

PR #40 머지 직전 cross-review(codex)에서 발견된 실제 버그. 재도입 시 같은 실수 반복 방지.

### 버그 1: SSE emitter 누수 + 환경변수 문서화 (커밋 `f607108`)
- 증상: `MAX_EMITTERS_PER_USER` 초과 시 `oldest.complete()` 호출 전에 list에서 제거하지 않으면 onCompletion 콜백이 다시 removeEmitter 호출하며 race
- 수정: `removeEmitter(userId, oldest)` 먼저 → `oldest.complete()` 호출. `frontend/.env.example`에 `BACKEND_BASE_URL` 추가
- **재도입 시 반영**: §4.2 코드는 이미 수정본. 그대로 사용.

### 버그 2: SSE useEffect deps에 `markAllRead` 포함 (해소되지 않음, 미래 위험)
- 증상: `useEffect(..., [markAllRead])` + `markAllRead`가 `useCallback(..., [])` → 현재 마운트 1회만 실행. 향후 `markAllRead` 의존성 추가 시 SSE 재연결 위험
- 권고: 장기적으로 `useNotifications` SSE effect 분리 또는 `markAllRead`를 `useRef`로 래핑
- **재도입 시 반영**: §5.4 코드는 현재 상태 그대로. 재도입 시 useRef 패턴으로 리팩토링 검토.

### 버그 3: BFF SSE 라우트 `duplex: 'half'` + `@ts-ignore` 잔재 (해소됨)
- 증상: 초기 구현에서 `duplex: 'half'` + `@ts-ignore` 사용. Next.js 16에서 불필요
- 수정: 제거 후 `signal: request.signal` 명시
- **재도입 시 반영**: §5.2 코드는 이미 깨끗.

### 버그 4: 401 body-null EventSource 재연결 루프 (해소됨)
- 증상: 백엔드 401 응답에 body 없이 status만 반환 시 EventSource가 정상 종료가 아닌 onerror로 인식 → 5회 재시도
- 수정: BFF 라우트가 401/4xx 시 `Connection: close` 헤더 추가 + sseClient의 `preflightAuth`로 사전 차단
- **재도입 시 반영**: §5.2, §5.3 코드는 이미 수정본.

### 알려진 한계 (의도적 수용, 후속 작업)
| 항목 | 영향 | 후속 작업 |
|------|------|----------|
| MAX_EMITTERS TOCTOU race | 동시 등록 시 일시적 11-12개. 5분 timeout으로 정리 | `emitters.compute()` 블록으로 원자화 |
| `shownToastIds` 무제한 증가 | 장기 세션에서 number Set 메모리 누적 | LRU cap (50건) |
| `NotificationPanel.loadDetail` race | 빠른 연속 클릭 시 stale 응답 덮음 | AbortController |
| `registeredBy` 응답 노출 | FE 미사용. 내부 userId 누출 | 응답에서 제거 |
| `NotificationService.loadPinsByIds` N+1 | `PinRepository.findById` 반복 | `PinRepository.findAllById` 추가 |
| `@TransactionalEventListener` 동기 호출 | 수신자 N명일 때 스레드 점유 | `@Async` 도입 검토 |

---

## 9. 재도입 체크리스트

### Step 1: 백엔드 코드 복원
- [ ] `NotificationSseRegistry.java` 복원 (§4.2)
- [ ] `NotificationHeartbeatScheduler.java` 복원 (§4.3) — `@EnableScheduling`은 `RequestIdFilterConfig.java:23` 그대로
- [ ] `NotificationSsePushListener.java` 복원 (§4.4)
- [ ] `NotificationCreatedEvent.java` 복원 (§4.5)
- [ ] `NotificationService.createFor*` 메서드에 `eventPublisher.publishEvent(...)` 추가 (§4.7)
- [ ] `NotificationV1Controller.stream` 메서드 + `NotificationV1ApiSpec.stream` 시그니처 복원 (§4.6)
- [ ] `NotificationV1Dto.NotificationStreamEvent` record 복원

### Step 2: 프론트엔드 코드 복원
- [ ] `frontend/src/app/api/v1/notifications/stream/route.ts` 복원 (§5.2)
- [ ] `frontend/src/lib/notifications/sseClient.ts` 복원 (§5.3)
- [ ] `frontend/src/lib/notifications/api.ts`에 `NOTIFICATION_SSE_URL` 상수 추가 (`/api/v1/notifications/stream`)
- [ ] `frontend/src/lib/notifications/types.ts`에 `ConnectionState`, `NotificationStreamEvent` 추가
- [ ] `useNotifications.ts`의 mount/visibilitychange/focus 트리거 fetch 블록을 SSE 구독 블록으로 교체 (§5.4)
- [ ] `NotificationBell`에 회색 점(connectionState=failed) 시각 상태 복원

### Step 3: 환경변수
- [ ] `frontend/.env.example`에 `BACKEND_BASE_URL=http://localhost:8080` 추가
- [ ] staging/production 환경에서 `BACKEND_BASE_URL` 환경변수 설정 (Vercel)

### Step 4: 컨텍스트 문서 갱신
- [ ] `context/notification/README.md`: SSE 핵심 결정 항목 복원, fetch 트리거 정책 항목 제거
- [ ] `context/notification/architecture.md`: SSE 인프라 섹션 복원
- [ ] `context/notification/status.md`: FR-5~9/15/17, BR-6 → ✅ 변경
- [ ] `context/notification/glossary.md`: SSE/SseEmitter/heartbeat/preflight/BFF SSE 용어 부활
- [ ] `context/notification/PROJECTS.md`: 도메인 설명에 SSE 표현 복원
- [ ] `context/README.md` L19/L58: Phase 8 도메인/로드맵 행 갱신
- [ ] `docs/ops/phase-8-notifications.md`: 옵션 B 다운그레이드 노트 제거, 운영 검증 항목 복원

### Step 5: 테스트
- [ ] `NotificationSseRegistryTest.java` 통과 (사용자 100+ 동시 시뮬레이션 추가 권장)
- [ ] `NotificationServiceIT.java` 통과 (AFTER_COMMIT 발화 검증)
- [ ] `NotificationV1ControllerIntegrationTest.java` 통과 (401 + SSE 헤더 검증)
- [ ] `sseClient.test.ts` 통과 (백오프 + 5회 실패 → failed)
- [ ] `useNotifications.test.ts` 통과 (SSE prepend + toast + shownToastIds)

### Step 6: staging 실측 검증 (의문점 3건)
- [ ] **의문점 1**: 백엔드 register/remove 로그 추가 → 탭 닫기 → 즉시 제거(<1초) 확인
- [ ] **의문점 2**: register 2 / remove 1 (안정 시 1개) 패턴 확인
- [ ] **의문점 3**: V007 Flyway 적용 (이미 옵션 B에서 적용됐을 것이므로 재확인만)
- [ ] Vercel ↔ EC2 cross-origin EventSource 쿠키 전송 (SameSite=Lax 동작)
- [ ] Vercel/Cloudflare 프록시 SSE 버퍼링 (30초 heartbeat 즉시 도달, DevTools Network 탭)
- [ ] 다중 탭 SSE 독립 수신

### Step 7: 후속 개선 (선택, 진화 로드맵 단계 2-3)
- [ ] `MAX_EMITTERS` TOCTOU 원자화 (`emitters.compute()`)
- [ ] `shownToastIds` LRU cap (50건)
- [ ] `NotificationPanel.loadDetail` AbortController
- [ ] `registeredBy` 응답에서 제거
- [ ] `PinRepository.findAllById` 추가 → N+1 제거
- [ ] `@TransactionalEventListener` + `@Async` 검토
- [ ] Micrometer SSE 연결 수 메트릭 + Grafana 대시보드
- [ ] heartbeat 30s → 60s (사용자 500+ 시점)

---

## 10. 참고 자료

### Phase 8 원본 산출물
- PRD: [`.dev/feat-phase-8-notifications/prd.md`](../../.dev/feat-phase-8-notifications/prd.md) — AC-1~22 명세
- 설계서: [`.dev/feat-phase-8-notifications/design.md`](../../.dev/feat-phase-8-notifications/design.md) — 25단계 구현 순서
- Trust Ledger: [`.dev/feat-phase-8-notifications/trust-ledger.md`](../../.dev/feat-phase-8-notifications/trust-ledger.md) — 2회차 감사 + 모든 결정 이력
- Codemap: [`.dev/feat-phase-8-notifications/codemap.md`](../../.dev/feat-phase-8-notifications/codemap.md)
- 전체 diff: [`.dev/feat-phase-8-notifications/diff.txt`](../../.dev/feat-phase-8-notifications/diff.txt)

### git에서 SSE 코드 꺼내기
PR #40 머지 커밋과 후속 커밋:
```bash
# Phase 8 메인 구현
git show 27d8cb4 -- "*/notification/*" "*/notifications/*"

# SSE emitter 누수 + 환경변수 문서화 수정
git show f607108

# 옵션 B 다운그레이드 커밋 (예정)
# git show <future-hash>
```

### 운영 가이드 / 진화 로드맵
- 운영 가이드(옵션 B 반영 예정): [`docs/ops/phase-8-notifications.md`](../ops/phase-8-notifications.md)
- 진화 로드맵: [`docs/ops/notification-scaling-roadmap.md`](../ops/notification-scaling-roadmap.md) — 단계 1~5 트리거

### 관련 ADR
- [ADR-0001 Redis/Kafka 도입 검토 (폐기)](../adr/0001-redis-kafka-usage.md) — 단일 EC2 SseEmitter 전제
- [ADR-0002 Redis 제거 + Caffeine](../adr/0002-redis-removal-caffeine.md) — 외부 의존 최소화

### 외부 표준
- [W3C Server-Sent Events](https://www.w3.org/TR/eventsource/)
- [MDN Page Visibility API](https://developer.mozilla.org/en-US/docs/Web/API/Page_Visibility_API) — 옵션 B에서 사용
- [Spring `SseEmitter` Javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/SseEmitter.html)
- [Next.js App Router Route Handlers — Streaming](https://nextjs.org/docs/app/building-your-application/routing/route-handlers#streaming) — `signal: request.signal` 보장 여부 확인 (의문점 1)

---

## 작성 메타

- 작성일: 2026-05-21
- 작성자: rnqhstmd (옵션 B 다운그레이드 결정과 함께)
- 대상 독자: 미래의 본인 또는 재도입 시점 개발자
- 보존 정책: 옵션 A 재도입 후에도 본 문서는 유지 (이력 보존)
