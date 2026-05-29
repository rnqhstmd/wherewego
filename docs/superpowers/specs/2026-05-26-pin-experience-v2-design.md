# Pin Experience v2 — 확정 설계 (v2.1 patch) — **Phase 12**

> 최초 작성: 2026-05-26
> v2.1 patch: 2026-05-27 — 카카오 챗봇 제약 검증·UI 색 정정·19개 미해결 정책(D-1~D-19) 확정
> 상태: 확정 (구현 착수 가능)
> Phase 넘버링: **Phase 12** (V012 마이그레이션과 매칭)
> 컨텍스트 위치: [context/pin/phase-12-pin-experience-v2.md](../../../context/pin/phase-12-pin-experience-v2.md)

---

## v2.1 변경 요약 (v2 → v2.1)

| 영역 | v2 원안 | v2.1 확정 | 사유 |
|------|---------|----------|------|
| 마커 색 | 발견=연보라, 위시=민트 (가정) | 발견=하늘색, **관심=진보라 #7B68EE**, 위시=노랑별 (실제 운영색) | 운영 중인 실제 색 반영 |
| 관심(1표) 시각화 | 우상단 🙋 부착 | 색·크기 변경(부착 아이콘 없음) | 마커 작아 아이콘 시인성 ↓ |
| 챗봇 MULTI 선택 | 버튼 토글 누적 | **콤마 번호 직접 입력** + "전부"/"건너뛰기" QuickReply | 카카오는 버튼 클릭 = 즉시 발화. 토글 누적 UX 불가 |
| pin_events 액션 | WANT/VIEW/SHARE/ROULETTE_SELECTED (P0 동시) | **P0=WANT만**, 나머지는 룰렛/공유 구현 시 추가 | 미구현 기능의 dead code 방지 |
| WANT 멱등 단위 | 하루 1회 | **영구 멱등** (`pin_id, user_id, WANT` UNIQUE) | 점수 누적 폭발 방지 + 단순성 |
| 정리 snooze 저장 | LocalStorage | **DB** (`users.cleanup_snoozed_until`) | 다기기 일관성 |
| 정리 UX | 미정 | **일괄 정리만** | 단순성 |
| BULK_SAVE 컷오프 | 10개+ | **31개+** | 카카오 1000자 안전 마진 |
| TTL | 3분 (MULTI/MEMO) | **3분 통일** + 만료 시 전체 REEL 보수 처리 | 명확화 |
| Flyway 단위 | 미정 | **V012 단일** | V006 선례 |

---

## 1. 배경 및 목표

(v2 원안 그대로)

현재 핀 시스템의 문제:
- REEL(발견)과 WISH(위시)가 의미적으로 겹침
- 챗봇으로 릴스 저장 시 여러 장소가 동시 저장되어도 가장 가고 싶은 곳을 구분 못 함
- 커플 2명 그룹에서 좋아요 최대 2개 → 관심도 변별력 부족
- 챗봇 플로우에 복수 선택·메모·엣지케이스 처리 부재

목표: 출처(어디서 왔나)와 상태(지금 어떤 단계인가)를 분리하여 핀 시스템을 직관적으로 재설계.

---

## 2. 태그 시스템 — 출처 + 상태 분리

### 2-1. 상태 태그 (기존 `tag` 컬럼 유지)

| 태그 | 의미 | 마커 시각화 |
|------|------|------------|
| REEL (발견) | 릴스/앱으로 새로 발견한 곳 | 하늘색 원형 (기본 크기) |
| **WANT 1표 이상 (과반 미달)** | 그룹원 중 일부가 "가고 싶어요" 누른 상태 | **진보라 `#7B68EE`** 원형, 1.1배 크기 |
| WISH (위시) | 그룹원 과반 이상 관심 → 진짜 가고 싶은 곳 | 노랑 별, 1.2배 크기, 전환 시 펄스 1회 |
| MEMORY (추억) | GPS 방문 감지 또는 직접 전환 | 핑크 하트 |

> WANT 1표 상태는 별도 `tag` 값이 아니라 `tag=REEL AND want_count >= 1` 파생 상태.

### 2-2. 출처 뱃지 (`instagram_url IS NOT NULL` 로 파생)

| 뱃지 | 의미 | 표시 위치 |
|------|------|-----------|
| 📹 | 챗봇 릴스 등록 | 핀 카드 / 말풍선 상단 좌측 |
| ✏️ | 앱 직접 등록 | 핀 카드 / 말풍선 상단 좌측 |

### 2-3. REEL → WISH 전환 조건

- 조건: 그룹원 중 `floor(N/2) + 1` 명 이상 "가고 싶어요" 클릭
- 2인 그룹: 2명 모두 (1명 = 50%, 과반 미달)
- 3인 그룹: 2명 이상
- **전환 후 역전환 없음** (WANT 취소해도 WISH 유지)
- 본인 1표 포함 과반 계산

---

## 3. 마커 3단계 시각화 (v2.1 정정)

지도 위 마커가 관심도 상태를 즉시 시각화:

```
0표 (발견 기본)
  ● 하늘색 원형, 크기 1.0

1표 이상 (관심 — 과반 미달)
  ● 진보라 #7B68EE 원형, 크기 1.1

과반 달성 (위시)
  ⭐ 노랑 별, 크기 1.2, 전환 직후 펄스 0.5초 1회
```

**보조 시각 신호** (모두 적용):
- 크기 transition (1.0 → 1.1 → 1.2)
- WISH 전환 시 펄스 1회 (CSS keyframe 0.5초)
- 그림자 강화 / 흰 외곽선: **미적용** (간결성 우선)

**MEMORY 핀**: WANT 버튼 UI 자체 미노출 ("이미 다녀온 곳" 정책)

"가고 싶어요" 버튼: REEL 상태 핀 말풍선 하단 고정 노출.
WISH 전환 시 그룹원에게 인앱 알림 (본인 제외 N-1명).

---

## 4. 관심도 시스템 — pin_events

### 4-1. pin_events 테이블 (P0 신규)

```sql
CREATE TABLE pin_events (
    id         BIGSERIAL   PRIMARY KEY,
    pin_id     BIGINT      NOT NULL REFERENCES pins(id),
    user_id    BIGINT      NOT NULL REFERENCES users(id),
    group_id   BIGINT      NOT NULL REFERENCES groups(id),
    action     VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_pin_events_action CHECK (action IN ('WANT'))
);

-- D-19 영구 멱등성: 한 사용자는 한 핀에 WANT 한 번만
CREATE UNIQUE INDEX uq_pin_events_pin_user_want
    ON pin_events (pin_id, user_id)
    WHERE action = 'WANT';

CREATE INDEX idx_pin_events_pin_id ON pin_events(pin_id);
CREATE INDEX idx_pin_events_group_created ON pin_events(group_id, created_at DESC);
```

### 4-2. 액션 종류 (P0 vs 후속)

| action | 트리거 | 가중치(향후) | P0 활성 |
|--------|--------|-------------|---------|
| WANT | "가고 싶어요" 버튼 클릭 | 10 | ✅ |
| VIEW | 말풍선 상세 조회 | 1 | ❌ (룰렛·공유 PR 시점) |
| SHARE | 공유 카드 생성 | 3 | ❌ (공유 기능 PR 시점) |
| ROULETTE_SELECTED | 룰렛 결과 선택 | 5 | ❌ (룰렛 PR 시점) |

> P0에선 CHECK 제약이 `IN ('WANT')` 단독. 후속 PR에서 ALTER로 확장.

### 4-3. 관심도 정렬 (P0)

- P0: `/pins?sort=want_count` 단순 정렬만 노출
- 가중합 공식은 정의만 두고 P2+ 도입

### 4-4. WANT 멱등 처리 (D-19)

- 동일 `(pin_id, user_id, action=WANT)` UNIQUE
- 토글 동작:
  - WANT 안 누른 상태에서 클릭 → INSERT + `want_count++`
  - WANT 누른 상태에서 클릭 → DELETE + `want_count--`
- 트랜잭션: `SELECT ... FOR UPDATE on pins` → INSERT/DELETE → UPDATE want_count → 과반 검사 → tag 전환 → WishConvertedEvent (AFTER_COMMIT)
- WISH 전환 후 WANT 취소: want_count만 감소, tag는 WISH 유지

---

## 5. 오래된 핀 정리 시스템 (D-10, D-11, D-12)

### 5-1. 정리 대상 조건

```
memo_source = AUTO
AND tag = REEL
AND created_at < NOW() - INTERVAL '30 days'
AND want_count = 0
AND deleted_at IS NULL
```

### 5-2. UX

```
┌─────────────────────────────────────────┐
│  📋 /pins 핀 목록                        │
│  ...                                    │
├─────────────────────────────────────────┤
│  🗑️ 30일째 관심받지 못한 발견 핀이      │  ← 배너 (D-10)
│      N개 있어요                         │
│  [🧹 한꺼번에 정리]   [⏰ 나중에]        │
└─────────────────────────────────────────┘
```

- `[한꺼번에 정리]` (D-12) → 그 N개 핀 한 번에 soft delete
- `[나중에]` → `users.cleanup_snoozed_until = NOW() + 7일` (D-11), 7일간 배너 미노출
- 7일 후 자동 재노출
- 개별 핀 삭제는 기존 기능(핀 상세 삭제)으로 유지

### 5-3. API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/v1/groups/{groupId}/cleanup/candidates` | 정리 대상 핀 N개 조회 |
| POST | `/api/v1/groups/{groupId}/cleanup/execute` | 일괄 soft delete |
| POST | `/api/v1/users/me/cleanup-snooze` | snooze 7일 설정 |

---

## 6. 맵 필터 개선

### 6-1. 필터 탭 (D-13)

```
[전체]  [발견 ▾]  [위시]  [추억]
```

- "발견 ▾" 드롭다운 (서브 토글):
  - ✓ 모든 발견
  - 🙋 관심 있는 발견 (want_count >= 1)
- 선택 후 탭 라벨: "발견 (관심)"
- URL 쿼리: `?tag=REEL&interest=true`

### 6-2. 릴스 번들 맵 필터 (D-14)

- 알림 상세 "📍 지도에서 보기" 버튼 → `/map?reel_bundle={notificationId}`
- **강조 방식**: 비강조 핀 `opacity: 0.3`, 강조 핀은 일반 렌더링
- 상단 배너: "릴스 저장 핀 N개 표시 중 [해제]"
- 해제 시 일반 지도로 복귀

---

## 7. 태그 툴팁 (D-15)

핀 카드/말풍선의 태그 라벨 옆 `?` 아이콘 클릭 시 모달 노출:

```
발견 → [가고싶어요 과반] → 위시 → [GPS 방문] → 추억
  ●            🙋🙋             ⭐             ❤️
```

- 현재 핀 위치를 진행 다이어그램에서 강조 표시
- 본문: "발견 핀에서 그룹원이 모두 '가고 싶어요'를 누르면 위시로 바뀌어요!"
- WISH → MEMORY 전환은 GPS 방문 또는 직접 태그 변경

---

## 8. 챗봇 릴스 처리 플로우 (v2.1 전면 재설계)

### 8-1. 세션 상태 머신

```
IDLE
 │  인스타 URL
 ▼
PROCESSING (Gemini 파싱, useCallback=true)
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
 ├─ "1,3,5" 콤마 숫자 ──→ MEMO_WAITING (selected)
 ├─ "전부" QR ─────────→ MEMO_WAITING (all)
 ├─ "건너뛰기" QR ─────→ MEMO_WAITING (none = 전체 REEL)
 ├─ 파싱 실패 ─────────→ MULTI_SELECTING (TTL 미리셋)
 └─ TTL 3분 ──────────→ COMPLETE (전체 REEL, D-4)

BULK_SAVE (31개+)
 ├─ 메모 텍스트 ───────→ COMPLETE (all REEL + memo)
 ├─ "건너뛰기" QR ─────→ COMPLETE (all REEL)
 └─ TTL 3분 ──────────→ COMPLETE (all REEL)

MEMO_WAITING
 ├─ 메모 텍스트 ───────→ COMPLETE (저장 + memo)
 ├─ "건너뛰기" QR ─────→ COMPLETE (저장, 메모 X)
 └─ TTL 3분 ──────────→ COMPLETE (저장, 메모 X)
```

### 8-2. ReelSavedSelectionSession (신규)

```java
public record ReelSavedSelectionSession(
    Long groupId,
    Long userId,
    String instagramUrl,
    List<ExtractedPlace> allPlaces,
    Set<Integer> selectedIndices,
    SessionState state,
    Instant expiresAt  // 3분
) {
    public enum SessionState {
        PROCESSING, SINGLE_WANT, MULTI_SELECTING, BULK_SAVE, MEMO_WAITING
    }
}
```

### 8-3. 폐기되는 기존 클래스

| 클래스 | 처리 |
|--------|------|
| `PendingInstagramSession` | `ReelSavedSelectionSession` 으로 통합 |
| `TwoSecondMemoHandler` | MEMO_WAITING 상태로 대체 |
| `InstagramPendingMemoHandler` | MULTI/SINGLE/BULK 핸들러로 분리 |

### 8-4. 번호 파싱 규칙 (콤마 전용)

```
1. split(",")
2. 각 토큰 trim()
3. 빈 토큰 무시 (trailing/연속 콤마 허용)
4. ^\d+$ 정규식 (콤마 외 구분자 X, 한글 X, 범위 X)
5. 1 ≤ n ≤ 추출장소수 검증
6. LinkedHashSet으로 중복 제거 (조용히, D-2)
7. 한 토큰이라도 실패하면 전체 거부 + 재안내
```

허용 예: `1,3,5` / `1, 3, 5` / `1,2,3,` / `1,,3` / ` 1, 2 , 3 `
거부 예: `1 3 5` / `1-3` / `1.5` / `일,삼` / `1,2번` / `1,16`(N=15)

### 8-5. 정상 흐름 표

| ID | 상태 | 입력 | 동작 | 다음 | 안내 문구 |
|----|------|------|------|------|----------|
| F-1 | IDLE | 인스타 URL | Gemini 파싱 + useCallback | PROCESSING | "🔍 릴스에서 장소를 찾고 있어요. 잠시만요…" |
| F-2 | PROCESSING | 장소 1개 추출 | SINGLE_WANT 진입 | SINGLE_WANT | "📍 {placeName} 1곳 발견!\n가보고 싶은 곳인가요?\n[💙 가고 싶어요] [📍 발견으로만 저장]" |
| F-3 | PROCESSING | 2~30개 추출 | MULTI_SELECTING 진입 | MULTI_SELECTING | "🗺️ N곳 발견!\n가고 싶은 번호를 콤마로 보내주세요.\n\n1. {p1}\n…\nN. {pN}\n\n💡 예) 1,3,5\n[🌟 전부] [⏭️ 건너뛰기]" |
| F-4 | PROCESSING | 31개+ 추출 | BULK_SAVE 진입 | BULK_SAVE | "🗺️ N곳이나 발견됐어요!\n많아서 모두 발견으로 저장할게요.\n메모를 남기시겠어요?\n[⏭️ 건너뛰기]" |
| F-5 | SINGLE_WANT | "가고 싶어요" QR | want=true, 메모 요청 | MEMO_WAITING | "💙 {placeName}을(를) 가고 싶은 곳으로 표시할게요.\n메모를 남기시겠어요?\n[⏭️ 건너뛰기]" |
| F-6 | SINGLE_WANT | "발견으로만 저장" QR | want=false, 메모 요청 | MEMO_WAITING | "📍 {placeName}을(를) 발견으로 저장할게요.\n메모를 남기시겠어요?\n[⏭️ 건너뛰기]" |
| F-7 | MULTI_SELECTING | "1,3,5" 파싱 성공 | 선택 확정 + 메모 요청 | MEMO_WAITING | "✓ {list}을(를) 가고 싶은 곳으로, 나머지는 발견으로 저장할게요.\n메모를 남기시겠어요?\n[⏭️ 건너뛰기]" |
| F-8 | MULTI_SELECTING | "전부" QR | 전체 인덱스 선택 | MEMO_WAITING | "🌟 N곳 전부 가고 싶은 곳으로 표시할게요.\n메모를 남기시겠어요?\n[⏭️ 건너뛰기]" |
| F-9 | MULTI_SELECTING | "건너뛰기" QR | 빈 선택 (전체 REEL) | MEMO_WAITING | "📍 N곳 모두 발견으로 저장할게요.\n메모를 남기시겠어요?\n[⏭️ 건너뛰기]" |
| F-10 | BULK_SAVE | 메모 텍스트 | 전체 REEL + memo 저장 | COMPLETE | "✅ N곳을 발견으로 저장했어요!\n📝 메모: {memo}" |
| F-11 | BULK_SAVE | "건너뛰기" QR | 전체 REEL 저장 | COMPLETE | "✅ N곳을 발견으로 저장했어요!" |
| F-12 | MEMO_WAITING | 메모 텍스트 | 저장 실행 | COMPLETE | "✅ 저장 완료!\n🌟 가고 싶어요: {wanted}\n📍 발견: {discovery}\n📝 메모: {memo}" |
| F-13 | MEMO_WAITING | "건너뛰기" QR | 메모 없이 저장 | COMPLETE | "✅ 저장 완료!\n🌟 가고 싶어요: {wanted}\n📍 발견: {discovery}" |

### 8-6. 엣지케이스 표

#### 입력 파싱 (MULTI_SELECTING)
| ID | 입력 | 동작 | 다음 | 문구 |
|----|------|------|------|------|
| EC-P1 | "1 2 3" 공백 구분 | 콤마 없음 → 실패 | MULTI (유지) | "❓ 콤마로 구분된 숫자만 보내주세요. 예) 1,3,5\n{목록 재노출}" |
| EC-P2 | "1-3" 범위 | 실패 | MULTI (유지) | EC-P1과 동일 |
| EC-P3 | "일,삼" | 실패 | MULTI (유지) | EC-P1과 동일 |
| EC-P4 | "1,2번,3" | 일부 실패 → 전체 거부 | MULTI (유지) | EC-P1과 동일 |
| EC-P5 | "1,16" 범위 외 | 실패 | MULTI (유지) | "❓ 1~N번 사이만 보내주세요." |
| EC-P6 | "0,1" | 실패 | MULTI (유지) | EC-P5와 동일 |
| EC-P7 | "1,,3" 빈 토큰 | 빈 토큰 무시, 채택 | MEMO_WAITING | F-7 |
| EC-P8 | "1, 2, 3" 공백 | trim 후 정상 | MEMO_WAITING | F-7 |
| EC-P9 | "1,2,3," trailing | 무시, 정상 | MEMO_WAITING | F-7 |
| EC-P10 | "1,1,2" 중복 | dedup, 침묵 처리 (D-2) | MEMO_WAITING | F-7 (중복 언급 없음) |
| EC-P11 | "3" 단일 | 단일 선택 | MEMO_WAITING | F-7 |
| EC-P12 | "," 만 | 빈 결과 → 실패 | MULTI (유지) | EC-P1과 동일 |
| EC-P13 | 빈 발화 | 무시 | MULTI (유지) | EC-P1과 동일 |
| EC-P14 | "다 별로" 자연어 | 실패 | MULTI (유지) | EC-P1과 동일 |
| EC-P15 | "전부" 텍스트 | F-8 동일 | MEMO_WAITING | F-8 |

#### 새 URL 도착
| ID | 현재 상태 | 동작 | 다음 | 문구 |
|----|----------|------|------|------|
| EC-U1 | SINGLE_WANT | 기존 단일 REEL 자동 저장 (메모 없음) + 새 URL PROCESSING | PROCESSING | "📌 이전 링크는 메모 없이 자동 저장되었어요\n🔍 새 릴스 분석 중…" |
| EC-U2 | MULTI_SELECTING | 선택분 WANT, 미선택 REEL 자동 저장 | PROCESSING | "📌 이전 선택({N}개)은 가고 싶어요로, 나머지는 발견으로 저장되었어요" |
| EC-U3 | MULTI_SELECTING (선택 0) | 전체 REEL | PROCESSING | "📌 이전 N곳은 모두 발견으로 저장되었어요" |
| EC-U4 | BULK_SAVE | 전체 REEL (메모 없음) | PROCESSING | "📌 이전 N곳은 모두 발견으로 저장되었어요" |
| EC-U5 | MEMO_WAITING | 메모 없이 저장 | PROCESSING | "📌 이전 링크는 메모 없이 저장되었어요" |
| EC-U6 | 모든 활성 | 동일 URL 재전송 (RESEND-1) | IDLE | "📌 이 링크는 이미 저장되었어요\n{결과 재표시}" |
| EC-U7 | IDLE | 미지원 도메인 | IDLE | "지원하지 않는 링크입니다." |

#### TTL 만료 (3분)
| ID | 상태 | 동작 | 다음 발화 prepend |
|----|------|------|---------------------|
| EC-T1 | SINGLE_WANT | REEL 저장 (D-3) | "⏱️ {placeName}을(를) 발견으로 자동 저장했어요" |
| EC-T2 | MULTI_SELECTING | 전체 REEL (D-4) | "⏱️ 시간 초과로 N곳 모두 발견으로 저장했어요" |
| EC-T3 | BULK_SAVE | 전체 REEL | "⏱️ N곳 모두 발견으로 저장했어요" |
| EC-T4 | MEMO_WAITING | 기존 선택 저장 (메모 없음) | "⏱️ 메모 없이 저장 완료했어요" |

#### 가드 / 인프라
| ID | 조건 | 동작 | 다음 | 문구 |
|----|------|------|------|------|
| EC-A1 | 봇 매핑 미연동 + URL | 가드 | IDLE | "먼저 그룹 연동이 필요해요. [🔗 그룹 연동하기]" |
| EC-A2 | 활성 그룹 없음 | 가드 | IDLE | "그룹에 먼저 참여해주세요." |
| EC-A3 | MEMO_WAITING에서 "그룹 연동하기" 발화 | 세션 유지 + 안내 | (유지) | "지금은 메모 입력 중이에요." |
| EC-A4 | MULTI에서 "그룹 연동하기" 발화 | 세션 유지 + 안내 | MULTI | "지금은 가고 싶은 곳 선택 중이에요." |
| EC-X1 | Gemini 5초 타임아웃 | useCallback push 실패 | IDLE | "❗ 처리 중 시간이 오래 걸려요. 잠시 후 결과를 알려드릴게요." |
| EC-X2 | Gemini 일시 오류 | IDLE 복귀 | IDLE | "잠시 후 다시 시도해주세요." |
| EC-X3 | 장소 0개 추출 | IDLE | IDLE | "이 릴스에서는 장소를 찾지 못했어요." |
| EC-X4 | 카카오 Local 검색 전부 실패 | manualNeeded 안내 | IDLE | "❓ 장소를 정확히 찾기 어려워요. 앱에서 직접 등록해주세요." |
| EC-X5 | DB unique 충돌 | alreadySaved | IDLE | "📌 이미 저장된 장소" |
| EC-X6 | callback push 실패 | PendingNotificationSession 적재 | (유지) | (다음 발화 prepend) |
| EC-X7 | RateLimit 차단 | 429 | (유지) | "잠시 후 다시 시도해주세요." |
| EC-R1 | SELECTION/MEMO 중 룰렛 액션 | 거부 + 세션 유지 (D-7) | (유지) | "지금은 릴스 장소 처리 중이에요. 끝나면 다시 시도해주세요." |
| EC-R2 | SELECTION/MEMO 중 공유 액션 | 거부 + 세션 유지 (D-7) | (유지) | EC-R1과 동일 |

#### 응답/메모 한계
| ID | 조건 | 동작 |
|----|------|------|
| EC-D1 | MEMO_WAITING 빈 텍스트 | 무시 + 재안내 |
| EC-D2 | 1000자 초과 메모 | 900자 절단 + 안내 |
| EC-D3 | SINGLE_WANT 임의 텍스트 | 무시 + QR 재노출 |
| EC-D4 | 미선택 장소 일부가 이미 저장됨 | alreadySaved로 분류 |
| EC-D5 | MEMO 단계 "전부"/"건너뛰기" 텍스트 | 메모로 저장 (D-5) |
| EC-D6 | confident=false 일부 | manualNeeded |
| EC-L1 | 1000자 초과 목록 | cards outputs 분할 |
| EC-L2 | 결과 본문 1000자 초과 | 절단 + "앱에서 확인" |

### 8-7. 메모 적용 정책

- SINGLE_WANT: 해당 1개 핀에만
- MULTI_SELECTING: 같은 릴스에서 저장된 모든 핀에 broadcast (`memo_source = AUTO`)
- BULK_SAVE: 전체 핀에 broadcast
- 메모 건너뛰기: `memo = null, memo_source = AUTO` 유지

### 8-8. 횡단 정책

| 정책 | 결정 |
|------|------|
| TTL | 모든 대기 상태 3분 (`reel-selection-ttl-seconds=180`) |
| 세션 키 | `botUserKey` 단위 단일 세션 |
| 자동 저장 알림 | `PendingNotificationSession`에 적재, 다음 발화 시 1회 prepend |
| RESEND 가드 | 같은 URL 10분 내 재전송 시 EC-U6 발동 |
| 파싱 실패 시 TTL | 리셋 안 함 (무한 재시도 방지) |
| 동시성 race | 직렬화 락 없음 (D-6, Caffeine + webhook 자체 직렬화로 충분) |

---

## 9. 신규 MessageType 열거값 (v2.1 단순화)

```java
// 기존 유지
INSTAGRAM_LINK,
INSTAGRAM_PENDING_MEMO,    // → REEL_MEMO_WAITING으로 의미 확장
LINK_CODE,
PLACE_SELECTION,
TEXT_2SEC_CANDIDATE,       // (deprecated, 호환 유지)
UNKNOWN,

// 신규
REEL_PLACE_SELECTION,      // MULTI/BULK 단계 입력 (콤마 숫자 / "전부" / "건너뛰기")
SINGLE_WANT_YES,           // "가고 싶어요"
SINGLE_WANT_NO,            // "발견으로만 저장"
```

`MessageClassifier` 우선순위:
```
PLACE_SELECTION > LINK_CODE > INSTAGRAM_LINK
  > SINGLE_WANT_YES/NO   (session.state=SINGLE_WANT + 정확 매칭)
  > REEL_PLACE_SELECTION (session.state=MULTI/BULK)
  > REEL_MEMO_WAITING    (session.state=MEMO_WAITING)
  > TEXT_2SEC_CANDIDATE > UNKNOWN
```

---

## 10. 데이터 모델 변경 요약

### 신규 테이블
| 테이블 | 용도 |
|--------|------|
| `pin_events` | WANT 액션 기록 (P0), 후속 VIEW/SHARE/ROULETTE |

### 컬럼 추가
| 테이블 | 컬럼 | 설명 |
|--------|------|------|
| `pins` | `want_count INT NOT NULL DEFAULT 0` | WANT 캐시 카운트 |
| `users` | `cleanup_snoozed_until TIMESTAMPTZ` | 정리 배너 snooze (D-11) |

### 제약/인덱스 추가
| 항목 | 정의 |
|------|------|
| `chk_notifications_type` | 확장: `+ 'WISH_CONVERTED'` |
| `uq_pin_events_pin_user_want` | UNIQUE `(pin_id, user_id) WHERE action='WANT'` (D-19) |
| `idx_pin_events_pin_id` | `(pin_id)` |
| `idx_pin_events_group_created` | `(group_id, created_at DESC)` |
| `idx_pins_cleanup` | `(group_id, created_at) WHERE tag='REEL' AND memo_source='AUTO' AND deleted_at IS NULL` |

### Flyway (D-18 V012 단일)
```sql
-- V012__pin_experience_v2.sql
-- 단일 트랜잭션 (V006 선례)
CREATE TABLE pin_events (...);
CREATE UNIQUE INDEX uq_pin_events_pin_user_want ON pin_events (...) WHERE action='WANT';
CREATE INDEX idx_pin_events_pin_id ON pin_events(pin_id);
CREATE INDEX idx_pin_events_group_created ON pin_events(group_id, created_at DESC);

ALTER TABLE pins ADD COLUMN want_count INT NOT NULL DEFAULT 0;
CREATE INDEX idx_pins_cleanup ON pins (group_id, created_at)
    WHERE tag='REEL' AND memo_source='AUTO' AND deleted_at IS NULL;

ALTER TABLE notifications DROP CONSTRAINT chk_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type
    CHECK (type IN ('MANUAL_PIN', 'CHATBOT_PINS', 'VISIT_DETECTED', 'WISH_CONVERTED'));

ALTER TABLE users ADD COLUMN cleanup_snoozed_until TIMESTAMPTZ;
```

---

## 11. API 변경 요약

### 신규 엔드포인트
| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/v1/groups/{groupId}/pins/{pinId}/want` | WANT 토글 (멱등) |
| GET | `/api/v1/groups/{groupId}/pins/{pinId}/want` | 내 WANT 상태 + 카운트 조회 |
| GET | `/api/v1/groups/{groupId}/cleanup/candidates` | 정리 대상 목록 |
| POST | `/api/v1/groups/{groupId}/cleanup/execute` | 일괄 정리 실행 |
| POST | `/api/v1/users/me/cleanup-snooze` | 7일 snooze 설정 |

### 기존 엔드포인트 확장
| 엔드포인트 | 변경 |
|-----------|------|
| `GET /api/v1/groups/{groupId}/pins` | 응답에 `wantCount`, `myWant` 필드 추가 |
| `GET /api/v1/groups/{groupId}/pins` | `?interest=true` (want_count >= 1) 파라미터 |
| `GET /api/v1/groups/{groupId}/pins` | `?sort=want_count` 정렬 옵션 (D-9) |

### WANT 토글 응답 스키마
```json
{
  "tag": "WISH",
  "wantCount": 2,
  "myWant": true,
  "wishConverted": true   // 이번 호출로 REEL → WISH 전환된 경우 true
}
```

---

## 12. 알림 메시지 템플릿 (D-16)

| Type | 본문 |
|------|------|
| `WISH_CONVERTED` | "🌟 '{placeName}'이 위시로 올라갔어요! 둘 다 가고 싶어해요" |

- 채널: 인앱 알림 (NotificationService)
- 발송 범위: 본인 제외 그룹원 N-1명
- 멱등성: 동일 핀 WISH_CONVERTED 알림은 1회만 INSERT (notifications row unique 보장)
- 발송 트리거: `WishConvertedEvent` `@TransactionalEventListener(AFTER_COMMIT)`

---

## 13. 프론트엔드 변경 요약

| 컴포넌트 | 변경 |
|----------|------|
| `PinMarker` | 3단계 시각화 (하늘색/진보라 #7B68EE/노랑별) + 크기 transition + WISH 펄스 |
| `PinPopup` | 출처 뱃지(📹/✏️) + "가고 싶어요" 토글 버튼 (REEL/WISH 노출, MEMORY 숨김) |
| `MapFilter` | "발견 ▾" 드롭다운 서브 토글 (D-13) |
| `MapClient` | `?reel_bundle=` 쿼리 + 비강조 핀 opacity 0.3 (D-14) |
| `NotificationDetail` | "📍 지도에서 보기" 버튼 |
| `PinCard` | 출처 뱃지 + WANT 카운트 표시 |
| `TagTooltip` | `?` 아이콘 클릭 시 진행 다이어그램 모달 (D-15) |
| `CleanupBanner` | `/pins` 하단 배너 + [한꺼번에 정리] [나중에] (D-10, D-12) |

---

## 14. 구현 우선순위

| 우선순위 | 항목 | 이유 |
|----------|------|------|
| P0 | V012 마이그레이션 (`pin_events` + `want_count` + `cleanup_snoozed_until` + `WISH_CONVERTED`) | 모든 후속의 기반 |
| P0 | WantService + WishConvertedEvent + 알림 | 핵심 기능 |
| P0 | POST/GET WANT API + `GET /pins` 응답 확장 | 프론트 연동 진입점 |
| P0 | 챗봇 `ReelSavedSelectionSession` 재설계 (8장 전체) | PendingInstagramSession 교체 |
| P0 | 마커 3단계 시각화 (PinMarker) | 사용자 체감 핵심 |
| P0 | PinPopup "가고 싶어요" 버튼 | 입력 경로 |
| P1 | 맵 필터 서브 토글 + 릴스 번들 강조 (6장) | 편의 기능 |
| P1 | TagTooltip 진행 다이어그램 (7장) | 온보딩 보조 |
| P2 | 오래된 핀 정리 시스템 (5장) | 데이터 위생, 데이터 누적 후 의미 |
| P3 | 가중치 합산 정렬 / VIEW·SHARE·ROULETTE 활성 | 룰렛·공유 PR과 함께 |

---

## 15. 미해결 정책 (모두 클로즈됨)

| ID | 항목 | 결정 |
|----|------|------|
| D-1 | BULK_SAVE 컷오프 | 30개 |
| D-2 | 중복 번호 안내 | 침묵 dedup |
| D-3 | SINGLE TTL 만료 | REEL만 |
| D-4 | MULTI TTL 만료 | 전체 REEL |
| D-5 | MEMO 단계 키워드 | 메모로 저장 |
| D-6 | 동시성 race | 그대로 |
| D-7 | SELECTION 중 룰렛/공유 | 거부 + 세션 유지 |
| D-8 | pin_events P0 활성 | WANT만 |
| D-9 | 관심도 정렬 | want_count만 |
| D-10 | 정리 노출 | /pins 하단 배너만 |
| D-11 | snooze 저장 | DB (`users.cleanup_snoozed_until`) |
| D-12 | 정리 UX | 일괄 정리만 |
| D-13 | 맵 필터 형태 | 드롭다운 |
| D-14 | 릴스 번들 강조 | 비강조 opacity 0.3 |
| D-15 | 태그 툴팁 위치 | 핀 카드/말풍선 ? 아이콘 |
| D-16 | WISH 알림 스타일 | 간결형 |
| D-17 | ROULETTE/SHARE enum | 구현 시 추가 |
| D-18 | Flyway 단위 | V012 단일 |
| D-19 | WANT 멱등 | 영구 UNIQUE |
