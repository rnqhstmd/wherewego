# Codemap: Phase 13 — WANT 시스템 인벤토리

> 작성일: 2026-05-28
> 목적: 삭제·수정 대상 심볼의 정확한 위치 매핑. design.md의 변경 명세 근거.
> 조사 범위: grep 78개 파일 + 핵심 31개 파일 정독.

## 1. 백엔드 — 전체 삭제 대상 (파일 단위)

| 파일 | 내용 | 처리 |
|------|------|------|
| `domain/pin/want/WantService.java` | toggle / getStatus / markWantOnInitialSave | 삭제 |
| `domain/pin/want/WantToggleResult.java` | record (tag, wantCount, myWant, wishConverted) | 삭제 |
| `domain/pin/want/WantStatusResult.java` | record (wantCount, myWant) | 삭제 |
| `domain/pin/want/WishConvertedEvent.java` | record (groupId, pinId, triggerUserId, placeName) | 삭제 |
| `domain/pin/PinEvent.java` | JPA Entity | 삭제 |
| `domain/pin/PinEventAction.java` | enum { WANT } | 삭제 |
| `domain/pin/PinEventRepository.java` | 포트 인터페이스 | 삭제 |
| `infrastructure/pin/PinEventRepositoryImpl.java` | 어댑터 | 삭제 |
| `infrastructure/pin/PinEventJpaRepository.java` | Spring Data | 삭제 |
| `domain/notification/WishConvertedNotificationListener.java` | @TransactionalEventListener(AFTER_COMMIT) | 삭제 |
| `test/.../domain/pin/want/WantToggleIT.java` | 통합 테스트 | 삭제 |
| `backend/scripts/dev-seed-partner.sql` | 2인 그룹 시뮬레이션 시드 (WANT 테스트용) | 삭제(또는 보존 — 그룹 멤버십 테스트엔 유용, design에서 판단) |
| `backend/scripts/dev-unseed-partner.sql` | 위 unseed | 동상 |

## 2. 백엔드 — 부분 수정 대상

### `domain/pin/Pin.java`
- L86-92 `want_count` 필드 → 삭제
- L262-275 `applyWantDelta(int)` → 삭제
- L277-299 `transitionToWishIfMajority(int)` → 삭제
- L136-148 `fromSelection(...)` → **tag 인자 추가** (WISH 직저장용). 현재 REEL 하드코딩
- L118-130 `autoFromInstagram(...)` → 유지 (REEL 기본)

### `domain/pin/PinService.java`
- L30 `PinEventRepository` 필드 → 삭제
- L24-27 `SORT_CREATED_AT`/`SORT_WANT_COUNT` → `SORT_WANT_COUNT` 삭제
- L35-43 `toSummary(Pin)` → 유지 (단건, want 무관)
- L51-74 `toSummaries(List<Pin>, Long viewerId)` → **viewerId·findMyWantPinIds 제거**, 단순 변환으로
- L155-175 `registerFromSelectionWithDedup(...)` → **PinTag 인자 추가**, `Pin.fromSelection(.., tag)` 호출
- L215-237 `listGroupPins(.., sort, interestOnly)` → sort/interestOnly 분기 제거, 단순 created_at
- L256-287 `listGroupPinsPaged(.., sort, interestOnly)` → 동상
- L302-308 `normalizeSort(...)` → want_count 분기 제거 (또는 메서드 삭제)

### `domain/pin/PinSummary.java`
- L30-31 `wantCount`, `myWant` 필드 → 삭제
- L37-59 `from(.., wantCount, myWant)` 팩토리 → 시그니처 축소
- L65-67 하위호환 `from(pin, n1, n2)` → 단일 팩토리로 통합

### `domain/pin/PinRepository.java` (포트)
- L99-103 `findActiveByGroupIdSortedByWantCount` → 삭제
- L105-109 `findActiveByGroupIdAndTagSortedByWantCount` → 삭제
- L111-115 `findActiveByGroupIdInterestOnly` → 삭제
- L117-121 `countActiveByGroupIdInterestOnly` → 삭제
- L79-89 `findCleanupCandidates`/`countCleanupCandidates` → 유지하되 구현에서 want_count 조건 제거
- `infrastructure/pin/PinRepositoryImpl.java` + `PinJpaRepository.java` → 위 메서드 구현/쿼리 삭제 + cleanup 쿼리에서 `want_count=0` 절 제거

### `interfaces/api/pin/PinV1Controller.java`
- L10, L42 `WantService` import/필드 → 삭제
- L118-141 `toggleWant` / `getWantStatus` 엔드포인트 → 삭제
- L61-110 `listPins` → `sort`/`interest` 파라미터 제거, `listGroupPins(userId, groupId, tagFilter)` 호출로 축소

### `interfaces/api/pin/PinV1ApiSpec.java`
- WANT 엔드포인트 스펙(toggleWant/getWantStatus) + sort/interest 파라미터 → 삭제

### `interfaces/api/pin/PinV1Dto.java`
- L10-11 want import → 삭제
- L38-39 `PinSummaryResponse.wantCount`/`myWant` → 삭제 (+ from 매핑 L59-60)
- L65-82 `WantToggleResponse` record → 삭제
- L84-97 `WantStatusResponse` record → 삭제

### `domain/notification/NotificationType.java`
- `WISH_CONVERTED` → 삭제 (MANUAL_PIN, CHATBOT_PINS, VISIT_DETECTED 만 유지)

### `domain/notification/NotificationService.java`
- L120-164 `createForWishConverted(...)` → 삭제
- L252-278 `getDetail` 의 `WISH_CONVERTED` 분기 → 삭제
- L63-71 `createForChatbatBatch` → **태그 분리 표시를 위한 집계 추가** (design §알림 참조)
- L213-227 `listRecent` 의 `NotificationItemResult` 생성 → CHATBOT_PINS 태그 분포 추가 검토

### `domain/notification/Notification.java` / `NotificationRepository.java` / `NotificationJpaRepository.java`
- `createForWishConverted` 팩토리, `wish_pin_id` getter, `existsBy...WishPinId` 쿼리 → 삭제
- `getWishPinId()` 등 wish_pin_id 참조 제거

### `domain/pin/cleanup/CleanupService.java`
- L23 주석 `want_count=0` → 제거 (조건은 PinRepository 쿼리에 있음, 주석만 정정)

### `support/error/ErrorType.java`
- `PIN_WANT_FORBIDDEN_TAG`, `PIN_WANT_COUNT_NEGATIVE`, `PIN_SORT_PARAM_INVALID`(want 전용이면) → 삭제

### 챗봇 (도메인 유지, 저장 태그만 변경)
| 파일 | 변경 |
|------|------|
| `domain/chatbot/ReelSavedSelectionSession.java` | `Snapshot.wantOnSelected` → `saveAsWish` 의미로 rename (record 필드명) |
| `domain/chatbot/MessageClassifier.java` | L36-37 `SINGLE_WANT_NO_TEXT="발견으로만 저장"` → `"발견으로 저장"`. `SINGLE_WANT_YES_TEXT="가고 싶어요"` → `"위시로 저장"` |
| `domain/chatbot/handler/ReelSingleWantHandler.java` | L34-35 라벨 상수, L99-102 QuickReply 라벨 변경. `wantYes` → `saveAsWish` |
| `domain/chatbot/handler/InstagramLinkHandler.java` | L189-196 SINGLE 안내+버튼, L210-221 MULTI 안내문구 "선택=위시/나머지=발견" 추가 |
| `domain/chatbot/handler/ReelMultiSelectionHandler.java` | L82-99 전부/건너뛰기 분기의 `wantOnSelected` → `saveAsWish` 의미. 안내 prefix 정정 |
| `domain/chatbot/handler/ReelMemoWaitingHandler.java` | L174-226 `saveAllSelected`: WantService 의존 제거. 선택 핀=WISH 저장, 미선택=REEL. `SaveResult`에 wishCount/reelCount 분리. L234-256 응답 "위시 N곳/발견 M곳" |
| `domain/chatbot/handler/ReelBulkSaveHandler.java` | 변경 거의 없음 (전체 REEL 유지). saveAsWish=false |
| `domain/chatbot/ChatbotWebhookService.java` | L177-181 auto-save 시 동일 흐름. `ensureSelectionFilled` 의 wantOnSelected 참조 정정 |
| `domain/chatbot/ReelSelectionAutoSaveScheduler.java` | forceSaveOnExpire 경로의 want 참조 정정 (미확인 — design에서 확인) |

## 3. 프론트 — 삭제 대상

| 파일 | 처리 |
|------|------|
| `app/map/_components/WishToast.tsx` | 삭제 |
| `app/map/_components/TagProgressModal.tsx` | 삭제 |

## 4. 프론트 — 부분 수정

### `app/map/_components/PinPopup.tsx`
- L47-57 `onWantToggle`/`pulse` props → 삭제
- L99-103, L222-233 want 상태/핸들러 → 삭제
- L404-419 `bodyHeart`/`viewFooter` want 분기 → 삭제
- L585-694 `HeartAction` 컴포넌트 → 삭제
- 태그 수정 탭(L318-345)은 유지 (직접 위시 변경 — D-13-C11)

### `app/map/MapClient.tsx`
- WantToggleResponse import, L140-153 `wantUpdate` optimistic 액션 → 삭제
- L231-242 reducer `wantUpdate` 분기 → 삭제
- L302-314 `pulsingPinId`, `wishToastPin` state → 삭제
- L536-620 `handleWantToggle` + 펄스 effect → 삭제
- WishToast import/렌더 → 삭제
- `toggleWantAction` import → 삭제
- TagFilterButton `ALL_FILTER_KEYS`/`FilterKey` INTEREST 의존 정정 (§아래)

### `app/map/_components/MapboxView.tsx`
- L67-74 `dimmedPinIds`는 유지(reel_bundle), `pulsingPinId` prop → 삭제
- L218 `HEART_BADGE_SVG` → 삭제
- L220-278 `renderPinDotInto`: wantCount 인자·하트 뱃지 합성 제거
- L399-421 `pulsingPinId` effect → 삭제
- L502-507 wantCount 룩업/variantKey → tag 단독으로 축소
- `getMarkerVariant(tag, wantCount)` → `getMarkerVariant(tag)` 

### `lib/pin/markers.tsx`
- L16 `PinKind`에서 `"interest"` 제거
- L30-35 `PIN_COLORS.interest` 제거
- L61-64 `getInterestSvgString` 삭제
- L111-178 `InterestGlyph`, `InterestBadgeIcon` 삭제
- L259-276 `getMarkerVariant(tag, _wantCount)` → `(tag)` 시그니처 축소

### `app/map/_components/TagFilterButton.tsx`
- L23 `FilterKey`에서 `"INTEREST"` 제거 → `"MEMORY"|"WISH"|"REEL"`
- L25-30 `ALL_FILTER_KEYS` 3키
- L40-65 `OPTIONS`에서 INTEREST 항목 + InterestBadgeIcon import 제거

### `app/map/_components/TagLegendButton.tsx`
- L28-53 `STAGES`에서 "관심" 단계 + InterestBadgeIcon 제거 → 3단계
- L206-237 하트 의미 섹션(LegendRow/HeartIcon) 제거
- L145-155 카피 정정 (D-13-F2)

### `app/map/actions.ts`
- L6 `toggleWant` import, L177-201 `toggleWantAction` + `ToggleWantActionResult` → 삭제
- WantToggleResponse import → 삭제

### `lib/api/pin.ts`
- L8-9 want import, L21-35 `ListPinsOptions.sort`/`interest` → 삭제
- L60-61 params sort/interest → 삭제
- L125-160 `toggleWant`, `getWantStatus` → 삭제

### `lib/api/types.ts`
- L41-50 `PinSummaryResponse.wantCount`/`myWant` → 삭제
- L52-76 `WantToggleResponse`, `WantStatusResponse` → 삭제

### `app/map/_components/notifications/NotificationItem.tsx`
- WISH_CONVERTED 분기 → 삭제 또는 CHATBOT_PINS "위시 N곳/발견 M곳" 표시로 정정 (design §알림)

### `app/pins/_components/PinCard.tsx`
- want 관련 표시(있으면) → 삭제 (정독 필요 — design에서 확인)

### `lib/notifications/types.ts`
- WISH_CONVERTED 타입 + wishConverted 참조 → 삭제

### `app/globals.css`
- `@keyframes pin-pulse` + `.pin-pulse-once` → 삭제

### `lib/design/tokens.ts`
- `pin-interest` / `colors.pinInterest` (#7B68EE 또는 라벤더) → 삭제

## 5. 마이그레이션

### `db/migration/V012__pin_experience_v2.sql`
- §1 `pin_events` CREATE TABLE + 인덱스 → 삭제
- §2 `pins.want_count` ALTER + `idx_pins_group_want_count` → 삭제. `idx_pins_cleanup`는 유지(정리 기능)
- §3 `notifications` WISH_CONVERTED CHECK 확장 + `wish_pin_id` 컬럼 + `uq_notifications_wish_converted` → 삭제. CHECK는 `('MANUAL_PIN','CHATBOT_PINS','VISIT_DETECTED')`로
- §4 `users.cleanup_snoozed_until` → 유지

## 6. 미확인 — 구현 전 정독 필요

- `infrastructure/pin/PinRepositoryImpl.java`, `PinJpaRepository.java` (want 쿼리 JPQL/네이티브)
- `infrastructure/notification/NotificationJpaRepository.java` (wish_pin_id 쿼리)
- `domain/notification/Notification.java` (createForWishConverted 팩토리, wish_pin_id 필드)
- `domain/chatbot/ReelSelectionAutoSaveScheduler.java` (forceSaveOnExpire want 참조)
- `app/pins/_components/PinCard.tsx` (want 표시 여부)
- `app/map/_components/notifications/NotificationItem.tsx`, `NotificationPinList.tsx` (WISH_CONVERTED 렌더)
- `config/cache/CacheConfig.java` (REEL_SELECTION 캐시 — 유지)
