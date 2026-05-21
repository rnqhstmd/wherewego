# notification 도메인

> 그룹원 핀 등록 시 상대방에게 앱 내 실시간 알림을 생성·전달하는 도메인. 카카오톡 푸시 미사용, SSE 기반.

## 개요

Phase 8(PR [#40](https://github.com/rnqhstmd/wherewego/pull/40))에서 신규로 도입된 도메인. 핀 도메인의 등록 이벤트를 트리거로 받아 같은 그룹의 다른 활성 멤버에게 알림 row를 생성하고, SSE 연결이 있으면 실시간으로 push한다.

## 핵심 결정

- **receiver_id 단일 컬럼 + 행 fan-out** — MVP 2인 그룹에서 미읽음 인덱스 단순화. 그룹 N인 확장 시 (N-1)배 row.
- **트랜잭션 분리(BR-3)** — 호출자(Controller/챗봇 핸들러)는 트랜잭션 밖에서 try-catch로 알림 호출. NotificationService는 자체 `@Transactional`(REQUIRED)로 새 트랜잭션을 시작·커밋한 뒤 `@TransactionalEventListener(AFTER_COMMIT)`이 SSE push 발화.
- **SSE 인프라** — `SseEmitter` Registry(ConcurrentHashMap + CopyOnWriteArrayList) + 30초 heartbeat + 5분 timeout + 사용자당 최대 10개 emitter DoS 가드. 추가 인프라 없이 단일 EC2 전제(ADR-0001).
- **영구 보관** — 만료 정책 없음. MVP 규모에서 DB 부담 없음.

## 알림 유형

| 유형 | 트리거 | 묶음 단위 |
|------|--------|----------|
| `MANUAL_PIN` | 웹 직접 등록 (`PinV1Controller.createPin`) | 단건 핀 |
| `CHATBOT_PINS` | 챗봇 릴스 자동 저장 + 장소 카드 선택 | 1~N개 핀 |

## API

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| GET | `/api/v1/notifications/stream` | JWT 쿠키 | SSE 스트림 (events: `connected`, `notification`, `: heartbeat`) |
| GET | `/api/v1/notifications` | JWT 쿠키 | 최신순 ≤50건 + unreadCount |
| POST | `/api/v1/notifications/read-all` | JWT 쿠키 | 미읽음 전체 읽음 처리 |
| GET | `/api/v1/notifications/{id}` | JWT 쿠키 | 알림 단건 상세 (핀 목록 + deleted 플래그) |

## 의존 도메인

- [pin](../pin/README.md) — `PinService.addPin` 트리거, 핀 메타 조회 (placeName/address/좌표)
- [group](../group/README.md) — `findOtherActiveMemberIds`로 수신자 fan-out
- [chatbot](../chatbot/README.md) — `InstagramLinkHandler`/`PlaceSelectionHandler`에서 트리거

## 상세 문서

- [architecture.md](architecture.md) — SSE 인프라, 트랜잭션 경계, BFF SSE 라우트
- [status.md](status.md) — FR/BR/AC 진행도
- [glossary.md](glossary.md) — 도메인 용어
- [PROJECTS.md](PROJECTS.md) — 소스 경로 매핑

## 후속 작업 (Trust Ledger 기록)

- `registeredBy` 응답 노출 제거 (FE 미사용)
- 조사 처리 자동화 (을/를 — 받침 유무)
- `notification_pins.pin_id` ON DELETE 정책 ADR
- SSE effect `useRef` 패턴 전환 (잠재 deps 위험)
- `NotificationPanel.loadDetail` 에러 처리
- MAX_EMITTERS_PER_USER TOCTOU race 원자화 (`compute` 블록)
- `shownToastIds` LRU cap (장기 세션 메모리)
