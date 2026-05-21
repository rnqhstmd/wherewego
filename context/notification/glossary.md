# notification 용어 사전

## 알림 분류

- **인앱 알림**: 카카오톡 푸시 없이 웹/모바일 앱 내부 UI(벨 아이콘 + 패널 + 말풍선)로 노출되는 알림.
- **MANUAL_PIN**: 사용자가 웹에서 직접 핀을 등록한 경우의 알림 유형 (단건 핀).
- **CHATBOT_PINS**: 챗봇으로 릴스 또는 장소 카드 선택을 통해 핀이 등록된 경우의 알림 유형 (1~N개 핀 묶음).

## SSE (Server-Sent Events)

- **SSE 스트림**: HTTP/1.1 long-lived 응답으로 서버가 클라이언트에 단방향으로 이벤트를 push하는 방식. WebSocket과 달리 단방향이며 텍스트(text/event-stream) 기반.
- **SseEmitter**: Spring MVC가 제공하는 SSE 응답 객체. `complete`/`completeWithError`/`onTimeout`/`onCompletion`/`onError` 라이프사이클 콜백을 가진다.
- **heartbeat**: 30초마다 SSE 연결 유지를 위해 발사되는 comment 형식 이벤트(`: heartbeat`). 클라이언트는 별도 처리 없이 연결만 유지.
- **preflight authentication**: SSE 연결 전 별도 fetch로 401/403을 사전 감지하는 패턴. EventSource는 응답 status를 직접 노출하지 않아 재연결 루프를 막기 위함.

## 트랜잭션 패턴

- **fan-out**: 단일 입력 이벤트(핀 등록)에서 다수 수신자(receiverId N명)에게 알림 row N개를 생성하는 패턴.
- **AFTER_COMMIT 이벤트**: `@TransactionalEventListener(phase = AFTER_COMMIT)`로 트랜잭션 커밋 직후 발화하는 이벤트. DB 일관성이 보장된 시점에 외부 액션(SSE push) 실행.
- **트랜잭션 격리 (BR-3)**: 핀 트랜잭션과 알림 트랜잭션을 분리하여 알림 실패가 핀 저장에 영향을 주지 않도록 하는 정책. NotificationService 자체 `@Transactional` + 호출자 try-catch 이중 보장.

## UX 요소

- **벨 아이콘**: 모바일 우상단/데스크탑 사이드바에 표시되는 알림 진입점. 미읽음 시 빨간 점, 연결 끊김 시 회색 점.
- **알림 말풍선 (NotificationToast)**: SSE 신규 알림 수신 시 벨 아이콘 옆에 1회 노출되는 작은 카드. 5초 자동 닫힘 또는 외부 탭 시 닫힘.
- **알림 패널 (NotificationPanel)**: 벨 클릭 시 열리는 알림 목록. 모바일은 Sheet, 데스크탑은 SidePanel(left:66). 동시 1개 패널 정책으로 다른 액션 시트 자동 닫힘.
- **flyTo**: 알림 상세에서 핀 클릭 시 Mapbox GL JS의 `map.flyTo({center, zoom: 14})`로 해당 좌표로 부드럽게 이동하는 동작.

## 인프라

- **BFF (Backend For Frontend)**: Next.js App Router의 `app/api/[...path]/route.ts` catch-all 라우트가 백엔드 API를 same-origin 프록시로 노출하는 패턴. SSE는 streaming이라 별도 전용 라우트(`app/api/v1/notifications/stream/route.ts`) 사용.
- **X-Accel-Buffering: no**: nginx 등 리버스 프록시의 응답 버퍼링을 비활성화하는 비표준 HTTP 헤더. SSE의 즉시 전달을 보장.
- **TOCTOU race (Time-Of-Check to Time-Of-Use)**: 검사 시점과 사용 시점 사이에 다른 스레드가 상태를 변경하여 발생하는 동시성 문제. MAX_EMITTERS_PER_USER 가드의 알려진 한계.
- **DoS 가드**: 사용자당 최대 10개 SseEmitter 제한으로 무한 연결로 인한 메모리 고갈 방지.
