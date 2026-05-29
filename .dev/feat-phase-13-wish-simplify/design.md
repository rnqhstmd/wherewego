# 설계서: Phase 13 — Pin Model 단순화 (WANT 폐기 · WISH 일원화)

> 작성일: 2026-05-28
> 선행 문서: prd.md, codemap.md
> 진행 방식: PRD → 설계 → 구현 (정석 절차)

## 1. 개요

Phase 12에서 도입한 WANT(관심 표현) 시스템을 전면 폐기하고, WISH를 "누군가 가고 싶다고 표시한 곳"으로 단순화한다. 챗봇 선택 핀은 WISH 태그로 직접 저장한다.

| 축 | 영향 |
|----|------|
| 데이터 | V012 수정 — `pin_events`·`pins.want_count`·`notifications.wish_pin_id`·`WISH_CONVERTED` 제거. `cleanup_snoozed_until`·`idx_pins_cleanup` 유지 |
| 도메인 | `pin/want/*`·`PinEvent*` 패키지 삭제. `Pin`에서 want 메서드 제거. `fromSelection`에 tag 인자 |
| 챗봇 | 저장 모델 재정의 — `selectedIndices`(저장할 핀) → `wishIndices`(위시로 저장할 핀). **전체 저장 + 선택분만 WISH** |
| 알림 | `WISH_CONVERTED` 제거. `CHATBOT_PINS` 알림에 위시/발견 카운트 분리 |
| 프론트 | 하트 UI·펄스·토스트·관심 필터·진행 모달 제거. 마커 3단계 |

핵심 원칙: **챗봇 세션 상태머신(State enum·TTL·핸들러 골격·dedup·메모 broadcast)은 유지**하고, "어떤 핀을 어떤 태그로 저장하는가"의 의미만 바꾼다.

---

## 2. 핵심 설계 결정

### 2.1 챗봇 저장 모델 재정의 (가장 중요)

**현재(Phase 12) 동작 — 문제**: `ReelMemoWaitingHandler.saveAllSelected`는 `selectedIndices`에 포함된 핀만 저장하고, 미선택 핀은 `continue`로 건너뛴다 (저장 안 함). 선택 핀에는 `markWantOnInitialSave`로 본인 WANT 1표를 찍는다.

**Phase 13 동작 — 목표 (D-13-B3)**: 추출된 **모든** 핀을 저장하되, `wishIndices`에 속하면 `tag=WISH`, 나머지는 `tag=REEL`로 저장한다.

```
Snapshot 필드 변경:
  selectedIndices: Set<Integer>   →  wishIndices: Set<Integer>  (위시로 저장할 1-based 인덱스)
  wantOnSelected: boolean         →  (삭제 — wishIndices로 충분)
```

| 단계 | 사용자 입력 | wishIndices | 저장 결과 |
|------|------------|-------------|-----------|
| SINGLE | [위시로 저장] | {1} | 1번 WISH |
| SINGLE | [발견으로 저장] | {} | 1번 REEL |
| MULTI | "1,3" | {1,3} | 1·3 WISH / 2·4·5 REEL (전부 저장) |
| MULTI | [전부] | {1..N} | 전체 WISH |
| MULTI | [건너뛰기] | {} | 전체 REEL |
| BULK(31+) | (메모만) | {} | 전체 REEL |

저장 루프 (의사코드):
```
for i in 1..places.size():
    hit = places[i-1]
    tag = wishIndices.contains(i) ? WISH : REEL
    result = pinService.registerFromSelectionWithDedup(userId, groupId, hit, instagramUrl, tag)
    if result.alreadyExisted: alreadyExistedCount++; continue
    if memo != null: pin.applyAutoMemo(memo); save
    if tag == WISH: wishSaved++ else reelSaved++
```

WANT INSERT·과반 검사·WishConvertedEvent 발행 일체 없음.

### 2.2 챗봇 위시 직저장 — 도메인 변경

`Pin.fromSelection`은 현재 REEL 하드코딩. tag 인자를 추가한다:

```java
// Pin.java
public static Pin fromSelection(Long groupId, Long ownerUserId, PlaceSearchHit hit,
                                String instagramUrl, PinTag tag) {
    String normalizedUrl = validateInstagramUrl(instagramUrl);
    return new Pin(groupId, ownerUserId, hit.placeName(), hit.address(),
            toBigDecimal(hit.latitude()), toBigDecimal(hit.longitude()), normalizedUrl, tag);
}
```

`PinService.registerFromSelectionWithDedup`에 `PinTag tag` 인자 추가, `Pin.fromSelection(.., tag)` 호출. dedup된 기존 핀은 태그 변경하지 않는다(이미 저장된 의사 존중 — `RegisterPinResult.alreadyExisted=true`로 그대로 반환). 생성 시점 태그 지정이므로 중간 상태(REEL→WISH) 노출 없음 (NFR-13-4 충족).

### 2.3 알림 본문 분리 (D-13-D2)

CHATBOT_PINS 알림 1건에 위시·발견 핀이 함께 링크된다. 알림 목록에서 "위시 N곳, 발견 M곳"을 표시하려면 `listRecent`가 연결 핀의 태그 분포를 알아야 한다.

**설계**: `NotificationItemResult`에 `wishCount`·`reelCount` 추가. `listRecent`에서 CHATBOT_PINS 알림의 연결 핀 전체를 배치 조회하여 태그별 집계. (MVP 규모: 릴스당 수~수십 핀, N+1 비용 허용 — §9 확인사항)

```
NotificationItemResult { ..., totalPinCount, wishCount, reelCount }
```
- MANUAL_PIN / VISIT_DETECTED: wishCount=reelCount=0 (프론트는 totalPinCount 사용)
- CHATBOT_PINS: 연결 핀 tag 집계. 프론트 NotificationItem이 "위시 N곳, 발견 M곳" 렌더

대안(단순): 알림은 "N곳 저장"으로 두고 챗봇 카톡 응답에서만 분리. 그러나 D-13-D2가 인앱 분리를 명시하므로 위 설계 채택. 구현 난도가 높으면 §9에서 재협의.

### 2.4 마이그레이션 (D-13-5, D-13-6)

V012가 운영 미반영·로컬 도커 PG만 적용 상태이므로 **V012 SQL 자체를 수정**한다 (V013 신설 아님). 로컬은 도커 볼륨 재생성으로 깨끗하게 재적용.

수정 후 V012가 남기는 것: `idx_pins_cleanup`(정리 기능), `users.cleanup_snoozed_until`(snooze). 제거: pin_events 전체, want_count, wish_pin_id, WISH_CONVERTED CHECK·인덱스, idx_pins_group_want_count.

---

## 3. 백엔드 변경 명세

### 3.1 삭제 (codemap §1)
`pin/want/` 패키지 4개 파일, `PinEvent`·`PinEventAction`·`PinEventRepository`(+impl/jpa), `WishConvertedNotificationListener`, `WantToggleIT`.

### 3.2 `Pin.java`
- `want_count` 필드·`applyWantDelta`·`transitionToWishIfMajority` 삭제
- `fromSelection`에 `PinTag tag` 인자 추가 (§2.2)

### 3.3 `PinService.java`
- `PinEventRepository` 필드 삭제, `SORT_WANT_COUNT`·`normalizeSort` want 분기 삭제
- `toSummaries(List<Pin>)`: `viewerId`·`findMyWantPinIds` 제거 → 닉네임만 주입하는 단순 변환
- `registerFromSelectionWithDedup(.., PinTag tag)`: 시그니처 확장
- `listGroupPins`/`listGroupPinsPaged`: `sort`/`interestOnly` 파라미터·분기 제거. 시그니처를 `(userId, groupId, tagFilter[, page, size])`로 축소. 기존 호출자(Controller) 동반 수정

### 3.4 `PinSummary.java` / `PinV1Dto.java`
- `wantCount`·`myWant` 필드 삭제, `from` 팩토리 단일화
- `WantToggleResponse`·`WantStatusResponse` record 삭제, `PinSummaryResponse`에서 두 필드 삭제

### 3.5 `PinRepository`(+impl/jpa)
- want 정렬·관심 필터 메서드 4개 삭제
- `findCleanupCandidates`/`countCleanupCandidates` 쿼리에서 `want_count = 0` 절 제거 (D-13-7)

### 3.6 `PinV1Controller.java` / `PinV1ApiSpec.java`
- `WantService` 의존·`toggleWant`·`getWantStatus` 삭제
- `listPins`에서 `sort`·`interest` 파라미터 제거, `listGroupPins(userId, groupId, tagFilter)` 호출

### 3.7 알림 (`NotificationService`, `NotificationType`, `Notification`, repo)
- `WISH_CONVERTED` enum·`createForWishConverted`·`getDetail` WISH 분기·`wish_pin_id` 참조 삭제
- `NotificationItemResult`에 `wishCount`·`reelCount` 추가, `listRecent` 태그 집계 (§2.3)
- `Notification.createForWishConverted` 팩토리·`getWishPinId` 삭제

### 3.8 `CleanupService` / `ErrorType`
- CleanupService 주석 정정 (조건은 쿼리에 위치)
- `PIN_WANT_FORBIDDEN_TAG`·`PIN_WANT_COUNT_NEGATIVE`·(want 전용)`PIN_SORT_PARAM_INVALID` 삭제

---

## 4. 챗봇 변경 명세

### 4.1 `ReelSavedSelectionSession.Snapshot`
- `selectedIndices` → `wishIndices`, `wantOnSelected` 삭제 (§2.1)

### 4.2 `MessageClassifier` / `MessageType`
- `SINGLE_WANT_YES_TEXT` = "위시로 저장", `SINGLE_WANT_NO_TEXT` = "발견으로 저장"
- enum 이름 `SINGLE_WANT_YES`/`SINGLE_WANT_NO`는 의미 유지(저장 태그 결정)이므로 보존하거나 `SINGLE_WISH`/`SINGLE_REEL`로 rename (구현 시 일괄). 보존이 변경폭 작음

### 4.3 `InstagramLinkHandler`
- SINGLE 안내: "...가고 싶은 곳이면 위시로, 일단 둘러보기면 발견으로 저장할게요." + `[위시로 저장]`/`[발견으로 저장]`
- MULTI 안내: "...✨ 가고 싶은 곳 번호를 콤마로 보내면 위시로 저장할게요. (나머지는 발견) 예: 1,3,5" + `[전부]`/`[건너뛰기]`
- BULK 안내: 현행 유지 ("전체 발견으로 저장... 메모 입력")
- 진입 시 `wishIndices=∅` 초기화

### 4.4 `ReelSingleWantHandler`
- 라벨 상수·QuickReply 텍스트 변경
- [위시로 저장] → `wishIndices={1}`, [발견으로 저장] → `wishIndices={}` 로 MEMO_WAITING 전이

### 4.5 `ReelMultiSelectionHandler`
- "전부" → `wishIndices={1..N}`, "건너뛰기" → `wishIndices={}`
- 콤마 파싱 성공 → `wishIndices=파싱결과` (나머지는 자동 REEL)
- 전이 prefix: "N곳을 위시로 저장할게요. 나머지는 발견으로 저장돼요. " 식

### 4.6 `ReelMemoWaitingHandler`
- `WantService` 의존 제거
- `saveAllSelected` → `saveAll`: **전체 places 순회**, `wishIndices` 포함 시 WISH 저장 (§2.1)
- `SaveResult`에 `wishSavedNames`/`reelSavedNames`(또는 wishCount/reelCount) 분리
- 완료 응답: "✨ 위시 N곳 / 📍 발견 M곳 저장했어요" + 각 목록

### 4.7 `ReelBulkSaveHandler`
- `wishIndices=∅` 유지 (전체 REEL). 안내문 현행 유지

### 4.8 `ChatbotWebhookService` / `ReelSelectionAutoSaveScheduler`
- `ensureSelectionFilled`: 자동 저장 시 `wishIndices`는 비운 채(전체 REEL 보수 저장) 전체 인덱스 저장. 즉 미응답 만료/새 URL 도착 시 보수적으로 발견 저장 (Phase 12 D-3/D-4 정신 유지)
- AutoSaveScheduler의 want 참조 정정 (구현 시 정독)

---

## 5. 프론트 변경 명세

### 5.1 삭제
`WishToast.tsx`, `TagProgressModal.tsx`, `markers.tsx`의 InterestGlyph/InterestBadgeIcon/`"interest"`, `globals.css`의 pin-pulse, `tokens.ts`의 pin-interest.

### 5.2 `markers.tsx`
- `PinKind` = `"reel"|"wish"|"memory"`
- `getMarkerVariant(tag)`: MEMORY→memory 1.0 / WISH→wish 1.2 / REEL→reel 1.0

### 5.3 `MapboxView.tsx`
- `pulsingPinId` prop·effect 삭제, `HEART_BADGE_SVG` 삭제
- `renderPinDotInto(el, tag)`: wantCount 인자·하트 합성 제거. variantKey = tag 단독
- `dimmedPinIds`(reel_bundle)는 유지

### 5.4 `MapClient.tsx`
- `handleWantToggle`·펄스 effect·`pulsingPinId`·`wishToastPin` state 삭제
- optimistic reducer `wantUpdate` 분기·액션 타입 삭제
- WishToast·toggleWantAction import·렌더 삭제
- `PinPopup`에 넘기던 `onWantToggle`/`pulse` 제거

### 5.5 `PinPopup.tsx`
- `HeartAction` 컴포넌트·want 상태·핸들러·props 삭제
- `bodyAction`(하트) 제거. 태그 수정 탭(REEL/WISH/MEMORY)은 유지 (D-13-C11)

### 5.6 `TagFilterButton.tsx` / `TagLegendButton.tsx`
- FilterKey 3키(MEMORY/WISH/REEL), OPTIONS에서 INTEREST 제거
- Legend STAGES 3단계 + 하트 섹션 제거 + 카피 정정 (D-13-F2):
  > "발견 = 둘러본 곳 / 위시 = 가고 싶다고 표시한 곳 / 추억 = 다녀온 곳" (카주얼 톤)
- MapClient의 `selectedFilters`/`visibleOptimisticPins` INTEREST 분기 제거 → REEL은 want 무관 전체

### 5.7 API 계층
- `types.ts`: `wantCount`/`myWant`/`WantToggleResponse`/`WantStatusResponse` 삭제
- `pin.ts`: `toggleWant`/`getWantStatus`/`ListPinsOptions.sort`/`interest` 삭제
- `actions.ts`: `toggleWantAction` 삭제

### 5.8 알림 프론트
- `lib/notifications/types.ts`: WISH_CONVERTED·wishConverted 삭제
- `NotificationItem.tsx`: WISH_CONVERTED 분기 삭제. CHATBOT_PINS는 wishCount/reelCount로 "위시 N곳, 발견 M곳" 렌더 (§2.3)
- `PinCard.tsx`: want 표시 있으면 제거 (구현 시 정독)

---

## 6. 구현 순서

1. **마이그레이션**: V012 수정 → 로컬 도커 PG 볼륨 재생성 → Flyway 적용 확인
2. **백엔드 도메인 삭제**: pin/want, PinEvent*, WishConverted* (컴파일 깨짐 시작)
3. **백엔드 수정**: Pin·PinService·PinSummary·PinV1Dto·PinRepository·Controller·ApiSpec·ErrorType
4. **알림**: NotificationType·NotificationService·Notification·repo (WISH 제거 + CHATBOT 집계)
5. **챗봇**: Snapshot·Classifier·핸들러 5종·WebhookService·Scheduler (wishIndices 모델)
6. **백엔드 컴파일+테스트** 통과 확인
7. **프론트 삭제**: WishToast·TagProgressModal·markers interest
8. **프론트 수정**: MapboxView·MapClient·PinPopup·필터·범례·API·알림
9. **프론트 tsc+lint+vitest** 통과
10. **수동 검증**: 챗봇 위시/발견 분리 저장 + 알림 본문 + 지도 마커 3단계

## 7. 검증 계획

| 대상 | 방법 |
|------|------|
| 마이그레이션 | 도커 PG 재생성 후 앱 기동 → Flyway 적용 로그 |
| 백엔드 | `./gradlew compileJava test` (WantToggleIT 삭제 후) |
| 챗봇 흐름 | SINGLE/MULTI/BULK 시나리오별 태그 저장 검증 (단위/통합) |
| 알림 | CHATBOT_PINS 위시·발견 혼합 저장 → listRecent 카운트 |
| 프론트 | `tsc --noEmit`, lint, vitest. 마커 3단계 수동 확인 |

## 8. 변경 규모 추정

- 백엔드: 삭제 ~11파일, 수정 ~15파일
- 프론트: 삭제 2파일, 수정 ~12파일
- 마이그레이션: V012 1파일

## 9. 확정된 결정 (사용자 승인 2026-05-28)

1. **알림 분리 표시** (§2.3): **인앱 알림 목록에 "위시 N곳, 발견 M곳" 분리 표시 채택.** `listRecent`가 CHATBOT_PINS 연결 핀 tag를 집계하여 `NotificationItemResult`에 `wishCount`/`reelCount`를 채운다. MVP 규모이므로 N+1 비용 허용.

2. **MessageType enum 이름**: `SINGLE_WANT_YES/NO` **그대로 보존** (의미만 "위시/발견 저장"으로 재해석, 변경폭 최소화).

3. **dev-seed-partner.sql / dev-unseed-partner.sql**: **삭제.**

4. **기존 WISH 핀**: 로컬 DB의 기존 WISH 핀은 **그대로 WISH 유지** (별도 마이그레이션 처리 없음). V012 수정 후 로컬 도커 PG는 볼륨 재생성으로 재적용하며, 로컬 데이터는 테스트용이라 소실 무방.
