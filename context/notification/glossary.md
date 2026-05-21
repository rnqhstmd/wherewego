# notification 용어 사전

## 알림 분류

- **인앱 알림**: 카카오톡 푸시 없이 웹/모바일 앱 내부 UI(벨 아이콘 + 패널 + 말풍선)로 노출되는 알림.
- **MANUAL_PIN**: 사용자가 웹에서 직접 핀을 등록한 경우의 알림 유형 (단건 핀).
- **CHATBOT_PINS**: 챗봇으로 릴스 또는 장소 카드 선택을 통해 핀이 등록된 경우의 알림 유형 (1~N개 핀 묶음).

## 트랜잭션 패턴

- **fan-out**: 단일 입력 이벤트(핀 등록)에서 다수 수신자(receiverId N명)에게 알림 row N개를 생성하는 패턴.
- **트랜잭션 격리 (BR-3)**: 핀 트랜잭션과 알림 트랜잭션을 분리하여 알림 실패가 핀 저장에 영향을 주지 않도록 하는 정책. NotificationService 자체 `@Transactional` + 호출자 try-catch 이중 보장.

## Fetch 트리거 (옵션 B 다운그레이드, 2026-05-21)

- **mount fetch**: 클라이언트 컴포넌트 최초 마운트 시 `GET /notifications` 호출. 초기 items + unreadCount 초기화. 이 시점은 toast 미노출.
- **Page Visibility API**: 브라우저 표준 API. `document.visibilityState`가 `visible`로 변할 때(`visibilitychange` 이벤트) 사용자가 탭/앱으로 돌아왔음을 감지. 옵션 B에서 알림 갱신 트리거로 사용.
- **focus 이벤트**: window 포커스 복귀 감지. visibilitychange와 함께 사용해 다른 탭에서 돌아온 경우도 커버.
- **신규 알림 감지 (FR-15 변형)**: fetch 결과의 최대 알림 id가 직전 캐시의 최대 id를 초과할 때 신규로 판단. 1회 말풍선 노출 트리거.
- **shownToastIds**: 동일 알림 id에 대해 toast가 중복 노출되지 않도록 추적하는 클라이언트 Set.

## UX 요소

- **벨 아이콘**: 모바일 우상단/데스크탑 사이드바에 표시되는 알림 진입점. 미읽음 시 빨간 점.
- **알림 말풍선 (NotificationToast)**: 신규 알림 감지 시 벨 아이콘 옆에 1회 노출되는 작은 카드. 5초 자동 닫힘 또는 외부 탭 시 닫힘.
- **알림 패널 (NotificationPanel)**: 벨 클릭 시 열리는 알림 목록. 모바일은 Sheet, 데스크탑은 SidePanel(left:66). 동시 1개 패널 정책으로 다른 액션 시트 자동 닫힘.
- **flyTo**: 알림 상세에서 핀 클릭 시 Mapbox GL JS의 `map.flyTo({center, zoom: 14})`로 해당 좌표로 부드럽게 이동하는 동작.

## 보류된 SSE 용어 (재도입 시 재정의)

옵션 B 다운그레이드(2026-05-21)로 제거된 항목. 사용자 100명+ 시점 재평가 시 재도입.

- ~~SSE (Server-Sent Events)~~
- ~~SseEmitter~~
- ~~heartbeat (30초 SSE 연결 유지)~~
- ~~preflight authentication~~
- ~~AFTER_COMMIT 이벤트~~
- ~~BFF SSE 전용 라우트~~
- ~~X-Accel-Buffering: no~~
- ~~TOCTOU race (MAX_EMITTERS 가드)~~
- ~~DoS 가드 (사용자당 최대 10 emitter)~~
- ~~회색 점(연결 끊김 상태)~~
