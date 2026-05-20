# 설계: Phase 2.10 — 잔여 후속 통합 (Rev. 2)

## 0. 설계 규모

- **중형**
- 판단 근거: 단일 PR 통합이나 변경 폭은 좁다. 좌표 수정은 Provided 패턴 확장(단일 `coordinateProvided` 플래그) + `Pin.changeCoordinate` 도메인 메서드 1개 신설로 끝나고, 프론트엔드는 기존 picker UX(이미 존재하는 `CrosshairOverlay` + `AddPinPickerContent` 패턴)를 신규 시트 종류로 재구성하는 방식이 핵심이다. CSS·문서 작업은 모두 단순 변경이다. 신규 비즈니스 규칙은 0개, 신규 도메인 메서드 1개, 신규 컴포넌트 1개(picker), 신규 server action 1개.

## 1. 변경 범위 요약

**신규 파일: 2개**
- `frontend/src/app/map/_components/PinCoordinateEditPicker.tsx` — 좌표 수정 모드 전용 picker 패널 컨텐츠 (reverse geocoding 없음, "1단계 종결" 흐름)
- `context/map/mapbox-token-sop.md` — Mapbox 토큰 회전 SOP (FR-MAP-7, AC-11)

**수정 파일: 13개 (백엔드 5, 프론트 5, CSS 1, 문서 2)**

백엔드 (5):
- `backend/.../domain/pin/PinUpdateCommand.java` — `coordinateProvided` 단일 플래그 + `latitude`/`longitude` 2필드 추가 (8→11), `of` 검증 추가
- `backend/.../domain/pin/Pin.java` — `changeCoordinate(BigDecimal lat, BigDecimal lng)` 도메인 메서드 신설
- `backend/.../domain/pin/PinService.java` — `updatePin` 좌표 분기 1개 추가
- `backend/.../interfaces/api/pin/PinV1Dto.java` — `UpdatePinRequest`에 `BigDecimal latitude/longitude` 직접 필드 + `toCommand` 검증 확장
- `backend/.../interfaces/api/pin/PinV1ApiSpec.java` — `updatePin` description에 좌표 수정 명시

> **NOTE**: `ErrorType.PIN_COORDINATE_INVALID`는 이미 `ErrorType.java:58`에 등록되어 있음(`CreatePinRequest` 도입 시 선반영). 추가 작업 없음.

프론트엔드 (5):
- `frontend/src/lib/api/pin.ts` — `PinPatch` 인터페이스에 `latitude?/longitude?: number` 추가
- `frontend/src/app/map/actions.ts` — `updatePinCoordinateAction` 신규 server action 추가
- `frontend/src/app/map/_components/PinPopup.tsx` — ⋮ 메뉴에 "좌표 수정" 버튼 + `onRequestCoordinateEdit` 콜백 prop + `coordinateError` prop 추가
- `frontend/src/app/map/_components/types.ts` — (선택) 별도 변경 없음. `ActiveSheet`는 `MapClient` 내부 union이므로 거기서 확장
- `frontend/src/app/map/MapClient.tsx` — `coordinateEditTarget` 상태 + 진입/완료/취소/실패 핸들러 + `coordinateErrorByPinId` 맵 + `ActiveSheet` union 확장 + `flyTo` 카메라 제어

CSS (1):
- `frontend/src/app/globals.css:51` — `font-family: var(--font-sans), Arial, Helvetica, sans-serif` 한 줄 변경

문서 (2):
- `context/map/status.md` — Pretendard 항목 갱신, `mapbox-token-sop.md` cross-link 추가
- `context/pin/status.md` + `context/chatbot/status.md` — Phase 2.10 완료 항목 갱신 (PR 마지막 단계)

**테스트: 백엔드 2 (수정)**
- `backend/.../test/.../PinUpdateCommandTest.java` — 좌표 Nested 클래스 추가 + 기존 `of(...)` 호출 시그니처 패치(끝에 3개 인자 추가)
- `backend/.../test/.../PinV1ControllerIntegrationTest.java` — 좌표 수정 IT 케이스 5개 추가
- 프론트엔드: 별도 Vitest 신규 없음. 빌드 통과 + 기존 회귀로 갈음.

---

## 2. 백엔드 설계

### 2.1 도메인 레이어 (PinUpdateCommand — 단일 `coordinateProvided` 플래그)

**현재** (`PinUpdateCommand.java:15-24`): 8필드 record (memo/tag/placeName/address × Provided). `of` 정적 팩토리에서 미제공 검증 + 길이/blank 검증.

**변경 — 11필드 record + 좌표 단일 플래그 + 범위 검증:**

좌표는 의미상 분리 불가능한 단위(위도만 변경한 핀은 의미 없음)이므로 두 개의 Provided 플래그가 아니라 **단일 `coordinateProvided` + lat/lng 두 값**으로 묶는다. 이는 텍스트 4필드(각각 의미 독립)와 의도적으로 다른 매핑 컨벤션이다.

```java
public record PinUpdateCommand(
        boolean memoProvided, String memo,
        boolean tagProvided, PinTag tag,
        boolean placeNameProvided, String placeName,
        boolean addressProvided, String address,
        boolean coordinateProvided, BigDecimal latitude, BigDecimal longitude
) { ... }
```

**`of` 정적 팩토리 검증 추가:**
- "전부 미제공" 검사(`PIN_UPDATE_EMPTY`)는 `!coordinateProvided` 조건도 추가
- `coordinateProvided`가 true일 때:
  - lat 또는 lng가 null → `PIN_COORDINATE_INVALID` (단일 플래그라 XOR invariant는 불필요. DTO에서 두 값을 모두 검사한 뒤에만 true로 세팅)
  - 범위 검증: `latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || > 90` → `PIN_COORDINATE_INVALID`
  - 동일하게 `longitude` `-180 ~ 180` 검증

**핵심 시그니처:**
```java
public static PinUpdateCommand of(
        boolean memoProvided, String memo,
        boolean tagProvided, PinTag tag,
        boolean placeNameProvided, String placeName,
        boolean addressProvided, String address,
        boolean coordinateProvided, BigDecimal latitude, BigDecimal longitude);
```

> **소수점 7자리 제약**: V001 컬럼이 `DECIMAL(10,7)`이므로 JPA 저장 시 자동 라운딩. 명시 검증은 `CreatePinRequest.toCommand`(`PinV1Dto.java:106-113`)도 하지 않으므로 컨벤션상 생략. PRD의 "소수점 7자리 이하"는 범위 검증의 부수적 표현으로 해석.

### 2.2 도메인 엔티티 (Pin.changeCoordinate)

```java
/**
 * 좌표 변경 (Phase 2.10 FR-PIN-7). 범위 검증은 Command 레이어에서 수행하므로
 * 도메인은 단순 위임한다. lat/lng 는 non-null (호출 전 Command 에서 보장).
 */
public void changeCoordinate(BigDecimal latitude, BigDecimal longitude) {
    this.latitude = latitude;
    this.longitude = longitude;
}
```

`changeTag`, `changePlaceInfo` 컨벤션과 동일하게 검증은 Command 레이어에서, 도메인은 setter 역할만 한다.

### 2.3 서비스 레이어 (PinService.updatePin)

```java
@Transactional
public PinSummary updatePin(Long userId, Long groupId, Long pinId, PinUpdateCommand cmd) {
    groupMemberService.requireActiveMembership(userId, groupId);
    Pin pin = pinRepository.findActiveByIdAndGroupIdForUpdate(pinId, groupId)
            .orElseThrow(() -> new CoreException(ErrorType.PIN_NOT_FOUND));
    if (cmd.tagProvided()) { pin.changeTag(cmd.tag()); }
    if (cmd.memoProvided()) { ... }                          // 기존
    if (cmd.placeNameProvided()) { ... }
    else if (cmd.addressProvided()) { ... }                  // 기존
    // Phase 2.10: 좌표 분기
    if (cmd.coordinateProvided()) {
        pin.changeCoordinate(cmd.latitude(), cmd.longitude());
    }
    return PinSummary.from(pin);
}
```

### 2.4 인터페이스 레이어 (PinV1Dto.UpdatePinRequest — CreatePinRequest와 대칭 매핑)

좌표는 숫자 필드라 "빈 문자열" 분기가 의미 없으므로 `JsonNode`로 받을 동기가 없다. Jackson 기본 동작으로 "키 없음 ≡ JSON null ≡ Java null"로 통합.

```java
public record UpdatePinRequest(
        JsonNode memo, JsonNode tag,
        JsonNode placeName, JsonNode address,
        BigDecimal latitude, BigDecimal longitude          // 직접 매핑
) {
    public PinUpdateCommand toCommand() {
        // ... 기존 memo/tag/placeName/address 정규화 동일 ...

        // Phase 2.10: 좌표 단일 플래그 처리
        boolean coordinateProvided;
        if (latitude == null && longitude == null) {
            coordinateProvided = false;
        } else if (latitude != null && longitude != null) {
            coordinateProvided = true;
        } else {
            throw new CoreException(ErrorType.PIN_COORDINATE_INVALID);
        }

        return PinUpdateCommand.of(
                memoProvided, memoValue,
                tagProvided, tagValue,
                placeNameProvided, placeNameValue,
                addressProvided, addressValue,
                coordinateProvided, latitude, longitude);
    }
}
```

**JSON wire 타입 — JacksonConfig.WRITE_BIGDECIMAL_AS_PLAIN 사전 검증:**
- `backend/supports/jackson/.../JacksonConfig.java`에 `WRITE_BIGDECIMAL_AS_PLAIN` 활성. BigDecimal은 JSON `number`(plain decimal)로 직렬화.
- 역직렬화 시 Jackson은 JSON number를 BigDecimal 필드에 그대로 매핑. 정수 좌표(`"latitude": 37`)도 BigDecimal로 안전 변환.
- 잘못된 타입(문자열) 전달 시 Jackson이 400 응답 자동 매핑.
- 범위/null 검증은 `toCommand` 진입 후 수행 — Phase 2.8 `CreatePinRequest.toCommand:106-113`와 동일한 2단계 검증 컨벤션.

### 2.5 PinV1ApiSpec

description에 "placeName/address/coordinate(latitude+longitude 쌍) 부분 수정 지원. 좌표는 두 값이 함께 와야 하며 한쪽만 전달은 PIN_COORDINATE_INVALID(400). 범위는 latitude -90~90, longitude -180~180." 추가. 시그니처 그대로.

### 2.6 에러 처리

`ErrorType.PIN_COORDINATE_INVALID`(`ErrorType.java:58`)는 이미 등록됨. 본 Phase 추가 작업 없음.

---

## 3. 프론트엔드 설계

### 3.1 API 클라이언트 + 타입

**`PinPatch` 인터페이스 확장 (`pin.ts:9-14`):**

```typescript
export interface PinPatch {
  memo?: string;
  tag?: PinTag;
  placeName?: string;
  address?: string;
  latitude?: number;   // 신규
  longitude?: number;  // 신규
}
```

`updatePin` 함수 본문 변경 없음. `types.ts` 변경 없음(`latitude: number; longitude: number` 이미 wire 사실과 일치).

**BigDecimal ↔ number 변환 정책:**
- `WRITE_BIGDECIMAL_AS_PLAIN` 활성으로 wire는 plain `number`.
- 프론트엔드는 **항상 `number` 타입으로 송수신**. 기존 `Number(...)` 패턴은 안전망이지 형변환 책임이 아님.
- 요청 본문에 `number` 그대로 전송 → Jackson이 `BigDecimal`로 역직렬화.

### 3.2 Server Action

```typescript
export type UpdatePinCoordinateActionResult = CreatePinActionResult;

export async function updatePinCoordinateAction(
  groupId: number,
  pinId: number,
  latitude: number,
  longitude: number,
): Promise<UpdatePinCoordinateActionResult> {
  try {
    const data = await updatePin(groupId, pinId, { latitude, longitude });
    try { revalidatePath("/pins"); }
    catch (e) { console.error("revalidatePath('/pins') 실패 (좌표 변경은 성공)", e); }
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError) {
      return { ok: false, code: error.code, message: error.message };
    }
    throw error;
  }
}
```

`updatePinMemoAction`(`actions.ts:74-97`) 패턴 그대로. `/map`은 useOptimistic이 즉시 갱신하므로 `revalidatePath('/map')` 호출 안 함.

### 3.3 PinPopup ⋮ 메뉴 진입점 (M5: popup 닫고 진입)

```typescript
interface PinPopupProps {
  // ... 기존 prop ...
  onRequestCoordinateEdit: (pin: PinSummaryResponse) => void;
  coordinateError: string | null;
}
```

**하단 액션 영역**:
- HLine 아래 우측 정렬: "좌표 수정"(`colors.inkSoft`) + "삭제"(`colors.pinNew`)
- 클릭 시 `onRequestCoordinateEdit(pin)`. **PinPopup 자체는 좌표 변경에 무수정.**

**M5 결정**: 좌표 수정 진입 시 PinPopup을 닫는다(`selectedPinId = null`). picker UI 집중 + screenPos 깜빡임 원천 차단. 완료/취소/실패 시 `setSelectedPinId(pinId)` 재노출.

### 3.4 picker 재사용 — 별도 컴포넌트

**ActiveSheet union 확장:**

```typescript
type ActiveSheet =
  | "search" | "add" | "memo" | "roulette"
  | "coordinate-edit"   // 신규
  | null;
```

`activeSheetToTab`은 `"coordinate-edit"`을 null로 매핑(액션바 비강조).

**CrosshairOverlay 활성 조건**:
```typescript
{(activeSheet === "add" || activeSheet === "coordinate-edit") && <CrosshairOverlay />}
```

**PinCoordinateEditPicker 신규 (별도 컴포넌트 4가지 근거):**
1. 흐름 단계 수: 신규 등록은 picker → MemoTag 2단계 / 좌표 수정은 picker → 완료 1단계
2. reverse geocoding 리스크: 신규 등록 핵심이나 수정에 불필요, placeName 오변경 위험
3. 콜백 시그니처 다름: `{lng, lat, address, placeName}` vs `{lat, lng}`
4. 신규 등록 플로우 무영향 보장 (PRD 위험표)

```typescript
interface PinCoordinateEditPickerProps {
  map: mapboxgl.Map | null;
  mapboxToken: string;
  initialPin: PinSummaryResponse;
  onCancel: () => void;
  onConfirm: (latLng: { lat: number; lng: number }) => void;
}
```

구성 ~60줄: 좌표 추적 useEffect 1개 (`AddPinPickerContent.tsx:47-58` 패턴 복제), header(initialPin.placeName), body(좌표 표시 only, reverse geocoding 없음), footer("취소"/"완료").

### 3.5 MapClient — 좌표 수정 통합 흐름

**상태:**
```typescript
const [coordinateEditTarget, setCoordinateEditTarget] =
  useState<PinSummaryResponse | null>(null);
const [coordinateErrorByPinId, setCoordinateErrorByPinId] =
  useState<Record<number, string>>({});
```

**진입 핸들러** (M4 깜빡임 완화 + M5 popup 닫기):
```typescript
const handleRequestCoordinateEdit = useCallback((pin: PinSummaryResponse) => {
  setCoordinateErrorByPinId((prev) => {
    if (!(pin.id in prev)) return prev;
    const { [pin.id]: _omit, ...rest } = prev;
    return rest;
  });
  setSelectedPinId(null);
  setCoordinateEditTarget(pin);
  setActiveSheet("coordinate-edit");
  if (map) {
    map.flyTo({
      center: [Number(pin.longitude), Number(pin.latitude)],
      zoom: 16,
    });
  }
}, [map]);
```

**완료 핸들러:**
```typescript
const handleConfirmCoordinateEdit = useCallback((latLng: { lat: number; lng: number }) => {
  if (!coordinateEditTarget) return;
  const pinId = coordinateEditTarget.id;
  setActiveSheet(null);
  setCoordinateEditTarget(null);

  startOptimisticTransition(async () => {
    applyOptimistic({
      kind: "patch",
      pinId,
      patch: { latitude: latLng.lat, longitude: latLng.lng },
    });
    setSelectedPinId(pinId);

    const result = await updatePinCoordinateAction(
      groupId, pinId, latLng.lat, latLng.lng,
    );
    if (result.ok) {
      setPins((prev) => prev.map((p) => (p.id === pinId ? result.data : p)));
      if (pinsCacheRef.current) {
        pinsCacheRef.current.fetchedAt = Date.now();
      }
      return;
    }
    const message =
      result.code === "PIN_COORDINATE_INVALID" ? "좌표가 유효한 범위를 벗어났어요" :
      result.code === "GROUP_NOT_MEMBER" ? "권한이 없어요" :
      result.code === "PIN_NOT_FOUND" ? "이 핀을 찾을 수 없어요" :
      result.message;
    setCoordinateErrorByPinId((prev) => ({ ...prev, [pinId]: message }));
  });
}, [coordinateEditTarget, groupId, applyOptimistic]);
```

**취소 핸들러:**
```typescript
const handleCancelCoordinateEdit = useCallback(() => {
  const pinId = coordinateEditTarget?.id ?? null;
  setActiveSheet(null);
  setCoordinateEditTarget(null);
  if (pinId !== null) {
    setSelectedPinId(pinId);
  }
}, [coordinateEditTarget]);
```

**시트 렌더 분기:**
```typescript
} else if (activeSheet === "coordinate-edit" && coordinateEditTarget) {
  activePanel = renderPanel(
    "좌표 수정",
    <PinCoordinateEditPicker
      map={map}
      mapboxToken={mapboxToken}
      initialPin={coordinateEditTarget}
      onCancel={handleCancelCoordinateEdit}
      onConfirm={handleConfirmCoordinateEdit}
    />,
  );
}
```

**useOptimistic patch — number 일관:** reducer 수정 없음. `{ latitude: number, longitude: number }` 일관 사용.

**마커 깜빡임 시나리오 (M4):**
- 작은 좌표 이동: markerCacheRef hit → `setLngLat` → 매끄럽게 이동
- 큰 좌표 이동: 클러스터 캐시 미스로 깜빡일 가능성
- 완화: 진입 시 `flyTo(기존 핀, zoom=16)` → viewport 고정 → 일반 사용 시 깜빡임 없음

---

## 4. CSS 변경 (FR-MAP-8)

```css
body {
  background: var(--background);
  color: var(--foreground);
  font-family: var(--font-sans), Arial, Helvetica, sans-serif;  /* ← 1줄 수정 */
}
```

**Tailwind v4 `@theme` 충돌 사전 검증:**
- `globals.css:12-15` `@theme inline` — color 변수만, font 정의 없음
- `globals.css:19-39` `@theme` — color 토큰만, font 정의 없음
- 결론: `@theme`은 body font-family에 영향 없음

**AC-12 검증 절차 (필수):**
1. `npm run build` + dev 서버
2. DevTools → body → Computed → `font-family` 값 확인 → `var(--font-sans), ...`로 시작
3. Rendered font에 `Pretendard Variable` 표시 확인
4. Tailwind v4 preflight reset 시 fallback: `@layer base { body { font-family: var(--font-sans), Arial, Helvetica, sans-serif; } }`

---

## 5. 문서 변경

### 5.1 `context/map/mapbox-token-sop.md` 신규 작성

```markdown
# Mapbox 토큰 회전 SOP (운영자 가이드)

> 본 문서는 운영자가 Mapbox 액세스 토큰을 안전하게 교체하는 절차를 단계별로 안내합니다.
> Mapbox 대시보드 접근 권한 + 배포 플랫폼(Vercel 등) 환경 변수 권한이 필요합니다.

## 적용 시점
- 토큰 노출 의심 (PR 실수, 클라이언트 디버그 노출 등)
- 정기 로테이션 (분기 1회 권장)
- 신규 도메인 추가 (URL Restriction 확장 필요 시)

## 절차

### 1) 신규 토큰 발급
- Mapbox 대시보드(https://account.mapbox.com/access-tokens/) 진입
- "Create a token" 클릭
- Public scope: `styles:read`, `fonts:read`, `tilesets:read`, `datasets:read` 등 운영 필수 스코프만 선택
- 토큰명 규칙: `<운영자가 채울 형식, 예: wherewego-{env}-{yyyymmdd}>`

### 2) URL Restriction 설정
- 발급 직후 "URL restrictions" 섹션에 운영 도메인 추가
  - `<운영 도메인>/*`
  - `<preview 환경 와일드카드>/*`
- restriction 미설정 토큰은 발급 24시간 이내 폐기 권장

### 3) 환경 변수 갱신
- 배포 플랫폼 대시보드 → Project Settings → Environment Variables
- `NEXT_PUBLIC_MAPBOX_TOKEN` 값을 신규 토큰으로 교체
- `NEXT_PUBLIC_MAPBOX_STYLE_URL`은 변경 없음
- production / preview / development 환경 각각에 적용

### 4) 배포 트리거
- main 브랜치 재배포
- 배포 완료 후 운영 도메인에서 지도 렌더링 확인
- (선택) Mapbox 대시보드 "Statistics"에서 신규 토큰 호출량 증가 + 구 토큰 호출량 감소 확인

### 5) 구 토큰 폐기
- 신규 토큰 정상 동작 24시간 모니터링 후, 구 토큰 "Delete" 처리

## 롤백
- 신규 토큰 적용 후 401/403 발생 시 환경 변수를 구 토큰으로 즉시 되돌리고 재배포
- 구 토큰을 폐기하지 않은 상태에서만 가능 (5단계 전이 안전)

## 관련 문서
- [status.md](./status.md)
- 환경 변수 샘플: `frontend/.env.local.example`
```

NFR-4 충족. 토큰명 규칙 + URL Restriction 예시는 placeholder 유지 + 운영자 채움 안내 (실제 운영 도메인 미노출).

### 5.2 `context/map/status.md` 갱신

`status.md:23-24` 두 줄을 다음으로 교체:
```markdown
- **Phase 2.10 완료**: Pretendard 폰트 self-host (`public/fonts/PretendardVariable.woff2` + `next/font/local`로 `--font-sans` 주입 완료) + `globals.css` body `font-family`를 `var(--font-sans)` 토큰으로 연결 완료 — [PR-LINK]
- **Phase 2.10 완료**: Mapbox 토큰 회전 SOP 운영자 가이드 — [mapbox-token-sop.md](./mapbox-token-sop.md) — [PR-LINK]
```

### 5.3 pin/chatbot status.md 갱신

**`context/pin/status.md:27`**:
```markdown
- **Phase 2.10 완료**: 핀 장소 좌표 수정 — `PinUpdateCommand` 단일 `coordinateProvided` 플래그 + `latitude/longitude` BigDecimal 2필드 추가(8→11), `Pin.changeCoordinate` 도메인 메서드 신설, `PinV1Dto.UpdatePinRequest` 좌표를 BigDecimal 직접 매핑(CreatePinRequest 와 대칭), `PinPopup` ⋮ "좌표 수정" 진입점 + 신규 `PinCoordinateEditPicker` 시트(기존 picker 흐름 무영향), `useOptimistic patch` 좌표 반영(reducer 변경 없음), 진입 시 `flyTo` 로 마커 깜빡임 최소화. 삭제 핀 복원 기능은 제외(사용자 결정) — [PR-LINK]
```

**`context/chatbot/status.md:28`**:
```markdown
- **Phase 2.10 완료**: 카카오 i 오픈빌더 PLACE_SELECTION 시나리오 설정(빌더 콘솔 운영 작업, 코드 변경 없음) + 카카오톡 실기기 1회 수동 E2E 검증(PR 본문 절차/결과 기록) + Phase 2.7 IT 5케이스 회귀 통과 — [PR-LINK]
```

---

## 6. 의존성 및 영향도

**새로 추가할 의존성**: 없음.

**기존 코드 영향:**
- `PinUpdateCommand` 시그니처 변경(8→11) — 호출부 2곳: `UpdatePinRequest.toCommand`(단계 3) + `PinUpdateCommandTest`(단계 4)
- `Pin.changeCoordinate` 신설 — 기존 호출 없음
- `PinService.updatePin` — 좌표 분기 1개 추가, 기존 분기 영향 없음
- `PinPopup` ⋮ 메뉴 — "좌표 수정" 버튼 "삭제" 옆 추가 (HLine + 우측 정렬 유지)
- `MapClient` `ActiveSheet` union 확장 — `activeSheetToTab`은 `"coordinate-edit"` null 매핑

**하위 호환성:**
- API: `PATCH /pins/{pinId}`에 좌표 미전달 시 좌표 미변경 (Provided + 키 없음 ≡ null 통합). 기존 클라이언트 영향 없음
- `useOptimistic patch`: `Partial<PinSummaryResponse>` 자동 허용. 기존 호출 영향 없음
- 응답 wire 포맷: 기존과 동일

**Future improvement (범위 외):** `useMapCenterCoordinate(map)` hook 추출.

---

## 7. 구현 순서 (5배치 / 8단계)

```
배치 B1 — 백엔드 도메인 (병렬 가능)
  단계 1: PinUpdateCommand 11필드 + of 좌표 검증
  단계 2: Pin.changeCoordinate 도메인 메서드

배치 B2 — 백엔드 서비스/DTO (직렬, 의존 1·2)
  단계 3: PinService.updatePin 분기 + UpdatePinRequest BigDecimal 직접 필드 +
          toCommand XOR/null 검증 + PinV1ApiSpec description

배치 B3 — 백엔드 테스트 (의존 3)
  단계 4: PinUpdateCommandTest 좌표 Nested + 기존 of(...) 시그니처 패치 +
          PinV1ControllerIntegrationTest 좌표 IT 5케이스

배치 B4 — 프론트엔드 + CSS (병렬 가능, 의존 3)
  단계 5: pin.ts PinPatch + actions.ts updatePinCoordinateAction
  단계 6: PinCoordinateEditPicker 신규 + PinPopup ⋮ 좌표 수정 + MapClient 통합
  단계 7: globals.css body font-family + DevTools 검증

배치 B5 — 문서 + 빌드 검증 (의존 4·5·6·7)
  단계 8a: mapbox-token-sop.md 신규 + status.md 3개 갱신
  단계 8b: (cd backend && ./gradlew build) + (cd frontend && npm run build && npm test) +
           카카오 빌더 콘솔 설정 + 실기기 E2E
```

**병렬 묶음**: (1, 2) / (5, 7) — 단계 6은 5에 의존.

---

## 8. 테스트 전략

### 영역 A — 백엔드

**PinUpdateCommandTest 신규 Nested `CoordinateValidation`:**
- `coordinateProvidedWithLatNull_throwsPinCoordinateInvalid`
- `coordinateProvidedWithLngNull_throwsPinCoordinateInvalid`
- `latitudeOutOfRange_throwsPinCoordinateInvalid` (91, -91)
- `longitudeOutOfRange_throwsPinCoordinateInvalid` (181, -181)
- `coordinateProvidedInRange_passes` (37.5665, 126.9780)
- `coordinateAndMemoTogether_buildsCommand` 복합 수정

기존 `of(...)` 호출 일괄 패치 (끝에 `false, null, null` 추가).

**PinV1ControllerIntegrationTest 신규 5케이스:**
1. `updatePin_withCoordinates_changesLocationAndKeepsOtherFields` (AC-1, AC-5)
2. `updatePin_coordinateOutOfRange_returns400` (AC-2)
3. `updatePin_byNonMember_returns403` (AC-3)
4. `updatePin_withoutCoordinates_keepsCoordinates` (AC-6)
5. `updatePin_latitudeOnly_returns400` (XOR)

### 영역 B — 프론트엔드

- `npm run build` + `npm test` 회귀
- 수동: ⋮ → popup 닫힘 → picker + flyTo → 새 위치 → 완료 → 마커 이동 + popup 재노출 (AC-4)
- 수동: 다른 필드 불변 (AC-5)
- 수동: DevTools body font-family + Rendered font Pretendard (AC-12)

### 영역 C — 회귀 (FR-PIN-9)

- Phase 2.8 AC 1~17 / Phase 2.9 페이지네이션 / useOptimistic 흐름

### 영역 D — PLACE_SELECTION (FR-BOT-10)

- 자동화: 신규 IT 없음, Phase 2.7 IT 5케이스 회귀
- 수동: 빌더 콘솔 설정 + 실기기 E2E + PR 본문 기록

---

## 9. 위험 및 대응

| 위험 | 가능성 | 대응 |
|------|--------|------|
| PinUpdateCommand 시그니처 변경(8→11) 호출부 컴파일 깨짐 | 중간 | 호출부 2곳, 단계 3·4 일괄 패치, 컴파일 에러로 누락 감지 |
| 좌표 한 쪽만 전달 시 의도 모호 | 낮음 | DTO + Command 양쪽 `PIN_COORDINATE_INVALID` 차단 |
| BigDecimal ↔ number 정밀도 손실 | 매우 낮음 | WRITE_BIGDECIMAL_AS_PLAIN, BigDecimal(10,7) ≤ JS double precision |
| picker 재진입 시 신규 등록 상태 충돌 | 중간 | ActiveSheet 분리, AddPinPickerContent 무변경 |
| 큰 좌표 이동 시 supercluster 캐시 미스 깜빡임 (M4) | 낮음 | 진입 시 flyTo viewport 고정 → 일반 사용 시 깜빡임 없음. MVP 수용 |
| PinPopup screenPos 한 프레임 null 깜빡임 (M5) | 낮음 | 진입 시 popup 닫음 + 완료/취소/실패 후 재노출 |
| globals.css 폰트 변경 시각적 회귀 | 낮음 | Pretendard 의도된 토큰 폰트. AC-12 DevTools 검증 |
| Tailwind v4 preflight reset 가능성 | 낮음 | @theme font 정의 없음. reset 시 @layer base 보강 fallback |
| 마커 인스턴스 캐시 재생성 | 낮음 | pinId 캐시 hit setLngLat 재사용 |
| 카카오 빌더 콘솔 권한 부재 | 낮음 | 권한 사전 확인, 부재 시 별도 운영 작업 분리 |
| 운영 작업 PR 묶음 머지 지연 (CONSIDER 3) | 중간 | 권한 사전 확보, 실패 시 FR-BOT-9 분리 가능 명시 |
| 동시 좌표 수정 last-write-wins (CONSIDER 4) | 낮음 | 비관 락으로 DB race 차단, last-write-wins 수용 (MVP 2인) |
| 단일 PR 통합 리뷰 부담 | 중간 | PR 본문 도메인별 섹션 분리 |

---

## 확인 사항

추가 확인 사항 없음. 설계가 완료되었습니다.

사용자 답변 반영:
- Q1 카메라 동작 → flyTo(핀 위치, zoom=16) (Recommended 안)
- Q2 실패 인라인 에러 → coordinateErrorByPinId 맵 + PinPopup coordinateError prop (Recommended 안)
- Q3 SOP 실값 → placeholder 유지 + 운영자 채움 안내 (Recommended 안)
