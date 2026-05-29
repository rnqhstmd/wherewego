# notification 구현 추적

> Phase 8 PRD 요구사항별 구현 상태를 추적합니다.
> **옵션 B 다운그레이드(2026-05-21)** — SSE 인프라 제거, mount/visibility/focus fetch 기반으로 전환. 일부 SSE 의존 요구사항은 보류 처리.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현 (보류 사유 포함)

## 기능 요구사항

| ID | 요구사항 | 상태 | PR | 비고 |
|----|----------|------|-----|------|
| FR-1 | 웹 직접 등록 트리거 (PinV1Controller.createPin → createForManualPin) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |
| FR-2 | 챗봇 3경로 트리거 (InstagramLinkHandler 3분기 + PlaceSelectionHandler), 4경로 자동 커버 | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |
| FR-3 | findOtherActiveMemberIds로 등록자 제외 수신자 fan-out | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |
| FR-4 | autoRegistered 0건 시 알림 미생성 (NotificationService 자체 + 호출자 가드) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |
| FR-5 | GET /api/v1/notifications/stream SSE 엔드포인트 + JWT 인증 | ⬜ | — | 옵션 B 다운그레이드로 SSE 제거 (2026-05-21). 사용자 100명+ 시점 재평가 |
| FR-6 | 30초 heartbeat (NotificationHeartbeatScheduler @Scheduled) | ⬜ | — | 옵션 B 다운그레이드로 SSE 제거 |
| FR-7 | SSE 연결 중 push, 미연결 시 DB 저장만 + REST 조회 fallback | ⬜ | — | 옵션 B에서는 REST 조회만 사용. push 부분 보류 |
| FR-8 | 클라이언트 재연결 정책 (지수 백오프 2→30s, 최대 5회, failed 수렴) | ⬜ | — | 옵션 B 다운그레이드로 SSE 제거 |
| FR-9 | 다중 탭 SSE 독립 연결 (CopyOnWriteArrayList) | ⬜ | — | 옵션 B에서는 각 탭이 독립 fetch라 본질적 N/A |
| FR-10 | GET /notifications 최신순 ≤50건 + unreadCount | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | mount/visibility/focus 트리거 |
| FR-11 | POST /read-all 전체 읽음 처리 (bulk UPDATE + clearAutomatically) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |
| FR-12 | GET /notifications/{id} 단건 상세 (핀 목록 + deleted) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |
| FR-13 | 모바일 우상단 [벨][프로필] 가로 배치 | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |
| FR-14 | 미읽음 시 빨간 점 8px + read-all 후 소멸 | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | mount/visibility fetch 결과로 갱신 |
| FR-15 | 새 알림 말풍선 5초 자동 + 외부 탭 닫힘, 알림당 1회 (shownToastIds) | ⬜ | — | **옵션 B 변형 유지** — visibilitychange/focus 트리거 fetch에서 직전 max id 초과한 신규 알림 감지 시 1회 노출로 재구현 필요 |
| FR-16 | 벨 클릭 → 패널 + read-all 호출 | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |
| FR-17 | 패널 열림 중 새 알림 → 상단 추가 + read-all 재호출 | ⬜ | — | 옵션 B 다운그레이드로 제거. 패널 외부 이벤트(visibilitychange/focus) 시에만 갱신 |
| FR-18 | 핀 클릭 → 패널 닫힘 + flyTo + PinPopup | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |
| FR-19 | 데스크탑 벨 슬롯 + 동일 UX | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |
| FR-20 | 빈 상태 안내 ("아직 알림이 없어요") | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |

## 비즈니스 규칙

| ID | 규칙 | 상태 | PR | 비고 |
|----|------|------|-----|------|
| BR-1 | 등록자 본인 알림 미생성 (JPQL `userId <> :excludeUserId`) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |
| BR-2 | 영구 보관 (만료 정책 없음) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |
| BR-3 | 핀 트랜잭션과 알림 트랜잭션 분리 (호출자 try-catch + NotificationService @Transactional) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |
| BR-4 | 삭제 핀 알림 레코드 유지 + 상세에서 deleted=true (좌표 null) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |
| BR-5 | autoRegistered 0건 / alreadySaved만 있는 경우 skip | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |
| BR-6 | SseEmitter 5분 타임아웃 | ⬜ | — | 옵션 B 다운그레이드로 SSE 제거 |
| BR-7 | 알림 목록 최대 50건 cap | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) | |

## 알림 UX 개선 (2026-05-23, 커밋 6426914)

| 항목 | 상태 | 상세 |
|------|------|------|
| 알림 목록 actor 레이블 통일 | ✅ | `NotificationItem.tsx` — `currentUserId` prop 제거, 항상 `{registeredByNickname}님이 장소를 저장했어요.` 표시. "나"→본인 이름 형식으로 통일 |
| 알림 상세 버튼 텍스트 | ✅ | `NotificationPinList.tsx` — "출처 ↗" → "릴스 보기 ↗" 텍스트 변경 |

## Phase 10 완료 (2026-05-24)

| 항목 | 상태 | PR | 비고 |
|------|------|-----|------|
| `VISIT_DETECTED` 신규 알림 유형 (WISH/REEL→MEMORY 자동 전환 시 발송) | ✅ | [#57](https://github.com/rnqhstmd/wherewego/pull/57) | V009 마이그레이션 (`visit_pin_id` 컬럼 + 부분 UNIQUE 인덱스 + ON DELETE RESTRICT) |
| `NotificationVisitWriter` (`@Transactional(REQUIRES_NEW)`) + 호출자 catch race-free 차단 | ✅ | [#57](https://github.com/rnqhstmd/wherewego/pull/57) | Spring self-invocation 회피, `DataIntegrityViolationException` 호출자 catch |
| `NotificationService.createForVisitDetected` 본인 포함 fan-out | ✅ | [#57](https://github.com/rnqhstmd/wherewego/pull/57) | Phase 11 "우리 기록" 도입 전 과도기 용도. 본인 알림함에도 방문 기록 노출 |
| 알림 상세 `pin.memo` 최신값 join (VISIT_DETECTED 한정) | ✅ | [#57](https://github.com/rnqhstmd/wherewego/pull/57) | `NotificationPinItemResult.memo` + `PinItem.memo` 필드 추가 |
| `NotificationItem` 본인/짝꿍 카피 분기 ("내가 다녀온 장소" / "{닉네임}님이 다녀온 장소") | ✅ | [#57](https://github.com/rnqhstmd/wherewego/pull/57) | `currentUserId` prop 추가 |
| `NotificationPinList` 메모 줄 표시 (VISIT_DETECTED 한정) + 동사 분기 ("다녀온" / "저장한") | ✅ | [#57](https://github.com/rnqhstmd/wherewego/pull/57) | `type` prop 추가 |

## Phase 10 추가 보강 (2026-05-24, 커밋 957761a — gemini-code-assist 봇 리뷰 반영)

| 항목 | 상태 | 상세 |
|------|------|------|
| `createForVisitDetected` 멤버십 사전 검증 | ✅ | `groupMemberRepository.findActiveByGroupIdAndUserId(groupId, registeredBy)` → 비활성 멤버면 `log.warn` 후 early return. 비활성 그룹 멤버가 알림 발사 시도 시 의도치 않은 fan-out 차단 |
| Fan-out 루프 `RuntimeException` 격리 | ✅ | `NotificationVisitWriter.writeOne` 호출 try-catch에 `DataIntegrityViolationException` (duplicate) 분기 외에 `RuntimeException` catch 추가. 한 receiver의 예외가 나머지 receiver fan-out을 막지 않음 |
| `MapClient.handleVisitConfirm` 에러 로그 단순화 | ✅ | `console.error` 페이로드를 `{groupId, pinId, code, message}` → `result.code`만으로 축소. 식별자 노출 최소화 |
| `accuracy > 50m` 시 `firstEnterAt` clear (gemini 권고) | ⬜ | **의도적 미반영**. PRD BR-VD-3 "타이머에 영향을 주지 않는다"를 누적 보존으로 해석한 self-check Q4 결정 존중. 부작용(연속 불량 GPS 후 즉시 detect)은 trust-ledger 후속 관찰 항목 |

## Phase 12 완료 (2026-05-27) — WISH_CONVERTED 알림

| 항목 | 상태 | PR | 비고 |
|------|------|-----|------|
| `NotificationType.WISH_CONVERTED` enum + V012 CHECK 확장 (`MANUAL_PIN`, `CHATBOT_PINS`, `VISIT_DETECTED`, `WISH_CONVERTED`) | ✅ | [#76](https://github.com/rnqhstmd/wherewego/pull/76) | V012 단일 마이그레이션 + `wish_pin_id` 컬럼 + 부분 UNIQUE 인덱스 `uq_notifications_wish_converted` |
| `WishConvertedEvent(pinId, groupId, triggerUserId)` AFTER_COMMIT 리스너 → `NotificationService.createForWishConverted` | ✅ | [#76](https://github.com/rnqhstmd/wherewego/pull/76) | `WantService.toggle` 과반 도달 시 publish, `WishConvertedNotificationListener` AFTER_COMMIT 격리 |
| 본문 (간결형, D-16): `"🌟 '{placeName}'이 위시로 올라갔어요! 둘 다 가고 싶어해요"` | ✅ | [#76](https://github.com/rnqhstmd/wherewego/pull/76) | 채널 = 인앱 알림만 (FCM/카카오 푸시 미사용) |
| 발송 범위: 본인 제외 그룹원 N-1명 (`findOtherActiveMemberIds`) | ✅ | [#76](https://github.com/rnqhstmd/wherewego/pull/76) | 멱등: `existsByGroupIdAndReceiverIdAndRegisteredByAndWishPinId` 사전 조회로 동일 핀 1회만 INSERT (PR #76 Gemini #3 후속 보강) |

상세: [pin/phase-12-pin-experience-v2.md](../pin/phase-12-pin-experience-v2.md) §WANT 시스템 / §알림

## 후속 작업 (Trust Ledger 기록, 미반영)

- ⬜ `registeredBy` 필드 응답에서 제거 (FE 미사용, 최소 공개 원칙)
- ⬜ 조사 처리 자동화 (NotificationToast/Item — 받침 유무 을/를)
- ⬜ `notification_pins.pin_id` ON DELETE 정책 ADR 기록 (soft delete 영구 유지 가정)
- ⬜ `NotificationPanel.loadDetail` 실패 시 에러 UI 표시
- ⬜ `NotificationPanel` 빠른 연속 클릭 시 detail fetch race condition (AbortController)
- ⬜ Phase 11 도입 시 `createForVisitDetected`의 본인 포함 fan-out을 "우리 기록" 화면 기반으로 재검토 (현재는 과도기 정책)

## 옵션 B 다운그레이드 코드 반영 (2026-05-21)

PR #40 다운그레이드 후속 커밋으로 반영 완료:

**Backend 제거** ✅
- `NotificationSseRegistry`, `NotificationHeartbeatScheduler`, `NotificationSsePushListener`, `NotificationCreatedEvent` 삭제
- `NotificationSseRegistryTest` 삭제
- `NotificationV1Controller.stream()` + `NotificationV1ApiSpec.stream()` 시그니처 제거
- `NotificationService`에서 `ApplicationEventPublisher` 의존성 제거 (fan-out 후 이벤트 발행 제거)
- `NotificationV1ControllerIntegrationTest`에서 SSE 테스트 2건 제거
- `application.yml`의 `spring.task.scheduling.pool.size` 는 `ThresholdMonitorScheduler`도 사용하므로 유지

**Frontend 제거** ✅
- BFF SSE 라우트 `frontend/src/app/api/v1/notifications/stream/` 디렉토리 삭제
- `frontend/src/lib/notifications/sseClient.ts` + `sseClient.test.ts` 삭제
- `NOTIFICATION_SSE_URL` 상수 제거
- `ConnectionState` 타입 제거, `NotificationStreamEvent` → `NotificationToastPayload`로 명칭 갱신

**Frontend 변형** ✅
- `useNotifications` 훅: mount + `visibilitychange`(hidden→visible) + window `focus` 이벤트에서 fetch 트리거
- 토스트 노출: 직전 `lastSeenMaxIdRef` 초과한 **최상위 신규 알림 1건만** 토스트 (FR-15 변형)
- `shownToastIdsRef`로 동일 알림 중복 노출 차단 유지 (AC-16)
- 패널 열림 중 신규 알림 감지 → 토스트 미노출 + `markAllRead` 재호출 (AC-17 유사 동작 유지)
- `NotificationBell`: `connectionState` prop 제거, 회색 점(연결 끊김) 시각 상태 제거
- `MapClient`: 벨 컴포넌트에 `connectionState` 전달 제거

**잔여**
- `docs/ops/phase-8-notifications.md` 옵션 B 반영 (의문점 1·2 항목 제거, V007 FK는 유지) — 별도 작업

## SSE 재도입 트리거 (보류)

다음 조건 도달 시 Phase 8 옵션 A(SSE 인프라)를 재도입 검토:

- 활성 사용자 100명+ 도달
- 실시간 알림 미수신에 대한 사용자 피드백 누적
- 또는 [노티 인프라 진화 로드맵](../../docs/ops/notification-scaling-roadmap.md) 단계 3(500~5,000명) 도달

재도입 시 참조: [Phase 8 SSE 인프라 아카이브](../../docs/architecture/notification-sse-archive.md) — 전체 구현 코드 + 결정 근거 + 의문점 + 재도입 체크리스트 보존
