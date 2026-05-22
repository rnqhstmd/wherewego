# Phase 8 운영 가이드 — 인앱 알림함 (SSE)

> Phase 8 구현 요약 + 검증 상태 + 의문점 + 운영 배포 체크리스트. PR [#40](https://github.com/rnqhstmd/wherewego/pull/40) 머지 전·후 운영 시 이 문서를 참조한다.

## 1. 구현 요약

### 변경 범위
- 41 파일, +3,500+ 라인
- Backend 신규 16 (notification 도메인 전체) + 수정 6
- Frontend 신규 9 (lib/notifications + 컴포넌트 5) + 수정 3
- DB: V007 마이그레이션 (`notifications`, `notification_pins` + 인덱스 3)
- BFF: SSE 전용 라우트 `frontend/src/app/api/v1/notifications/stream/route.ts`

### 핵심 결정
- **receiver_id 단일 컬럼 + 행 fan-out** — MVP 2인 그룹에서 미읽음 인덱스 단순화
- **트랜잭션 분리 (BR-3)** — 호출자(Controller/챗봇 핸들러)는 트랜잭션 밖에서 try-catch + NotificationService 자체 `@Transactional`(REQUIRED)
- **AFTER_COMMIT 이벤트 리스너 → SSE push** — DB 커밋 후 발화
- **단일 EC2 SseEmitter** (ADR-0001 일관) — 30초 heartbeat, 5분 timeout, 사용자당 최대 10개
- **BFF SSE 전용 라우트** — `request.signal` 연동 + preflight 401 즉시 failed + `X-Accel-Buffering: no`
- **영구 보관** (BR-2) — 만료 정책 없음

### AC 매핑
PRD AC-1 ~ AC-22 모두 코드에 구현 확인 (자세한 매핑은 `.dev/feat-phase-8-notifications/status.md` 참조).

---

## 2. 아키텍처

### 전체 흐름

```
[ PinV1Controller / 챗봇 Handler ] (트랜잭션 밖)
       │  try { notificationService.createFor*(...) }
       │  catch (RuntimeException) { log.warn(...) }   ← BR-3 호출자 격리
       ▼
[ NotificationService ] @Transactional (REQUIRED)
       │  1) findOtherActiveMemberIds(groupId, registeredBy) → receiverIds
       │  2) 각 receiverId마다 Notification + NotificationPin[] insert
       │  3) eventPublisher.publishEvent(NotificationCreatedEvent)
       ▼  (메서드 종료 = 트랜잭션 커밋)
[ NotificationSsePushListener ] @TransactionalEventListener(AFTER_COMMIT)
       ▼
[ NotificationSseRegistry.push(receiverId, event) ]
   → 각 SseEmitter.send (실패 시 removeEmitter + completeWithError)

[ NotificationHeartbeatScheduler ] @Scheduled(fixedRate=30s)
   → registry.broadcastHeartbeat() (comment ": heartbeat")
```

### 챗봇 트리거 4 경로 → 3 트리거 자동 커버

| 경로 | 진입 위치 | 트리거 위치 |
|------|----------|------------|
| `handleCandidates` | 직접 진입 | handleCandidates 끝부분 |
| `handleLegacySingle` | 직접 진입 | handleLegacySingle 단건 저장 분기 |
| `handleGoogleFallback` | 직접 진입 | handleGoogleFallback 단건 저장 분기 |
| `autoSaveOnExpiry` | scheduler 스레드 | `runBackgroundAutoSave → runParseAndCandidates → handleCandidates/handleLegacySingle` 체인을 통해 자동 커버 |
| `autoSavePreviousImmediately` | asyncCandidatesExecutor | 동일 체인 |

자세한 SSE 인프라/데이터 모델은 [`context/notification/architecture.md`](../../context/notification/architecture.md) 참조.

---

## 3. 검증 상태

### ✅ 검증 완료 (안전)
- Backend `compileJava` + `compileTestJava` 모두 SUCCESS
- Frontend `tsc --noEmit` EXIT 0
- AC-1 ~ AC-22 코드 매핑 확인 (인수 검증 ACCEPT)
- 트랜잭션 분리, 4 챗봇 경로 자동 커버, SSE Registry 동시성 모델
- phase-review 2회차 Critical 0건, HIGH 0건
- Codex cross-review Critical 0건

### ⚠️ 미검증 (운영/staging 검증 필요)
| 항목 | 검증 방법 |
|------|----------|
| **NotificationServiceIT / SseRegistryTest / V1ControllerIT 실제 실행** | `./gradlew :apps:wherewego-api:test` (Docker + Testcontainers 필요) |
| **FE Vitest 9 케이스 실행** | `cd frontend && npm test` |
| **V007 Flyway 마이그레이션 실제 적용** | 로컬 PostgreSQL + `./gradlew bootRun` 첫 부팅 |
| **SameSite=Lax + cross-origin EventSource 쿠키 전송** | staging 배포 후 브라우저에서 SSE 연결 확인 |
| **Vercel/Cloudflare 프록시 SSE 버퍼링** | staging에서 30초 heartbeat 즉시 도달 확인 (DevTools Network 탭) |
| **Next.js 16 Node runtime SSE streaming** | 로컬 `npm run dev`로 SSE 직접 호출 |

### 🟡 알려진 한계 (의도적 수용, 후속 개선)
| 항목 | 영향 | 후속 작업 |
|------|------|----------|
| MAX_EMITTERS TOCTOU race | 동시 등록 시 일시적 11-12개. 5분 timeout으로 정리 | `emitters.compute()` 블록으로 원자화 |
| `shownToastIds` 무제한 증가 | 장기 세션에서 number Set 메모리 누적 (수천 건 무시 가능) | LRU cap (50건) |
| `NotificationPanel.loadDetail` race | 빠른 연속 클릭 시 stale 응답 덮음 | AbortController |
| SSE useEffect deps에 `markAllRead` 포함 | useCallback([])로 안정. 미래 수정 시 SSE 재구독 위험 | useRef 패턴 |
| `registeredBy` 응답 노출 | FE 미사용. 내부 userId 누출 | 응답에서 제거 |
| `NotificationService.loadPinsByIds` N+1 | `PinRepository.findById` 반복. 50건×N핀에서 무해 | `PinRepository.findAllById` 추가 |
| `@TransactionalEventListener` 동기 호출 | 수신자 N명일 때 스레드 점유 누적. MVP 무해 | `@Async` 도입 검토 |

---

## 4. 솔직한 의문점 3건 (상세)

### 의문점 1: `signal: request.signal`이 Next.js 16에서 정말 동작하나?

**위치**: `frontend/src/app/api/v1/notifications/stream/route.ts`

```typescript
const upstream = await fetch(backendUrl, {
  method: 'GET',
  headers: { ..., 'Cookie': cookieHeader },
  signal: request.signal,  // ← 이 부분
});
```

**의도**:
```
사용자 탭 닫음 → 브라우저가 EventSource 종료
→ Next.js가 request.signal에 abort 발화
→ fetch(backendUrl)도 abort
→ 백엔드 SseEmitter의 onError 콜백 발화
→ NotificationSseRegistry.removeEmitter() 즉시 실행
```

**문제**: Next.js App Router의 `request.signal`이 클라이언트 disconnect 시 실제 abort된다는 보장이 **공식 문서에 명확하지 않음**. 런타임(Node vs Edge), Vercel deployed vs 로컬 dev에 따라 동작이 다를 수 있음.

**최악 시나리오**: `request.signal`이 abort 안 되면 → upstream fetch 유지 → 백엔드 SseEmitter는 5분 timeout까지 좀비로 남음 → 사용자가 자주 새로고침하면 emitter 누적.

**검증 방법**:
1. 백엔드 `NotificationSseRegistry.register/removeEmitter`에 timestamp 로그 추가
2. 브라우저에서 SSE 연결 → 탭 닫기 → 로그에서 제거까지 걸린 시간 측정
3. 즉시 제거(<1초) ✅ / heartbeat 후(30초) ⚠️ / timeout 후(5분) ❌

---

### 의문점 2: preflightAuth가 SSE 점유 없이 안전한가?

**위치**: `frontend/src/lib/notifications/sseClient.ts`

```typescript
async function preflightAuth() {
  const controller = new AbortController();
  const res = await fetch(options.url, {
    headers: { 'Accept': 'text/event-stream' },
    signal: controller.signal,
  });
  controller.abort();  // ← status 받자마자 즉시 abort
  if (res.status === 401 || res.status === 403) return 'unauthorized';
  return 'ok';
}

// 401이면 EventSource 시도 안 함 (5번 재시도 회피)
const preflight = await preflightAuth();
if (preflight === 'unauthorized') { setState('failed'); return; }
eventSource = new EventSource(url, { withCredentials: true });
```

**의도**: EventSource는 응답 status를 직접 노출하지 않음. 별도 fetch로 401만 미리 감지하여 불필요한 5회 재시도 회피.

**문제 A — 일시적 emitter 2개 등록**:
```
preflight fetch  → 백엔드 emitter 1번 생성
controller.abort() → 백엔드 onError → 제거 (시간 차이)
EventSource(url) → 백엔드 emitter 2번 생성
```
타이밍에 따라 일시적으로 동일 사용자가 emitter 2개 점유 → MAX 10개 가드 한 칸 차지.

**문제 B — 브라우저 호환성**:
- Chrome/Firefox/Edge: AbortController + fetch 정상 동작
- Safari 14 이하: AbortController-fetch 연동 일부 불완전
- 모바일 브라우저: 대부분 OK

**문제 C — fetch가 SSE response를 받기 시작함**:
백엔드는 `Accept: text/event-stream`을 보면 SseEmitter를 즉시 생성하고 `connected` 이벤트를 발사. preflight abort 전에 백엔드가 이미 emitter 등록 + 메시지 전송 시도. **다행히 abort된 클라이언트에 send 시도 시 IOException → completeWithError로 안전 정리**.

**검증 방법**:
1. 백엔드 로그: register/removeEmitter 호출 카운트
2. SSE 페이지 진입 → 정상이면 register 2 / removeEmitter 1 (안정 시 1개)
3. 이상하면 register 2 / removeEmitter 0 → preflight emitter가 좀비

---

### 의문점 3: V007 외래키가 실제로 적용되나?

**위치**: `backend/.../db/migration/V007__create_notifications.sql`

```sql
CREATE TABLE notifications (
    group_id BIGINT NOT NULL REFERENCES groups (id),
    receiver_id BIGINT NOT NULL REFERENCES users (id),
    registered_by BIGINT NOT NULL REFERENCES users (id),
    ...
);
CREATE TABLE notification_pins (
    pin_id BIGINT NOT NULL REFERENCES pins (id),
    ...
);
```

**의문점들**:

**A. 테이블 존재 여부**: V001~V006이 모두 정확히 실행되어 `groups`, `users`, `pins` 테이블이 있어야 V007 성공. 코드 검증은 했지만 실제 Flyway 실행은 안 함.

**B. 컬럼 타입 일치**: PostgreSQL FK는 참조 컬럼과 동일 타입이어야 함.
- `groups.id`, `users.id`, `pins.id`가 모두 `BIGINT` 또는 `BIGSERIAL`(내부 BIGINT)이어야 OK
- `SERIAL` (INTEGER)이면 타입 불일치로 V007 실패

**C. ON DELETE 정책**:
```sql
notification_id ... ON DELETE CASCADE   -- ✅ 명시
pin_id ... (정책 미지정 = RESTRICT 기본)  -- ⚠️ pins가 hard delete되면 막힘
```
현재 `pins`는 soft delete이므로 무해, 그러나 미래 정책 변경 시 위험.

**최악 시나리오**: Spring Boot 부팅 시 Flyway가 V007 실행 → 타입 불일치 또는 테이블 부재 → 즉시 에러 → 서버 못 뜸.

**검증 방법**:
```bash
cd backend
docker-compose -f docker/infra-compose.yml up -d  # PostgreSQL
./gradlew :apps:wherewego-api:bootRun
```

성공:
```
Migrating schema "public" to version "7 - create notifications"
Successfully applied 1 migration
```

실패 예:
```
ERROR: relation "groups" does not exist
ERROR: foreign key constraint cannot be implemented (datatype mismatch)
```

---

## 5. 운영 배포 전 체크리스트

### 머지 전 (개발자 환경)
- [ ] `./gradlew :apps:wherewego-api:test` 실행 → NotificationServiceIT 통과 확인
- [ ] `cd frontend && npm test` 실행 → Vitest 9 케이스 통과 확인
- [ ] 로컬 PostgreSQL + `./gradlew bootRun`로 V007 Flyway migrate 성공 확인
- [ ] 로컬 `npm run dev` + 로그인 후 SSE 연결 → DevTools에서 `notification` 이벤트 수신 확인

### 머지 후 (staging)
- [ ] staging에 배포 후 모바일/데스크탑 양쪽 환경에서 다음 흐름 확인:
  - 벨 아이콘 + 빨간 점 표시
  - 새 알림 수신 시 말풍선 5초 자동 닫힘
  - 패널 열기 → 핀 클릭 → flyTo + PinPopup
- [ ] DevTools Network 탭에서 `/api/v1/notifications/stream` 응답이 streaming (chunked) 확인
- [ ] 30초 heartbeat가 즉시 도달하는지 확인 (버퍼링 없음)
- [ ] 다중 탭에서 SSE 독립 수신 확인
- [ ] 탭 닫기 후 백엔드 emitter 즉시 정리 로그 확인 (의문점 1 검증)
- [ ] 로그아웃 상태에서 SSE 연결 시도 → preflight로 즉시 failed 상태 확인 (의문점 2 검증)
- [ ] `frontend/.env.example` 기준으로 staging 환경변수 모두 설정

### 운영 전 (production)
- [ ] DB 백업 스냅샷 확보 (V007 적용 전)
- [ ] V007 Flyway migration이 production DB에 성공 적용
- [ ] 운영 도메인(Vercel ↔ EC2) 간 SameSite + 쿠키 전달 검증
- [ ] 모니터링: SSE 연결 수 / heartbeat 실패율 / emitter 정리 시간 메트릭 추가 권장

---

## 6. 롤백 절차

### 코드 롤백 (Vercel + EC2)
- Vercel: 이전 deployment로 revert (즉시)
- EC2: 이전 jar로 재시작 (1~2분 다운타임)

### DB 롤백 (V007 제거)
V007은 **신규 테이블 추가만** 했으므로 기존 데이터 영향 없음. 롤백 시:
```sql
-- 신규 테이블 삭제 (CASCADE로 notification_pins도 함께 정리)
DROP TABLE IF EXISTS notification_pins CASCADE;
DROP TABLE IF EXISTS notifications CASCADE;
```

Flyway는 `flyway_schema_history`에서 V007 항목을 수동 삭제해야 다음 부팅 시 재적용 가능:
```sql
DELETE FROM flyway_schema_history WHERE version = '7';
```

기존 핀 데이터는 무관, 알림 데이터만 유실.

---

## 7. 알림 관련 후속 작업 (Trust Ledger 기록)

| 우선순위 | 항목 | 사유 |
|----------|------|------|
| **P1** | `request.signal` 동작 staging 실측 | Emitter 좀비 위험 |
| **P1** | V007 Flyway 실제 적용 검증 | 부팅 실패 위험 |
| **P2** | `PinRepository.findAllById` 추가 → N+1 제거 | 알림 50건 × N핀 누적 시 |
| **P2** | `registeredBy` 응답 노출 제거 | 최소 권한 원칙 |
| **P3** | 조사 처리 (을/를 받침) | UX 자연어 품질 |
| **P3** | `notification_pins.pin_id` ON DELETE 정책 ADR | 향후 hard delete 정책 변경 시 |
| **P3** | `shownToastIds` LRU cap | 장기 세션 메모리 |
| **P4** | MAX_EMITTERS TOCTOU 원자화 | 수평 확장 시 |
| **P4** | `@TransactionalEventListener` `@Async` 도입 | 수신자 N명 시 |
| **P4** | 카카오톡 푸시 알림 추가 (앱 비활성 사용자) | 사용자 활성도 ↑ |

---

## 관련 산출물

- PRD: [`.dev/feat-phase-8-notifications/prd.md`](../../.dev/feat-phase-8-notifications/prd.md)
- 설계서: [`.dev/feat-phase-8-notifications/design.md`](../../.dev/feat-phase-8-notifications/design.md)
- Trust Ledger: [`.dev/feat-phase-8-notifications/trust-ledger.md`](../../.dev/feat-phase-8-notifications/trust-ledger.md)
- 도메인 문서: [`context/notification/`](../../context/notification/)
- 변경 추적: [`context/notification/status.md`](../../context/notification/status.md)
