# Phase 12 — Pin Experience v2 (WANT 시스템·챗봇 v2 재설계·오래된 핀 정리)

- 작성일: 2026-05-27
- 수정일: 2026-05-27
- 관련 레포: rnqhstmd/wherewego
- 상태: ⬜ 미시작 (설계 확정)
- 설계 원본: [docs/superpowers/specs/2026-05-26-pin-experience-v2-design.md](../../docs/superpowers/specs/2026-05-26-pin-experience-v2-design.md)

## 개요

Phase 7에서 도입한 REEL/WISH/MEMORY 태그가 의미적으로 겹치는 문제(REEL=발견, WISH=설렘이지만 양쪽 다 "가고 싶다" 뉘앙스)와, 챗봇으로 릴스 1개에서 N개 장소가 동시 저장될 때 "가장 가고 싶은 곳"을 구분 못 하는 문제를 해결한다. 동시에 커플 2명 그룹에서 좋아요 최대 2개로는 관심도 변별력이 부족한 점도 함께 정리한다.

**핵심 변화**:
1. **출처 / 상태 분리** — `tag`는 상태(REEL→WISH→MEMORY) / 출처 뱃지(📹 챗봇 / ✏️ 앱)는 `instagram_url IS NOT NULL`로 파생
2. **WANT 시스템 신설** — 그룹원이 "가고 싶어요" 누르면 `pin_events.WANT` 누적. `floor(N/2)+1` 명 이상 누르면 자동으로 WISH 전환 + 알림
3. **3단계 마커** — 발견(하늘색 `#7BB3E8`, 1.0배) → 관심 1표+(진보라 `#7B68EE`, 1.1배) → 위시(노랑 별 `#F4C842`, 1.2배 + 전환 펄스)
4. **챗봇 v2 재설계** — 카카오 토글 UX 한계로 "콤마 번호 직접 입력" 모델로 전환. 1라운드 완결
5. **오래된 핀 정리** — `tag=REEL + memo_source=AUTO + 30일+ + want_count=0` 일괄 정리 배너 + DB snooze

---

## 결정 매트릭스 (D-1 ~ D-19)

설계 확정 과정에서 닫힌 19개 정책. 상세 사유는 설계 원본 §15 참조.

### 챗봇
| ID | 항목 | 결정 |
|----|------|------|
| D-1 | BULK_SAVE 컷오프 | 31개+ |
| D-2 | 중복 번호 입력 안내 | 침묵 dedup |
| D-3 | SINGLE_WANT TTL 만료 | REEL만 저장 |
| D-4 | MULTI_SELECTING TTL 만료 | 전체 REEL |
| D-5 | MEMO 단계 "전부/건너뛰기" 텍스트 | 메모로 저장 |
| D-6 | 동시성 race | 그대로 (Caffeine + webhook 직렬화) |
| D-7 | SELECTION 중 룰렛/공유 입력 | 거부 + 세션 유지 |

### WANT / 알림
| ID | 항목 | 결정 |
|----|------|------|
| D-8 | pin_events P0 활성 액션 | WANT만 (VIEW/SHARE/ROULETTE는 해당 기능 PR에서) |
| D-9 | 관심도 정렬 공식 | `want_count` desc만 |
| D-16 | WISH 알림 본문 | 간결형 |
| D-19 | WANT 멱등 | 영구 UNIQUE `(pin_id, user_id) WHERE action='WANT'` |

### 정리 / UI
| ID | 항목 | 결정 |
|----|------|------|
| D-10 | 정리 노출 | `/pins` 하단 배너 |
| D-11 | snooze 저장 | DB (`users.cleanup_snoozed_until`) |
| D-12 | 정리 UX | 일괄 정리 |
| D-13 | 맵 필터 "발견" 서브 | 드롭다운 (모든 발견 / 관심 있는 발견) |
| D-14 | 릴스 번들 강조 | 비강조 opacity 0.3 |
| D-15 | 태그 진행 다이어그램 | 핀 카드/말풍선 ? 아이콘 |

### 인프라
| ID | 항목 | 결정 |
|----|------|------|
| D-17 | ROULETTE/SHARE enum | 해당 기능 PR에서 ALTER |
| D-18 | Flyway 단위 | V012 단일 트랜잭션 |

---

## 데이터 모델 변경 (V012)

```sql
-- V012__pin_experience_v2.sql

-- 1) pin_events: 액션 누적 (P0=WANT만)
CREATE TABLE pin_events (
    id         BIGSERIAL   PRIMARY KEY,
    pin_id     BIGINT      NOT NULL REFERENCES pins(id),
    user_id    BIGINT      NOT NULL REFERENCES users(id),
    group_id   BIGINT      NOT NULL REFERENCES groups(id),
    action     VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_pin_events_action CHECK (action IN ('WANT'))
);

-- D-19 영구 멱등
CREATE UNIQUE INDEX uq_pin_events_pin_user_want
    ON pin_events (pin_id, user_id) WHERE action = 'WANT';
CREATE INDEX idx_pin_events_pin_id ON pin_events(pin_id);
CREATE INDEX idx_pin_events_group_created ON pin_events(group_id, created_at DESC);

-- 2) pins.want_count 캐시
ALTER TABLE pins ADD COLUMN want_count INT NOT NULL DEFAULT 0;
CREATE INDEX idx_pins_cleanup ON pins (group_id, created_at)
    WHERE tag = 'REEL' AND memo_source = 'AUTO' AND deleted_at IS NULL;

-- 3) notifications: WISH_CONVERTED 추가
ALTER TABLE notifications DROP CONSTRAINT chk_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type
    CHECK (type IN ('MANUAL_PIN', 'CHATBOT_PINS', 'VISIT_DETECTED', 'WISH_CONVERTED'));

-- 4) snooze 저장
ALTER TABLE users ADD COLUMN cleanup_snoozed_until TIMESTAMPTZ;
```

---

## 마커 3단계 시각화

| 상태 | 색·모양 | 크기 | 비고 |
|------|---------|------|------|
| 발견 (WANT 0표) | 하늘색 `#7BB3E8` 동그라미 | 1.0배 | 기존 REEL과 동일 |
| 관심 (WANT 1표+, 과반 미달) | 진보라 `#7B68EE` 동그라미 | 1.1배 | **신규** — 따뜻한/차가운 톤 점프 회피하고 차분한 강조 |
| 위시 (과반 달성) | 노랑 `#F4C842` 별 | 1.2배 | 기존 WISH 모양. **전환 직후 0.5초 펄스 1회 신규** |
| 추억 | 핑크 `#FFB3C6` 하트 | 1.0배 | 기존 MEMORY 동일 |

**보조 신호**: 크기 transition + WISH 전환 펄스. 흰 외곽선·그림자 강화 미적용(간결성 우선).

**MEMORY+WANT**: WANT 버튼 UI 자체 미노출 ("이미 다녀온 곳" 정책).

---

## WANT 시스템

### 전환 조건

- `floor(N/2) + 1` 명 이상 WANT (본인 1표 포함)
- 2인 그룹: 둘 다 (1명 = 50%, 과반 미달)
- 3인 그룹: 2명 이상
- **전환 후 역전환 없음** (WANT 취소해도 WISH 유지)

### 토글 트랜잭션

```
POST /api/v1/groups/{groupId}/pins/{pinId}/want

1. SELECT FOR UPDATE on pins
2. EXISTS (pin_id, user_id, action='WANT')?
   ├─ 있음 → DELETE + want_count -= 1
   └─ 없음 → INSERT + want_count += 1
3. UPDATE pins SET want_count = ?
4. (이번에 INSERT 이고) want_count >= floor(N/2)+1 AND tag=REEL?
   → UPDATE pins SET tag = WISH
   → publish WishConvertedEvent (AFTER_COMMIT)
5. 응답: { tag, wantCount, myWant, wishConverted: boolean }
```

### 알림 (D-16 간결형)

```
@TransactionalEventListener(AFTER_COMMIT)
onWishConverted(event):
  type = NotificationType.WISH_CONVERTED
  receiver_ids = findOtherActiveMemberIds(groupId, triggerUserId)
  body = "🌟 '{placeName}'이 위시로 올라갔어요! 둘 다 가고 싶어해요"
```

- 채널: 인앱 알림만 (FCM 미사용)
- 발송 범위: 본인 제외 그룹원 N-1명
- 멱등 보장: 동일 핀 WISH_CONVERTED 1회만 INSERT

---

## 챗봇 v2 재설계

### 결정 배경

v2 원안의 "장소 버튼 토글 + 완료" 모델은 **카카오 i 오픈빌더에서 구현 불가**:
- 카카오 버튼 액션 `message`는 누르는 즉시 발화로 자동 전송됨 → 입력창에 누적 불가
- 메시지 수정/삭제 API 없음 → 토글마다 새 카드가 채팅창에 누적 (6번 토글 = 카드 7개)
- 옛 카드의 버튼도 살아있어 위로 스크롤 후 클릭 시 state 꼬임

→ **콤마 번호 직접 입력 모델**로 전환. 1라운드 완결, 모든 카카오 제약 회피.

### 상태 머신

```
IDLE
 │  인스타 URL
 ▼
PROCESSING (Gemini, useCallback=true)
 │
 ├─ 0개 ─────────→ IDLE (오류 안내)
 ├─ 1개 ─────────→ SINGLE_WANT
 ├─ 2~30개 ──────→ MULTI_SELECTING
 └─ 31개+ ───────→ BULK_SAVE  (D-1)

SINGLE_WANT
 ├─ "가고 싶어요" QR ──→ MEMO_WAITING (want=true)
 ├─ "발견으로만 저장" QR → MEMO_WAITING (want=false)
 └─ TTL 3분 ──────────→ COMPLETE (REEL만, D-3)

MULTI_SELECTING
 ├─ "1,3,5" 콤마 숫자 ──→ MEMO_WAITING (selected indices)
 ├─ "전부" QR ─────────→ MEMO_WAITING (all)
 ├─ "건너뛰기" QR ─────→ MEMO_WAITING (none = 전체 REEL)
 ├─ 파싱 실패 ─────────→ MULTI_SELECTING (TTL 미리셋)
 └─ TTL 3분 ──────────→ COMPLETE (전체 REEL, D-4)

BULK_SAVE (31개+)
 ├─ 메모 텍스트 ───────→ COMPLETE (all REEL + memo)
 ├─ "건너뛰기" QR ─────→ COMPLETE
 └─ TTL 3분 ──────────→ COMPLETE

MEMO_WAITING
 ├─ 메모 텍스트 ───────→ COMPLETE
 ├─ "건너뛰기" QR ─────→ COMPLETE
 └─ TTL 3분 ──────────→ COMPLETE
```

### 콤마 파싱 규칙

```
1. split(",")
2. trim() 각 토큰
3. 빈 토큰 무시 (trailing/연속 콤마 허용)
4. ^\d+$ 정규식 (콤마 외 구분자 X)
5. 1 ≤ n ≤ 추출장소수 검증
6. LinkedHashSet으로 dedup (D-2: 침묵)
7. 한 토큰이라도 실패하면 전체 거부 + 재안내
```

허용: `1,3,5` / `1, 3, 5` / `1,2,3,` / `1,,3`
거부: `1 3 5` / `1-3` / `일,삼` / `1,2번` / `1,16`(N=15)

### 폐기 클래스

| 클래스 | 처리 |
|--------|------|
| `PendingInstagramSession` | `ReelSavedSelectionSession` 으로 통합 |
| `TwoSecondMemoHandler` | MEMO_WAITING 상태로 대체 |
| `InstagramPendingMemoHandler` | MULTI/SINGLE/BULK 핸들러로 분리 |

### 신규 MessageType

```java
// 신규
REEL_PLACE_SELECTION,      // MULTI/BULK 단계 (콤마 숫자 / "전부" / "건너뛰기")
SINGLE_WANT_YES,           // "가고 싶어요"
SINGLE_WANT_NO,            // "발견으로만 저장"

// INSTAGRAM_PENDING_MEMO → REEL_MEMO_WAITING 으로 의미 확장
```

---

## 엣지케이스 (요약)

상세 매트릭스 8-6절: 설계 원본 참조.

- **EC-P 시리즈** (파싱): `1 2 3`, `1-3`, `일,삼`, 범위 외, 중복 등 14개 케이스
- **EC-U 시리즈** (새 URL): SELECTION/MEMO 중 새 URL 도착 시 기존 세션 자동 저장 후 새 PROCESSING
- **EC-T 시리즈** (TTL 만료): D-3/D-4에 따라 보수적 처리(전체 REEL)
- **EC-A 시리즈** (가드): 그룹 미연동, "그룹 연동하기" QuickReply 메모 오용
- **EC-X 시리즈** (인프라): Gemini 타임아웃, callback push 실패, DB 충돌
- **EC-R 시리즈** (D-7): SELECTION/MEMO 중 룰렛·공유 액션 거부 + 세션 유지

---

## 오래된 핀 정리 시스템

### 조건

```
memo_source = AUTO
AND tag = REEL
AND created_at < NOW() - INTERVAL '30 days'
AND want_count = 0
AND deleted_at IS NULL
```

### UX (D-10, D-12)

```
[/pins 하단 배너]
🗑️ 30일째 관심받지 못한 발견 핀이 N개 있어요
[🧹 한꺼번에 정리]  [⏰ 나중에]
```

- `[한꺼번에 정리]` → N개 일괄 soft delete
- `[나중에]` (D-11) → `users.cleanup_snoozed_until = NOW() + 7일`, 7일간 배너 미노출

### API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/v1/groups/{groupId}/cleanup/candidates` | 정리 대상 목록 |
| POST | `/api/v1/groups/{groupId}/cleanup/execute` | 일괄 soft delete |
| POST | `/api/v1/users/me/cleanup-snooze` | 7일 snooze 설정 |

---

## 맵 필터 (D-13, D-14)

### 발견 ▾ 서브 토글
```
[전체] [발견 ▾] [위시] [추억]
       └─ ✓ 모든 발견
          🙋 관심 있는 발견 (want_count >= 1)
```
- URL: `?tag=REEL&interest=true`
- 라벨 변경: "발견 (관심)"

### 릴스 번들 강조
- 알림 → "📍 지도에서 보기" → `/map?reel_bundle={notificationId}`
- 강조 핀 일반 렌더링, 비강조 핀 `opacity: 0.3`
- 상단 배너: "릴스 저장 핀 N개 표시 중 [해제]"

---

## 태그 진행 다이어그램 (D-15)

핀 카드/말풍선 `?` 아이콘 클릭 시 모달:
```
발견 → [가고싶어요 과반] → 위시 → [GPS 방문] → 추억
  ●            🙋🙋             ⭐             ❤️
```

---

## API 변경 요약

### 신규
| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/v1/groups/{groupId}/pins/{pinId}/want` | WANT 토글 (멱등) |
| GET | `/api/v1/groups/{groupId}/pins/{pinId}/want` | 내 WANT 상태 조회 |
| GET | `/api/v1/groups/{groupId}/cleanup/candidates` | 정리 대상 목록 |
| POST | `/api/v1/groups/{groupId}/cleanup/execute` | 일괄 정리 |
| POST | `/api/v1/users/me/cleanup-snooze` | snooze 설정 |

### 기존 확장
| 엔드포인트 | 변경 |
|-----------|------|
| `GET /pins` | 응답에 `wantCount`, `myWant` 필드 |
| `GET /pins` | `?interest=true` (want_count >= 1) 파라미터 |
| `GET /pins` | `?sort=want_count` 정렬 옵션 (D-9) |

---

## 프론트엔드 변경 요약

| 컴포넌트 | 변경 |
|----------|------|
| `PinMarker` / `lib/pin/markers.tsx` | 3단계 시각화 (`#7BB3E8` / `#7B68EE` / `#F4C842` 별) + 크기 + WISH 펄스 |
| `PinPopup` / `SpeechBubblePopup` | 출처 뱃지(📹/✏️) + "가고 싶어요" 토글 버튼 (REEL/WISH 노출, MEMORY 숨김) |
| `MapFilter` | "발견 ▾" 드롭다운 서브 토글 |
| `MapClient` | `?reel_bundle=` 쿼리 처리 + 비강조 핀 opacity 0.3 |
| `NotificationDetail` | "📍 지도에서 보기" 버튼 |
| `PinCard` | 출처 뱃지 + WANT 카운트 표시 |
| `TagTooltip` | `?` 아이콘 클릭 시 진행 다이어그램 모달 |
| `CleanupBanner` | `/pins` 하단 배너 + [한꺼번에 정리] [나중에] |

---

## 구현 우선순위

| 우선순위 | 항목 |
|----------|------|
| P0 | V012 마이그레이션 (`pin_events` + `want_count` + `cleanup_snoozed_until` + `WISH_CONVERTED`) |
| P0 | `WantService` + `WishConvertedEvent` + 인앱 알림 |
| P0 | WANT 토글 API + `GET /pins` 응답 확장 |
| P0 | 챗봇 `ReelSavedSelectionSession` 재설계 (8장 전체) |
| P0 | 마커 3단계 시각화 + PinPopup 가고 싶어요 버튼 |
| P1 | 맵 필터 발견 ▾ 드롭다운 + 릴스 번들 강조 |
| P1 | `TagTooltip` 진행 다이어그램 |
| P2 | 오래된 핀 정리 시스템 (`/pins` 배너, snooze, 일괄 API) |
| P3 | 가중치 합산 정렬 / VIEW·SHARE·ROULETTE 활성 (룰렛·공유 PR과 함께) |

---

## 도메인 협력

- **입력**: [[chatbot]] ReelSavedSelectionSession 결과, [[place]] Gemini/Kakao Local 검색, [[group]] 활성 멤버 수 (과반 계산)
- **출력**: [[map]] 마커 3단계 + 필터, [[notification]] WISH_CONVERTED 알림
- **참조**: [[memo]] 정책 변경 없음, [[tag]] 상태 의미 명확화

---

## Phase 11 종속성 / 영향

- Phase 11 "우리 기록" 의 월별 타임라인은 본 Phase의 `want_count` 필드를 활용 가능 (관심도 정렬 옵션)
- `NotificationService.createForVisitDetected` 본인 포함 fan-out 정책은 본 Phase 변경 없음 (과도기 유지)

---

## 후속 작업 (Phase 12 외)

- Phase 12.1 (TBD): VIEW 액션 활성 + 관심도 가중합 공식 도입
- Phase 12.2 (TBD): 룰렛/공유 PR에서 `ROULETTE_SELECTED`/`SHARE` enum 확장 (V0??)
- Phase 12.3 (TBD): 카카오 푸시 vs 인앱 알림 정책 재평가 (사용자 100명+ 도달 시점)
