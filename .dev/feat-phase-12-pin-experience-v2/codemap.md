## 코드 맵: Phase 12 — Pin Experience v2 (WANT 시스템 · 챗봇 v2 재설계 · 오래된 핀 정리)

### 핵심 파일
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/Pin.java → JPA Entity `pins`. tag(REEL/WISH/MEMORY)·memo·visited_at 보유. Phase 12: `want_count INT NOT NULL DEFAULT 0` 컬럼/필드 추가, `applyWantCount`/`transitionToWish` 도메인 메서드 신설
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinService.java → 핀 CRUD 서비스(PESSIMISTIC_WRITE, `listGroupPins`). Phase 12: `WantService` 신설(같은 패키지 또는 분리) — 토글 트랜잭션(SELECT FOR UPDATE → INSERT/DELETE → want_count update → 과반시 tag=WISH + AFTER_COMMIT 이벤트)
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationType.java → enum {MANUAL_PIN, CHATBOT_PINS, VISIT_DETECTED}. Phase 12: `WISH_CONVERTED` 추가 + V012 DB CHECK 갱신
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/PendingInstagramSession.java → 인스타 URL 단순 pending(Caffeine). Phase 12: `ReelSavedSelectionSession`(MULTI/SINGLE/BULK/MEMO 상태머신) 신설 + 본 클래스 + `PendingNotificationSession`/`RecentlyAutoSavedSession` 재배치/폐기
- frontend/src/lib/pin/markers.tsx → 핀 마커 글리프 단일 소스(REEL=#7BB3E8 원/WISH=#F4C842 별/MEMORY=#FFB3C6 하트). Phase 12: 관심(WANT≥1, 과반 미달) #7B68EE 1.1배 원형 + WISH 전환 펄스 keyframe(0.5초) + WANT 카운트 글리프

### 참조 파일
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinTag.java → REEL/WISH/MEMORY enum. Phase 12: 상태 의미 명확화 (관심은 별도 enum 아님, `tag=REEL AND want_count>=1` 파생)
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinRepository.java → 포트 인터페이스(page/count 4메서드 + PESSIMISTIC_WRITE). Phase 12: `findByIdForUpdate` 재사용 + cleanup 후보 조회 메서드 추가
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationService.java → 알림 생성/조회(@Transactional, BR-3 격리). Phase 12: `createForWishConverted(groupId, pinId, triggerUserId)` 신설 + fan-out(N-1)
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/pin/PinV1Controller.java → REST 컨트롤러(`/api/v1/groups/{gid}/pins`). Phase 12: WANT 토글 POST + 상태 GET + cleanup candidates/execute API 추가
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/pin/PinV1Dto.java → 요청/응답 DTO. Phase 12: `wantCount`/`myWant` 응답 필드, 정리 API DTO 신설
- frontend/src/app/map/_components/PinPopup.tsx → 핀 말풍선(좌표/메모 편집·삭제). Phase 12: "가고 싶어요" 토글 버튼(REEL/WISH 노출, MEMORY 숨김) + 출처 뱃지(📹/✏️) + `?` 진행 다이어그램 모달
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/PendingNotificationSession.java → 챗봇 콜백 알림 세션. Phase 12: 챗봇 v2 ReelSavedSelectionSession과 통합 검토

### 설정
- backend/apps/wherewego-api/src/main/resources/db/migration/V011__add_invite_links_slug.sql → 직전 마이그레이션. Phase 12: V012 단일 트랜잭션 신규(pin_events 테이블 + want_count 컬럼 + WISH_CONVERTED 타입 + cleanup_snoozed_until)
- frontend/AGENTS.md → "This is NOT the Next.js you know" — 변경된 Next.js 버전이므로 `node_modules/next/dist/docs/`를 먼저 참조 필요
- .claude/config.json → projectTypes(java-spring=`./gradlew build`, node=`npm run build`), branchTypes, commitFormat

### 탐색 추가 (product-owner phase-requirements 누적)
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/MessageClassifier.java → 챗봇 메시지 타입 분류기. Phase 12: SINGLE_WANT_YES/NO, REEL_PLACE_SELECTION 타입 추가 + 우선순위 재정렬
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/PendingNotificationSession.java → 콜백 Push 실패 시 다음 발화 prepend 세션. EC-X6 처리 경로로 유지
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/group/GroupMemberRepository.java → 활성 그룹원 수 조회. 과반 계산(`floor(N/2)+1`) + WISH 알림 수신자 목록 확보
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/user/User.java → JPA Entity users. Phase 12: `cleanup_snoozed_until` 컬럼 추가 대상
- backend/apps/wherewego-api/src/main/resources/db/migration/V012__pin_experience_v2.sql → V012 신규 생성 대상 (단일 트랜잭션)
- frontend/src/app/pins/_components/CleanupBanner.tsx → 오래된 핀 정리 배너 (신규 생성, D-10)
- frontend/src/app/map/_components/MapFilter.tsx → 맵 필터 탭. Phase 12: 발견 드롭다운 서브 토글(D-13)
- frontend/src/app/map/_components/MapClient.tsx → 지도 클라이언트. `?reel_bundle=` + opacity 0.3 비강조 렌더링(D-14)

### 탐색 추가 (design-critic + patch 누적)
- backend/.../domain/notification/NotificationVisitWriter.java → V009 visit 알림이 REQUIRES_NEW 분리 트랜잭션을 위해 별도 writer를 둔 패턴. Phase 12에서는 fan-out 규모가 작아 미분리 결정
- backend/.../domain/notification/NotificationService.java::getDetail() → 기존은 NotificationPin 링크 기반 핀 로드. Phase 12에서 WISH_CONVERTED 타입은 wish_pin_id 기반 단건 Pin 로드 분기 추가 (Batch D 13-bis)
- backend/.../main/resources/db/migration/V009__add_visit_detected_notification_type.sql → 따라야 할 정확한 선례 (visit_pin_id + 부분 UNIQUE)

### 도메인 컨텍스트
- 매칭 레포: rnqhstmd/wherewego (context/pin/PROJECTS.md)
- 아키텍처: context/pin/architecture.md — `pins` 컬럼 정의 + V006 tag CHECK + Pin.validateInstagramUrl + PESSIMISTIC_WRITE + 페이지네이션 계약
- 설계 원본: docs/superpowers/specs/2026-05-26-pin-experience-v2-design.md (v2.1)
- 컨텍스트 추적: context/pin/phase-12-pin-experience-v2.md (D-1 ~ D-19 결정 매트릭스)
