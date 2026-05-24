# notification 도메인

> 그룹원 핀 등록 시 상대방에게 앱 내 알림을 생성하는 도메인. 카카오톡 푸시·SSE 미사용, mount/visibility 트리거 fetch 기반.

## 개요

Phase 8(PR [#40](https://github.com/rnqhstmd/wherewego/pull/40))에서 신규로 도입된 도메인. 핀 도메인의 등록 이벤트를 트리거로 받아 같은 그룹의 다른 활성 멤버에게 알림 row를 생성한다. 클라이언트는 마운트/탭 활성화(`visibilitychange`)/포커스 시 REST API로 알림 목록을 새로 조회하여 빨간 점 및 패널을 갱신한다.

## 핵심 결정

- **receiver_id 단일 컬럼 + 행 fan-out** — MVP 2인 그룹에서 미읽음 인덱스 단순화. 그룹 N인 확장 시 (N-1)배 row.
- **트랜잭션 분리(BR-3)** — 호출자(Controller/챗봇 핸들러)는 트랜잭션 밖에서 try-catch로 알림 호출. NotificationService는 자체 `@Transactional`(REQUIRED)로 새 트랜잭션을 시작·커밋. 알림 실패가 핀 저장에 영향을 주지 않음.
- **fetch 트리거 정책 (옵션 B 다운그레이드, 2026-05-21)** — `useNotifications` 훅이 mount + `visibilitychange`(앱 다시 켤 때) + `focus`(다른 탭에서 복귀) 이벤트에서 `GET /notifications` 호출. SSE/폴링 없음. 실시간성을 포기하는 대신 인프라 단순화. 사용자 100명+ 시점에 SSE 재도입 검토.
- **영구 보관** — 만료 정책 없음. MVP 규모에서 DB 부담 없음.

## 알림 유형

| 유형 | 트리거 | 묶음 단위 |
|------|--------|----------|
| `MANUAL_PIN` | 웹 직접 등록 (`PinV1Controller.createPin`) | 단건 핀 |
| `CHATBOT_PINS` | 챗봇 릴스 자동 저장 + 장소 카드 선택 | 1~N개 핀 |
| `VISIT_DETECTED` | WISH/REEL → MEMORY 자동 전환 (`PinV1Controller.updatePin`, Phase 10) | 단건 핀. 본인 포함 fan-out (Phase 11 도입 전 과도기) |

## API

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| GET | `/api/v1/notifications` | JWT 쿠키 | 최신순 ≤50건 + unreadCount |
| POST | `/api/v1/notifications/read-all` | JWT 쿠키 | 미읽음 전체 읽음 처리 |
| GET | `/api/v1/notifications/{id}` | JWT 쿠키 | 알림 단건 상세 (핀 목록 + deleted 플래그) |

## 의존 도메인

- [pin](../pin/README.md) — `PinService.addPin` 트리거, 핀 메타 조회 (placeName/address/좌표)
- [group](../group/README.md) — `findOtherActiveMemberIds`로 수신자 fan-out
- [chatbot](../chatbot/README.md) — `InstagramLinkHandler`/`PlaceSelectionHandler`에서 트리거

## 상세 문서

- [architecture.md](architecture.md) — fetch 트리거 정책, 트랜잭션 경계, 데이터 모델
- [status.md](status.md) — FR/BR/AC 진행도 (옵션 B 다운그레이드 반영)
- [glossary.md](glossary.md) — 도메인 용어
- [PROJECTS.md](PROJECTS.md) — 소스 경로 매핑

## 후속 작업 (Trust Ledger 기록)

- `registeredBy` 응답 노출 제거 (FE 미사용)
- 조사 처리 자동화 (을/를 — 받침 유무)
- `notification_pins.pin_id` ON DELETE 정책 ADR
- `NotificationPanel.loadDetail` 에러 처리
- `NotificationPanel` 빠른 연속 클릭 race condition (AbortController)

## SSE 재도입 트리거 (보류)

옵션 B 다운그레이드로 SSE 인프라(Registry/Heartbeat/Listener/BFF stream route/sseClient)를 제거함. 다음 조건 도달 시 Phase 8 옵션 A로 재도입 검토:

- 활성 사용자 100명+ 도달
- 실시간 알림 미수신에 대한 사용자 피드백 누적
- 또는 [노티 인프라 진화 로드맵](../../docs/ops/notification-scaling-roadmap.md) 단계 3(500~5,000명) 도달

재도입 시 **처음부터 다시 설계하지 말 것**. [Phase 8 SSE 인프라 아카이브](../../docs/architecture/notification-sse-archive.md)에 전체 구현 코드(`NotificationSseRegistry`, `NotificationHeartbeatScheduler`, `NotificationSsePushListener`, `NotificationCreatedEvent`, BFF SSE 라우트, `sseClient.ts`, `useNotifications` SSE 구독 블록), 결정 근거, 의문점 3건, 발견된 실제 버그 이력, 재도입 체크리스트가 보존되어 있다. 그대로 복원 후 의문점만 staging 재검증.
