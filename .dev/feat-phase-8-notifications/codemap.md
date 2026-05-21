## 코드 맵: Phase 8 — 인앱 알림함 (SSE)

### 핵심 파일 (변경/신규 예상)

#### Backend
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinService.java:152` → `addPin(userId, groupId, cmd)` 완료 후 `NotificationService.createManualNotification` 호출 추가 (MANUAL_PIN 트리거)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/ChatbotWebhookService.java:60` → `handle()` 릴스 처리 완료 분기에서 `NotificationService.createChatbotNotification(groupId, registeredBy, pinIds)` 호출 (CHATBOT_PINS, 릴스 1건=알림 1건)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/InstagramLinkHandler.java` → 핀 N개 등록 묶음 결과를 webhook 서비스로 전달
- (신규) `backend/.../domain/notification/Notification.java` → 도메인 엔티티 (id, groupId, registeredBy, type, createdAt, readAt)
- (신규) `backend/.../domain/notification/NotificationPin.java` → 조인 엔티티 (notificationId, pinId)
- (신규) `backend/.../domain/notification/NotificationService.java` → 알림 생성 + 조회 + 읽음 처리 + SSE publish
- (신규) `backend/.../domain/notification/NotificationSseRegistry.java` → 사용자별 SseEmitter 관리 (구독/해지/푸시)
- (신규) `backend/.../interfaces/api/notification/NotificationV1Controller.java` → `GET /api/v1/notifications/stream` (SSE), `GET /api/v1/notifications`, `PATCH .../{id}/read`
- (신규) `backend/.../src/main/resources/db/migration/V007__create_notifications.sql` → `notifications` 테이블 + `notification_pins` 조인 테이블

#### Frontend
- `frontend/src/app/map/_components/MobileTopNav.tsx` → 우상단 벨 아이콘 + 빨간 점 + SpeechBubblePopup 트리거 추가
- (신규) `frontend/src/app/map/_components/NotificationBell.tsx` → 벨 아이콘 컴포넌트 (미읽음 상태 표시)
- (신규) `frontend/src/app/map/_components/NotificationPanel.tsx` → 알림 목록 패널 (장소 N개 리스트)
- (신규) `frontend/src/lib/notifications/sseClient.ts` → EventSource 기반 SSE 구독 + 재연결
- (신규) `frontend/src/lib/notifications/useNotifications.ts` → 알림 상태 훅 (목록/미읽음/구독)
- `frontend/src/app/map/MapClient.tsx` → 알림 상세 클릭 시 `flyTo` + 핀 팝업 오픈 핸들러 연결

### 참조 파일 (의존성/참고)

- `frontend/src/components/ui/SpeechBubblePopup.tsx` → 새 알림 수신 시 1회 노출 (외부 탭 시 자동 닫힘 기존 동작 활용)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/group/GroupMemberRepositoryImpl.java` → 같은 그룹의 다른 멤버 조회 (수신자 결정). `findOtherActiveMembers(groupId, excludeUserId)` 메서드 신규 추가 대상
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/group/GroupV1Controller.java` → 인증/그룹 권한 검증 패턴 참조
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/pin/PinV1Controller.java` → addPin 호출부 구조 (트랜잭션 범위 경계)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/PlaceSelectionHandler.java` → PLACE_SELECTION 경로의 핀 저장 분기 (알림 트리거 필요 여부)
- `frontend/src/app/map/_hooks/useGroupPinSync.ts` → SSE 클라이언트 훅 설계 시 기존 polling 패턴 참조
- `backend/apps/wherewego-api/src/main/resources/db/migration/V006__renew_tag_constraint_and_migrate.sql` → Flyway 마이그레이션 단일 트랜잭션 패턴 (V007 작성 참조)
- `frontend/src/app/map/_components/PinPopup.tsx` → 핀 팝업 오픈 방식 참조 (알림 → 핀 진입)

### 설정
- `backend/apps/wherewego-api/src/main/resources/application.yml:27-32` → `spring.task.scheduling.pool.size: 1` 이미 설정. `@EnableScheduling` 활성 위치 확인 필요
- `context/README.md:57` → Phase 8 명세 (정합성 기준)
- `context/pin/status.md:29` → FR-PIN MANUAL_PIN 트리거 명세
- `context/chatbot/status.md:31` → FR-CHATBOT CHATBOT_PINS 트리거 명세

### 인증/보안 패턴 (architect 탐색 추가)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/config/security/SecurityConfig.java:35-46` → `.anyRequest().authenticated()` 자동 보호
- `backend/apps/wherewego-api/src/main/java/com/wherewego/config/security/JwtAuthenticationFilter.java:24` → cookie `access_token` 기반 인증
- `backend/apps/wherewego-api/src/main/java/com/wherewego/config/security/AuthUserArgumentResolver.java:30-34` → `@AuthUser Long userId` 자동 주입
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/ApiResponse.java` → 응답 envelope

### 프론트엔드 패턴 (architect 탐색 추가)
- `frontend/src/app/map/_components/MobileTopNav.tsx:162-196` → 우상단 프로필 Link 위치/스타일 (벨 교체 슬롯)
- `frontend/src/app/map/_components/DesktopActionPill.tsx:142-205` → 하단 프로필 위 NotificationBell 슬롯 삽입 가능
- `frontend/src/app/map/MapClient.tsx:866-883` → 룰렛 "지도에서 보기" 패턴 (flyTo + setSelectedPinId)
- `frontend/src/app/map/_hooks/useGroupPinSync.ts:41-126` → cleanup/abort/401 정지 패턴 (SSE 클라이언트 모범)
