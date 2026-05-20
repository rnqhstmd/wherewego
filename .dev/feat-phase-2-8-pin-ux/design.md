# 설계서: Phase 2.8 — 핀 도메인 UX 완성 (instagramUrl 입력 / 장소정보 수정 / 지도 삭제)

## 설계 규모

**중형** — 신규 0개 + 수정 12개. 신규 도메인 개념·테이블·라우트 없음. 기존 부분 수정/server action 분리/`useOptimistic` reducer 일반화 패턴 확장. `PinUpdateCommand.of()` 시그니처 4-arg→8-arg로 호출처 15곳 영향.

---

## 변경 범위

### 수정 대상 파일 (12개)

**Backend (5)**
1. `backend/.../domain/pin/Pin.java` — `changePlaceInfo(String, boolean, String)` 메서드 신규
2. `backend/.../domain/pin/PinUpdateCommand.java` — 필드 4→8 + of() 검증
3. `backend/.../domain/pin/PinService.java` — `updatePin` 분기 추가
4. `backend/.../interfaces/api/pin/PinV1Dto.java` — `UpdatePinRequest` 필드 2→4 + `toCommand()` 확장
5. `backend/.../support/error/ErrorType.java` — `PIN_UPDATE_EMPTY` 메시지 일반화

**Frontend (7)**
6. `frontend/src/lib/api/pin.ts` — `PinPatch` 확장 (placeName/address: string만)
7. `frontend/src/lib/pin/constants.ts` — `PLACE_NAME_MAX_LENGTH`/`ADDRESS_MAX_LENGTH` 신규
8. `frontend/src/app/map/_components/MemoTagPanelContent.tsx` — instagramUrl 입력 + `https://` 검증 (검색·picker 양 경로 자동 커버)
9. `frontend/src/app/map/_components/PinPopup.tsx` — footer 삭제 버튼 + `onRequestDelete` + `deleteError` props
10. `frontend/src/app/map/actions.ts` — `deletePinAction` 신규 (try/catch + `revalidatePath("/pins")`)
11. `frontend/src/app/map/MapClient.tsx` — useOptimistic reducer 일반화(`patch|remove`), 모달 렌더, 핀별 에러 보관
12. `frontend/src/app/pins/_components/PinEditDialog.tsx` — placeName/address 필드 (순서: 장소명→주소→태그→메모)
13. `frontend/src/app/pins/PinListClient.tsx` — `applyPatch` reducer에 placeName/address 케이스 추가

> 신규 파일 없음. `/map` 삭제 모달은 `pins/_components/PinDeleteConfirm.tsx` **재사용**.

---

## 상세 설계

### 1. `Pin.java` — `changePlaceInfo` 신규

```java
public void changePlaceInfo(String placeName, boolean addressProvided, String address) {
    this.placeName = placeName;
    if (addressProvided) {
        this.address = address;
    }
}
```
placeName 검증은 Command 레이어에서 수행.

### 2. `PinUpdateCommand.java` — 4→8 필드

```java
public record PinUpdateCommand(
        boolean memoProvided, String memo,
        boolean tagProvided, PinTag tag,
        boolean placeNameProvided, String placeName,
        boolean addressProvided, String address) {

    public static PinUpdateCommand of(...) {
        if (!memoProvided && !tagProvided && !placeNameProvided && !addressProvided) {
            throw new CoreException(ErrorType.PIN_UPDATE_EMPTY);
        }
        if (tagProvided && tag == null) throw new CoreException(ErrorType.PIN_TAG_INVALID);
        if (memoProvided && memo != null && memo.length() > 500) throw new CoreException(ErrorType.PIN_MEMO_TOO_LONG);
        if (placeNameProvided) {
            if (placeName == null || placeName.isBlank() || placeName.length() > 200)
                throw new CoreException(ErrorType.PIN_PLACE_NAME_INVALID);
        }
        if (addressProvided && address != null && address.length() > 500)
            throw new CoreException(ErrorType.PIN_ADDRESS_INVALID);
        return new PinUpdateCommand(...);
    }
}
```

### 3. `PinService.updatePin` — placeName/address 분기

```java
if (cmd.tagProvided()) { pin.changeTag(cmd.tag()); }
if (cmd.memoProvided()) { /* 기존 분기 유지 */ }
if (cmd.placeNameProvided()) {
    pin.changePlaceInfo(cmd.placeName(), cmd.addressProvided(), cmd.address());
} else if (cmd.addressProvided()) {
    pin.changePlaceInfo(pin.getPlaceName(), true, cmd.address());
}
```

### 4. `PinV1Dto.UpdatePinRequest` — 필드 2→4

```java
public record UpdatePinRequest(JsonNode memo, JsonNode tag,
                               JsonNode placeName, JsonNode address) {
    public PinUpdateCommand toCommand() {
        // memo, tag 기존 로직 유지...

        boolean placeNameProvided = placeName != null && !placeName.isNull();
        String placeNameValue = null;
        if (placeNameProvided) {
            if (!placeName.isTextual()) throw new CoreException(ErrorType.PIN_PLACE_NAME_INVALID);
            placeNameValue = placeName.asText().trim();  // of() 가 blank 재검증
        }

        boolean addressProvided = address != null && !address.isNull();
        String addressValue = null;
        if (addressProvided) {
            if (!address.isTextual()) throw new CoreException(ErrorType.PIN_ADDRESS_INVALID);
            String trimmed = address.asText().trim();
            if (trimmed.isEmpty()) {
                addressProvided = false;  // Q5: 빈 문자열은 미변경으로 안전 무시
            } else {
                addressValue = trimmed;
            }
        }

        return PinUpdateCommand.of(memoProvided, memoValue, tagProvided, tagValue,
                placeNameProvided, placeNameValue, addressProvided, addressValue);
    }
}
```

### 5. `ErrorType.PIN_UPDATE_EMPTY` — 메시지 일반화

`"수정할 필드(memo 또는 tag)가 없습니다."` → `"수정할 필드가 없습니다."`

### 6. `lib/api/pin.ts::PinPatch` 확장

```ts
export interface PinPatch {
  memo?: string;
  tag?: PinTag;
  placeName?: string;
  address?: string;  // Q5: null/빈 문자열 보내지 않음
}
```

### 7. `lib/pin/constants.ts` — 상수 추가

```ts
export const PLACE_NAME_MAX_LENGTH = 200;
export const ADDRESS_MAX_LENGTH = 500;
```

### 8. `MemoTagPanelContent.tsx` — instagramUrl 필드

> **검색·picker 양 경로 자동 커버**: `MapClient.tsx::handleConfirmCrosshair`가 `activeSheet="memo"`로 전환하여 picker도 동일 컴포넌트 통과. `AddPinPickerContent`는 변경 없음.

- state: `instagramUrl`, `urlError`
- 메모 아래에 `<PanelLabel>Instagram URL (선택)</PanelLabel>` + `<Input>`
- 검증: `url.trim().length === 0 || url.trim().startsWith("https://")`. 실패 시 `urlError="올바른 URL 형식이 아닙니다"`, 등록 버튼 비활성
- `createPinAction` 호출 시 `instagramUrl: instagramUrl.trim() || null` 추가
- 에러 코드 매핑: `PLC_DUPLICATE_PIN → "이미 등록된 장소예요"` (Q3 단일 메시지)

### 9. `PinPopup.tsx` — 삭제 버튼 + props

신규 props:
- `onRequestDelete: (pin: PinSummaryResponse) => void`
- `deleteError: string | null`

footer 레이아웃 (`expanded === true`일 때):
```
┌ [태그] [메모] (세그먼트 탭) ─────────────┐
│ 태그/메모 본문                              │
│ ─────── HLine ────────                  │
│                                  [ 삭제 ] │ ← color: colors.pinNew
│ (deleteError 있을 때 인라인 빨간 텍스트)   │
└─────────────────────────────────────────┘
```
- "삭제" 버튼: `colors.pinNew`, font 12px, padding 6/10, background transparent, no border
- 삭제 클릭 → `onRequestDelete(pin)` (PinPopup은 자신을 닫지 않음)
- `deleteError` truthy → footer 하단 인라인 빨간 텍스트

### 10. `map/actions.ts::deletePinAction` 신규

```ts
export async function deletePinAction(
  groupId: number, pinId: number
): Promise<DeletePinActionResult> {
  try {
    await deletePin(groupId, pinId);
    try { revalidatePath("/pins"); }
    catch (e) { console.error("revalidatePath('/pins') 실패 (삭제는 성공)", e); }
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) return { ok: false, code: error.code, message: error.message };
    throw error;
  }
}
```

이유: `/map`은 클라이언트 state(useOptimistic)로 마커 인스턴스 캐시 유지 → `revalidatePath('/map')` 미호출 (MUST-1). `/pins` 라우트만 동기화.

`/pins/actions.ts::deletePinAction`과 동명 함수 공존 — 각 라우트 상대경로 import이므로 런타임 충돌 없음.

### 11. `MapClient.tsx` — reducer 일반화 + 삭제 흐름

```ts
type OptimisticAction =
  | { kind: "patch"; pinId: number; patch: Partial<PinSummaryResponse> }
  | { kind: "remove"; pinId: number };

const [optimisticPins, applyOptimistic] = useOptimistic<PinSummaryResponse[], OptimisticAction>(
  pins, (current, action) => {
    if (action.kind === "remove") return current.filter(p => p.id !== action.pinId);
    return current.map(p => p.id === action.pinId ? { ...p, ...action.patch } : p);
  }
);
```

기존 호출처(`handleTagChange`, `handleMemoChange`)는 `applyOptimistic({ kind: "patch", pinId, patch })`로 변환.

신규 state:
- `deleteCandidate: PinSummaryResponse | null`
- `deleteErrorByPinId: Record<number, string>` (핀별 직전 실패 메시지)

PinPopup props 전달:
```tsx
<PinPopup
  ...
  onRequestDelete={(pin) => {
    setDeleteErrorByPinId(prev => { const { [pin.id]: _, ...rest } = prev; return rest; });
    setDeleteCandidate(pin);
  }}
  deleteError={deleteErrorByPinId[selectedPin.id] ?? null}
/>
```

확인 핸들러 (AC-16 흐름):
```ts
const handleConfirmDelete = useCallback(() => {
  if (!deleteCandidate) return;
  const pinId = deleteCandidate.id;
  setDeleteCandidate(null);  // 1) 모달 닫기
  startOptimisticTransition(async () => {
    applyOptimistic({ kind: "remove", pinId });  // 2) optimistic 제거
    const result = await deletePinAction(groupId, pinId);  // 3) 서버 호출
    if (result.ok) {
      setPins(prev => prev.filter(p => p.id !== pinId));
      setSelectedPinId(null);
      return;
    }
    const message = result.code === "GROUP_NOT_MEMBER" ? "권한이 없어요"
                  : result.code === "PIN_NOT_FOUND"    ? "이 핀을 찾을 수 없어요"
                  : result.message;
    setDeleteErrorByPinId(prev => ({ ...prev, [pinId]: message }));  // 4) 에러 저장
    setSelectedPinId(pinId);  // 5) 재선택 → PinPopup 재mount → 자동 롤백 시 인라인 에러 표시
  });
}, [deleteCandidate, groupId, applyOptimistic]);
```

모달 렌더 (PinDeleteConfirm 재사용):
```tsx
import { PinDeleteConfirm } from "@/app/pins/_components/PinDeleteConfirm";
// ...
{deleteCandidate && (
  <PinDeleteConfirm
    pin={deleteCandidate}
    onCancel={() => setDeleteCandidate(null)}
    onConfirm={handleConfirmDelete}
  />
)}
```

### 12. `PinEditDialog.tsx` — placeName/address 필드

`PinEditPatch` 확장:
```ts
export interface PinEditPatch {
  placeName?: string;
  address?: string;
  tag?: PinTag;
  memo?: string;
  // instagramUrl: Phase 2.8 범위 외 (별도 Phase)
}
```

UI 순서 (Q4): 장소명 → 주소 → 태그 → 메모

검증:
- placeName trim 빈 → 비활성 + "장소명을 입력해주세요" (AC-8)
- placeName 200자 초과 → 비활성 + 카운터 빨간색 (AC-10)
- address 500자 초과 → 비활성 + 카운터 빨간색

`canSave`: `(placeNameChanged || addressChanged || memoChanged || tagChanged) && 모든검증통과`. `addressChanged`는 양쪽 trim 후 비교 → 빈 입력은 자동으로 changed=false.

### 13. `PinListClient.tsx::applyPatch` reducer 확장

```ts
function applyPatch(pin, patch) {
  const next = { ...pin };
  if (patch.tag !== undefined) next.tag = patch.tag;
  if (patch.memo !== undefined) {
    if (patch.memo === "") { next.memo = null; next.memoSource = null; }
    else { next.memo = patch.memo; next.memoSource = "MANUAL"; }
  }
  if (patch.placeName !== undefined) next.placeName = patch.placeName;
  if (patch.address !== undefined) next.address = patch.address;
  return next;
}
```

AC-7/QE-2 충족 (PinEditDialog 저장 후 카드 즉시 반영).

---

## API 명세

### `PATCH /api/v1/groups/{groupId}/pins/{pinId}` (확장)

**필드별 정규화/검증** (Q5 반영):

| 필드 | 키 없음 | JSON null | "" (빈 문자열) | 비-blank 문자열 |
|---|---|---|---|---|
| placeName | 미변경 | 미변경 | `PIN_PLACE_NAME_INVALID` | trim 후 길이검사 |
| address | 미변경 | 미변경 | **미변경 (안전 무시)** | trim 후 길이검사 |
| memo | 미변경 | 미변경 | 잠금 해제 | MANUAL 저장 |
| tag | 미변경 | 미변경 | `PIN_TAG_INVALID` | enum 검증 |

### `POST /api/v1/groups/{groupId}/pins` — 변경 없음
`instagramUrl` 필드는 이미 `CreatePinRequest`에 존재.

### `DELETE /api/v1/groups/{groupId}/pins/{pinId}` — 변경 없음
`/map`에서도 동일 라우트 호출.

---

## 핵심 결정 (확정)

- **D1**: PinUpdateCommand 평면 확장 4→8 필드
- **D2**: 삭제 버튼 HLine + 우측 정렬 텍스트 (pinNew color)
- **D3**: 기존 PinDeleteConfirm 재사용 (신규 모달 만들지 않음)
- **D4**: instagramUrl 검증 `trim().startsWith("https://")` 만
- **D5**: `/pins/actions.ts::updatePinAction` 확장만 사용 (PinPatch 타입 확장으로 자동 위임)
- **D6**: 중복 메시지 "이미 등록된 장소예요" 단일 유지 (분기 안 함)
- **D7**: `/map deletePinAction` try/catch + `revalidatePath("/pins")` 호출 (updatePinMemoAction 패턴)

---

## 구현 순서

```
1 [Must] B1: Backend 일괄 (Pin/Command/Service/DTO + ErrorType 메시지 + 테스트 15호출처)  (의존: 없음)
2 [Must] B2: Frontend API 클라이언트 (lib/api/pin.ts::PinPatch, lib/pin/constants.ts)      (의존: 1)
3 [Must] B3: /pins (PinEditDialog + PinListClient::applyPatch reducer)                     (의존: 2)
4 [Must] B4: /map 등록 instagramUrl (MemoTagPanelContent)                                  (의존: 2)
5 [Must] B5: /map 삭제 (actions.ts + PinPopup + MapClient + PinDeleteConfirm 재사용)       (의존: 2)
```

B3/B4/B5는 같은 base(B2) 위에서 병렬 가능.

---

## AC 17건 → 변경 위치 매핑

| AC | 충족 위치 |
|---|---|
| AC-1, AC-2 | #8 MemoTagPanelContent (양 경로 자동 커버) |
| AC-3 | #8 onChange 검증 |
| AC-4 | #8 `trim() || null` |
| AC-5 | #8 PLC_DUPLICATE_PIN 매핑 |
| AC-6 | #2/#3/#4 PinUpdateCommand + Service + DTO |
| AC-7 | #12 PinEditDialog + #13 PinListClient applyPatch |
| AC-8 | #12 canSave 검증 |
| AC-9 | #3 PinService — requireActiveMembership만 검증 |
| AC-10 | #12 canSave + 카운터 |
| AC-11 | #9 footer expanded일 때만 |
| AC-12 | #9 HLine + 우측 정렬 + pinNew color |
| AC-13 | #11 PinDeleteConfirm이 이미 placeName 표시 |
| AC-14 | #11 PinDeleteConfirm onCancel + PinPopup expanded 보존 |
| AC-15 | #11 applyOptimistic({remove}) + setSelectedPinId(null) |
| AC-16 | #11 5단계 흐름 (setDeleteErrorByPinId → setSelectedPinId 재mount → 자동 롤백) |
| AC-17 | #11 GROUP_NOT_MEMBER 매핑 + #9 deleteError 인라인 |

---

## 위험 요소

| # | 위험 | 가능성 | 완화 |
|---|---|---|---|
| R1 | `PinUpdateCommand.of()` 15호출처 컴파일 에러 | High | B1 단일 PR, IDE refactoring 일괄 변환 |
| R2 | reducer 시그니처 변경으로 tag/memo 회귀 | Medium | B5 내 일괄 변환 + 수동 검증 |
| R3 | mountedRef/trackedPinId + 삭제 흐름 충돌 | Low | 기존 패턴 유지, 5단계 흐름 명시 |
| R4 | `<dialog>` backdrop이 mapbox-gl 위에서 가려짐 | Low–Med | `top-layer` 사양상 안전, 수동 검증, 가려지면 z-index:1000 보강 |
| R5 | PIN_UPDATE_EMPTY 메시지 단언 깨짐 | Low | B1에서 grep 후 갱신 |
| R6 | instagramUrl 중복 시 soft-deleted 핀 충돌 | Medium | 기존 동작과 동일, OOS |
| R7 | `/map`이 `/pins/_components`를 import | Low | 의도된 재사용, 누적 시 components/ui로 승격 |

---

## 테스트 전략

### Backend
- `PinUpdateCommandTest`: placeName/address 검증 케이스 + 기존 호출 16→8-arg 갱신
- `PinServiceIT`: updatePin placeName 반영, 비-멤버 차단(BR-3 등록자 무관), 동시 수정
- `PinV1ControllerIntegrationTest`: PATCH placeName/address 케이스, 빈 문자열 분기(Q5)

### Frontend
- `MemoTagPanelContent.test.tsx`: instagramUrl 렌더(양 경로), `https://` 검증, payload 포함, 중복 메시지
- `PinEditDialog.test.tsx`: 필드 순서(Q4), 검증, 빈 칸 changed=false, instagramUrl 미렌더
- `PinListClient.test.tsx`: applyPatch placeName/address, 카드 즉시 반영
- `PinPopup.test.tsx`: 삭제 버튼 노출(BR-6), HLine+우측+pinNew(AC-12), onRequestDelete 호출, deleteError 인라인
- `MapClient.test.tsx` (가능 시): 통합 흐름(AC-15/16/17/13/14)
