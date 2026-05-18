# 설계: Phase 2.9 — 규모 대응 (최종)

## 0. 설계 규모
- **중형**
- 판단 근거: 단일 도메인(pin)의 단일 엔드포인트에 하위 호환을 유지하며 페이지네이션을 추가. 도메인/인프라/인터페이스 3개 레이어를 모두 건드리지만 변경 폭은 좁고 신규 비즈니스 규칙은 없음. FR-5 문서화는 코드 영향 없음.

## 1. 변경 범위 요약

**신규 파일: 2개**
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinListResult.java` — Service 페이지 모드 반환 타입
- `context/map/gl-migration-plan.md` — GL symbol layer 마이그레이션 사전 분석 (FR-5)

**수정 파일: 9개 (백엔드 7, 프론트 2)**
- `backend/.../domain/pin/PinService.java` — `listGroupPinsPaged` 신규 메서드
- `backend/.../domain/pin/PinRepository.java` — count + paged 조회 메서드 추가
- `backend/.../infrastructure/pin/PinJpaRepository.java` — Pageable 시그니처 + count 메서드
- `backend/.../infrastructure/pin/PinRepositoryImpl.java` — 신규 포트 구현
- `backend/.../interfaces/api/pin/PinV1Controller.java` — `page`/`size` 쿼리 파라미터 + 분기/검증
- `backend/.../interfaces/api/pin/PinV1Dto.java` — `PinListResponse` nullable 필드 + `@JsonInclude(NON_NULL)`
- `backend/.../interfaces/api/pin/PinV1ApiSpec.java` — Swagger 시그니처 갱신
- `backend/.../support/error/ErrorType.java` — `PIN_PAGE_SIZE_EXCEEDED`, `PIN_PAGE_PARAM_INVALID` 추가
- `frontend/src/lib/api/types.ts` — `PinListResponse`에 nullable 필드 추가
- `frontend/src/lib/api/pin.ts` — `listPins` 시그니처 선택적 확장 (FR-7)

**문서: 3건**
- `context/map/gl-migration-plan.md` 신규 작성 + 시점 disclaimer + status.md 역링크
- `context/map/architecture.md` 주제 문서 테이블에 한 줄 추가
- `context/map/status.md` "GL 마이그레이션" 줄에 신규 문서 cross-link 추가

**테스트: 2건**
- `backend/.../test/.../PinV1ControllerIntegrationTest.java` — AC-0~6 케이스 추가
- `backend/.../test/.../PinServiceIT.java` — 페이지네이션 통합 케이스 + 회귀 케이스 보강

## 2. 백엔드 설계

### 2.1 도메인 레이어 (PinService)

**현재**: `listGroupPins(Long userId, Long groupId, PinTag tagFilter)` → `List<PinSummary>` 반환.

**변경 전략 — "두 모드 메서드 + 신규 결과 타입":**
- 기존 `listGroupPins(...)`는 그대로 유지하여 `List<PinSummary>` 반환 (PinServiceIT의 기존 4개 테스트가 변경 없이 통과).
- 신규 오버로드 `listGroupPinsPaged(Long userId, Long groupId, PinTag tagFilter, int page, int size)` → `PinListResult` 반환.
- `PinListResult` (record, 도메인 패키지):
  ```java
  public record PinListResult(List<PinSummary> items, long totalCount, boolean hasNext) {}
  ```
- 멤버십 검증(`groupMemberService.requireActiveMembership`)은 동일하게 선행.
- 트랜잭션: `@Transactional(readOnly = true)`.
- `hasNext` 계산: `(long)(page + 1) * size < totalCount` (명시적 long 캐스팅으로 오버플로 방지).

**핵심 시그니처:**
```java
@Transactional(readOnly = true)
public List<PinSummary> listGroupPins(Long userId, Long groupId, PinTag tagFilter);  // 기존, 변경 없음

@Transactional(readOnly = true)
public PinListResult listGroupPinsPaged(Long userId, Long groupId, PinTag tagFilter, int page, int size);
```

### 2.2 인프라 레이어 (PinRepository / PinJpaRepository / PinRepositoryImpl)

**PinRepository 포트 (도메인 패키지)** — 신규 메서드 4개 추가:
```java
List<Pin> findActiveByGroupIdOrderByCreatedAtDesc(Long groupId, int page, int size);
List<Pin> findActiveByGroupIdAndTagOrderByCreatedAtDesc(Long groupId, PinTag tag, int page, int size);
long countActiveByGroupId(Long groupId);
long countActiveByGroupIdAndTag(Long groupId, PinTag tag);
```

**PinJpaRepository (Spring Data)** — Spring Data 추론 메서드명 컨벤션 유지:
- `List<Pin> findByGroupIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long groupId, Pageable pageable);`
- `List<Pin> findByGroupIdAndTagAndDeletedAtIsNullOrderByCreatedAtDesc(Long groupId, PinTag tag, Pageable pageable);`
- `long countByGroupIdAndDeletedAtIsNull(Long groupId);`
- `long countByGroupIdAndTagAndDeletedAtIsNull(Long groupId, PinTag tag);`

**PinRepositoryImpl** — 신규 포트 메서드를 `PageRequest.of(page, size)`로 변환해 위임:
```java
return jpaRepository.findByGroupIdAndDeletedAtIsNullOrderByCreatedAtDesc(groupId, PageRequest.of(page, size));
```

**Pageable을 도메인 포트로 도입하지 않은 근거 (CONSIDER 1):**
현재 코드베이스의 도메인 포트(`PinRepository`, `GroupMemberRepository` 등)는 의도적으로 Spring Framework 의존성을 받지 않고 순수 Java 타입만 노출하는 컨벤션을 유지하고 있다. `PinRepository`에 `Pageable`을 노출하면 (1) 도메인 레이어가 `org.springframework.data.domain.*`에 직접 의존하게 되어 헥사고날 경계가 침투되고, (2) `Pageable`은 `Sort`, `PageRequest`, `Unpaged` 등 PRD 범위(page/size만)를 벗어나는 부가 기능을 같이 끌고 들어와 도메인이 의도치 않은 변경 표면을 노출하게 된다. 또한 (3) `Page<T>` 반환을 채택하면 count 쿼리가 Spring Data 내부에서 자동 실행되어 "파라미터 미전달 시 count 미실행" 분기 제어를 명시적으로 할 수 없다. 단순 `int page, int size` + `long count*` 분리는 메서드 수가 4개로 증가하나, 도메인 순수성과 명시적 제어를 동시에 달성한다. `Pageable` 변환은 인프라 어댑터(`PinRepositoryImpl`) 내부에만 가둔다 — 기존 `GroupMemberRepositoryImpl:20`의 단발성 `PageRequest.of(0, 1)` 패턴과 일치한다.

**totalCount 쿼리 분리 vs Page 반환 — 분리 채택.** 근거:
- `Page<T>`로 받으면 count 쿼리가 항상 따라붙는다. 우리는 legacy 모드(count 미실행)와 페이지 모드(count 실행)를 명시 제어해야 한다.
- NFR-1(기존 인덱스 활용): `INDEX(group_id, deleted_at)`를 SELECT와 COUNT 두 쿼리가 동일하게 활용한다.

### 2.3 인터페이스 레이어 (PinV1Controller, PinV1Dto)

**PinV1Controller** — `@RequestParam` 시그니처를 박싱 `Integer + required=false`로 추가:

```java
@GetMapping("/{groupId}/pins")
public ApiResponse<PinV1Dto.PinListResponse> listPins(
        @AuthUser Long userId,
        @PathVariable Long groupId,
        @RequestParam(required = false) String tag,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
) { ... }
```

**분기 규칙 (Q3 확정):**
- `page == null && size == null` → 기존 `listGroupPins(...)` 호출 → `PinListResponse.from(list)` (legacy 모드)
- `page != null && size != null` → 검증 통과 후 `listGroupPinsPaged(...)` 호출 → `PinListResponse.fromPaged(result)` (페이지 모드)
- **그 외 (한쪽만 null, 한쪽만 전달)** → `CoreException(ErrorType.PIN_PAGE_PARAM_INVALID)` 400

> 룰렛 호출부(`MapClient.tsx:353` 파라미터 없음)는 자동으로 legacy 모드를 탄다 (FR-2/AC-7). 부분 전달은 사용자 의도가 모호한 케이스로 명시적으로 거부한다.

**검증 위치 — Controller 메서드 내부 명시 throw (Service 진입 전).** 근거:
- 코드베이스 컨벤션: `PlaceV1Controller.search:28-30`이 `if (keyword.isBlank()) throw new CoreException(...)` 패턴.
- `@Validated` Bean Validation은 컨트롤러 적용 사례 없음 + 에러 응답 포맷 일관성(NFR-3) 위험.

**검증 규칙 (분기 진입 전 순서대로):**
1. `(page == null) != (size == null)` → `PIN_PAGE_PARAM_INVALID` (부분 전달)
2. `page != null && size != null` 가지 진입 후:
   - `page < 0` → `PIN_PAGE_PARAM_INVALID`
   - `size <= 0` → `PIN_PAGE_PARAM_INVALID`
   - `size > 100` → `PIN_PAGE_SIZE_EXCEEDED`

**PinV1Dto.PinListResponse 구조 — 단일 record + 선택적 필드 + `@JsonInclude(NON_NULL)` (Q1 확정).** 근거:
- 기존 `ChatbotV1Dto.SkillResponse:23`, `BasicCard:56`, `Button:62`가 동일 패턴.
- 구현 첫 통합 테스트(AC-0)에서 ApiResponse 래퍼 안 직렬화 동작을 즉시 검증.
- 실패 시 두 DTO 분리 fallback (§7 위험 표 참조).

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PinListResponse(
        List<PinSummaryResponse> items,
        Long totalCount,    // legacy 모드에서 null → JSON 미포함
        Boolean hasNext     // legacy 모드에서 null → JSON 미포함
) {
    public static PinListResponse from(List<PinSummary> list) {
        return new PinListResponse(
                list.stream().map(PinSummaryResponse::from).toList(),
                null, null);
    }

    public static PinListResponse fromPaged(PinListResult result) {
        return new PinListResponse(
                result.items().stream().map(PinSummaryResponse::from).toList(),
                result.totalCount(),
                result.hasNext());
    }
}
```

> `items`는 빈 리스트라도 `null`이 아니므로 직렬화에 항상 포함됨. AC-1/AC-2 모두 `items`가 항상 유지된다는 PRD 보장에 부합.

**Long/Boolean 박싱 사용 이유**: `@JsonInclude(NON_NULL)`이 primitive에는 동작하지 않으므로 nullable이 필요한 두 필드는 반드시 박싱.

**PinV1ApiSpec.java** — Swagger 시그니처에 page/size 파라미터 추가 + description에 "둘 다 미전달 시 legacy, 둘 다 전달 시 페이지 모드, 부분 전달은 400" 명시.

### 2.4 에러 처리

`ErrorType.java`에 핀 섹션(Phase 4 주석 아래)에 추가:
```java
PIN_PAGE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "PIN_PAGE_SIZE_EXCEEDED", "한 번에 조회할 수 있는 핀은 100개까지입니다."),
PIN_PAGE_PARAM_INVALID(HttpStatus.BAD_REQUEST, "PIN_PAGE_PARAM_INVALID", "페이지 파라미터가 유효하지 않습니다."),
```
컨벤션 일치: 도메인 접두어(PIN_) + SCREAMING_SNAKE_CASE.

### 2.5 설정

**결정: 설정 외부화 없음.** Controller 상수로 표현:
```java
private static final int DEFAULT_PAGE_SIZE = 20;
private static final int MAX_PAGE_SIZE = 100;
```
`application.yml` 수정 없음.

### 2.6 하위 호환성 보장 전략

**구현 메커니즘:**
1. `page == null && size == null` → 기존 `listGroupPins(...)` → `from(List)` → `totalCount=null, hasNext=null`.
2. Jackson은 record 레벨 `@JsonInclude(NON_NULL)`을 인식하여 `null` 필드를 **키 자체 누락**.
3. 결과: legacy 호출 → `{"items": [...]}`, 페이지 호출 → `{"items": [...], "totalCount": N, "hasNext": true|false}`.

**룰렛(MapClient.tsx) 무영향 검증:**
- `MapClient.tsx:353` `apiFetch<PinListResponse>('/groups/${groupId}/pins')` — 파라미터 없음 → legacy 모드 → `pool = res.items` (line 356).
- AC-0 (§5 영역 A 첫 단언): `JsonNode.has("totalCount") == false` 명시 검증.

## 3. 프론트엔드 설계 (FR-7 채택, Q2 확정)

### 3.1 types.ts PinListResponse 확장

```ts
export interface PinListResponse {
  items: PinSummaryResponse[];
  totalCount?: number;
  hasNext?: boolean;
}
```

### 3.2 listPins API 클라이언트 (FR-7)

```ts
export interface ListPinsOptions {
  tag?: PinTag;
  page?: number;
  size?: number;
}

export async function listPins(
  groupId: number,
  optionsOrTag?: ListPinsOptions | PinTag,
): Promise<PinListResponse> {
  const options: ListPinsOptions =
    typeof optionsOrTag === "string" ? { tag: optionsOrTag } : (optionsOrTag ?? {});

  const params = new URLSearchParams();
  if (options.tag) params.set("tag", options.tag);
  if (options.page !== undefined) params.set("page", String(options.page));
  if (options.size !== undefined) params.set("size", String(options.size));

  const query = params.toString();
  return apiFetchServer<PinListResponse>(
    `/groups/${groupId}/pins${query ? `?${query}` : ""}`,
  );
}
```

`pins/page.tsx:15`의 `listPins(group.groupId)` 호출은 인자가 없으므로 변경 불필요 (AC-8).

## 4. 문서화 (FR-5)

### 4.1 GL 사전 분석 문서 위치 + 동기화 책임/유효기간 (CONSIDER 3)

**결정: 신규 `context/map/gl-migration-plan.md` + `architecture.md` 테이블 링크 + `status.md` 양방향 cross-link.**

**문서 헤더 disclaimer:**
```
> **작성 시점 스냅샷**: 본 문서는 {작성일} 기준 코드 상태를 분석한 것이다.
> 이후 `PinPopup.tsx`, `MapboxView.tsx`, `clusterer.ts` 변경 시 분석 결과가 무효화될 수 있다.
> 실제 GL 마이그레이션 시점에 본 문서를 재검증한 뒤 사용하라.
```

**양방향 추적:**
- `status.md:25` → `- DOM Marker → GL symbol layer 마이그레이션 (500핀 초과 시) — 사전 분석: [gl-migration-plan.md](./gl-migration-plan.md)`
- `gl-migration-plan.md` 하단에 "관련 문서: [status.md](./status.md)" 역링크.
- `architecture.md` 주제 문서 테이블에 행 추가: `| gl-migration-plan | DOM Marker → GL symbol layer 전환 시 변경 지점 사전 분석 (Phase 2.9) |`.

### 4.2 기록 3개 항목

**① PinPopup 좌표 계산**
- 현재: `PinPopup.tsx`의 `map.project([lng, lat])` (마커 DOM 위치 기반).
- 전환 후: `map.queryRenderedFeatures({ layers: ['pin-layer'], filter: ['==', ['id'], pinId] })` + `feature.geometry.coordinates`를 `map.project()`로 픽셀 변환. `map.on('move', ...)`에서 동일 변환 반복.

**② useOptimistic patch|remove 흐름**
- 현재: `MapClient.tsx:138` reducer → DOM Marker `el.innerHTML`/style 갱신/제거.
- 전환 후: `source.setData(newGeoJson)` 또는 `map.setFeatureState(featureRef, { tag: 'MEMORY' })` + layer `paint` 표현식 `['feature-state', 'tag']` 분기. remove는 features 배열 제외 후 `setData`.

**③ supercluster + GL layer 클러스터 클릭**
- 현재: `clusterer.ts`가 supercluster 인스턴스 보유, DOM Marker 직접 렌더, `el.addEventListener('click', ...)`.
- 전환 후: GL 내장 클러스터링(`cluster: true, clusterRadius: 50`). `map.on('click', 'cluster-layer', e => { ... source.getClusterExpansionZoom(...) })` + 단일 포인트는 `map.on('click', 'pin-layer', ...)`.

### 4.3 수용 기준 갱신 (AC-4)

AC-4를 다음 4 케이스 단언으로 확장:
- `page=-1` 단독 → 400 `PIN_PAGE_PARAM_INVALID`
- `size=0` 단독 → 400 `PIN_PAGE_PARAM_INVALID`
- **`page=0`만 전달 (size 누락)** → 400 `PIN_PAGE_PARAM_INVALID` (Q3 신규)
- **`size=20`만 전달 (page 누락)** → 400 `PIN_PAGE_PARAM_INVALID` (Q3 신규)

## 5. 테스트 전략

테스트는 **영역 A(백엔드 응답 구조 회귀)**, **영역 B(프론트 호출부 호환)**, **영역 C(문서)**로 명시 분리 (CONSIDER 4).

### 영역 A. 백엔드 응답 구조 회귀

**AC-0 (직렬화 회로 검증, Q1 + MUST-ADDRESS 1):** 본 Phase의 **첫 구현 검증 단언**.
- 케이스 1 (legacy): `GET ...` → `body.get("data").has("totalCount") == false`, `has("hasNext") == false`.
- 케이스 2 (페이지): `GET ...?page=0&size=20` → `body.get("data").has("totalCount") == true`, `has("hasNext") == true`.
- **실패 시**: 즉시 §7 fallback("두 DTO 분리")으로 전환.

**AC-1**: `GET ...?page=0&size=20` → `items.size() ≤ 20`, `totalCount`/`hasNext` 키 존재.

**AC-2**: `GET ...` (파라미터 없음) → `data.fieldNames()` iterator로 키 집합 `{"items"}` 단일 원소 단언.

**AC-3**: `size=101` → 400 + `meta.errorCode == "PIN_PAGE_SIZE_EXCEEDED"`.

**AC-4 (갱신, Q3):** 네 케이스 모두 400 + `meta.errorCode == "PIN_PAGE_PARAM_INVALID"`:
- `page=-1&size=20` / `page=0&size=0` / `page=0` 단독 / `size=20` 단독

**AC-5**: `tag=PLACE&page=0&size=10` → totalCount는 PLACE 전체 수, items.size ≤ 10.

**AC-6**: page=0/size=N과 page=1/size=N 응답 id 집합 disjoint + 합집합 크기가 totalCount와 일치.

**PinServiceIT:**
- `listGroupPinsPaged_returnsCorrectSliceAndTotal` — 25개 핀, page=0/size=10 → items 10, total 25, hasNext true.
- `listGroupPinsPaged_lastPage_hasNextFalse` — page=2/size=10 → items 5, hasNext false.
- `listGroupPinsPaged_withTagFilter` — count도 태그 필터 반영.
- 기존 4개 `listGroupPins_*` 그대로 통과.

### 영역 B. 프론트엔드 호출부 호환

- `frontend npm run build` (또는 `npm run typecheck`) 통과 — `ListPinsOptions` 유니온 시그니처 컴파일.
- `pins/page.tsx:15`의 `listPins(group.groupId)` 신규 시그니처에서도 컴파일 통과 (AC-8).
- `MapClient.tsx:353`의 `apiFetch<PinListResponse>('...')`는 백엔드 legacy 응답을 받으며 `res.items` 접근만 사용 — 영역 A의 AC-0 케이스 1로 응답 구조 보장.

### 영역 C. FR-5 문서 검증

자동화 불필요. PR 리뷰에서 수동 확인:
- `gl-migration-plan.md`의 3개 섹션 + disclaimer 헤더
- `architecture.md` 테이블에 신규 행
- `status.md`의 GL 마이그레이션 줄에 cross-link

## 6. 구현 순서 (9단계 / 5배치, CONSIDER 2 반영)

```
배치 B1 — 백엔드 기반 타입 (병렬 가능, 의존: 없음)
  단계 1: ErrorType 두 코드 추가 + PinListResult record 신규 생성 + PinRepository 포트에 4개 메서드 시그니처 추가
           → 담당 파일: support/error/ErrorType.java,
                       domain/pin/PinListResult.java (신규),
                       domain/pin/PinRepository.java
  단계 2: GL 마이그레이션 문서 패키지 — gl-migration-plan.md 신규 + architecture.md 테이블 행 + status.md cross-link
           → 담당 파일: context/map/gl-migration-plan.md (신규),
                       context/map/architecture.md,
                       context/map/status.md

배치 B2 — 인프라 어댑터 (직렬, 의존: 단계 1)
  단계 3: PinJpaRepository Pageable 시그니처 2개 + count 2개, PinRepositoryImpl에서 PageRequest 변환 위임
           → 담당 파일: infrastructure/pin/PinJpaRepository.java,
                       infrastructure/pin/PinRepositoryImpl.java

배치 B3 — 도메인 서비스 (직렬, 의존: 단계 3)
  단계 4: PinService.listGroupPinsPaged 추가 (hasNext 계산)
           → 담당 파일: domain/pin/PinService.java

배치 B4 — 인터페이스 레이어 (직렬, 의존: 단계 4)
  단계 5: PinV1Dto.PinListResponse nullable 필드 + @JsonInclude(NON_NULL) + fromPaged + PinV1ApiSpec 갱신
           → 담당 파일: interfaces/api/pin/PinV1Dto.java,
                       interfaces/api/pin/PinV1ApiSpec.java
  단계 6: PinV1Controller page/size 파라미터 + 부분 전달 포함 검증 + 분기
           → 담당 파일: interfaces/api/pin/PinV1Controller.java

배치 B5 — 프론트 + 테스트 + 빌드 (병렬 가능, 의존: 단계 6)
  단계 7: [FR-7] types.ts + pin.ts 시그니처 확장 (ListPinsOptions 유니온)
           → 담당 파일: frontend/src/lib/api/types.ts,
                       frontend/src/lib/api/pin.ts
  단계 8: 백엔드 테스트 — PinV1ControllerIntegrationTest AC-0~6 + PinServiceIT 페이지 케이스
           → 담당 파일: test/.../PinV1ControllerIntegrationTest.java,
                       test/.../PinServiceIT.java
  단계 9: 빌드 검증 — (cd ./ && ./gradlew build) + (cd frontend && npm run build)
```

**병렬 묶음**: (1, 2) / (7, 8). 단계 9는 7·8 완료 후.
**테스트와 구현 분리**: 단계 8(테스트)은 단계 6(컨트롤러 구현)에 의존, 항상 마지막.

## 7. 위험 및 대응

| 위험 | 가능성 | 대응 |
|------|--------|------|
| **Q1 검증 실패 — record + `@JsonInclude(NON_NULL)`이 ApiResponse 래퍼 안에서 키 누락 실패** | 매우 낮음 | **AC-0 (영역 A 첫 단언)에서 즉시 검출.** Fallback: PinListResponse 두 개로 분리(`PinListResponse(items)` / `PagedPinListResponse(items, totalCount, hasNext)`) + Controller 반환 타입 변경. 단계 5 직후 AC-0 결과로 결정 |
| 페이지 분기 규칙 부분 전달 누락 → 룰렛이 페이지 모드로 빠짐 | 매우 낮음 | Q3 결정으로 명시 400. AC-4 갱신 2건으로 보장 |
| Integer 오버플로로 hasNext 오계산 | 매우 낮음 | size ≤ 100 + page ≥ 0 차단. 명시적 long 캐스팅 |
| `listPins(groupId, "PLACE")` 기존 호출이 신규 시그니처와 충돌 | 낮음 | `string \| ListPinsOptions` 유니온 + typeof 분기. 영역 B 빌드로 보장 |
| GL 마이그레이션 문서 코드와 동기화 안 됨 | 중간 (시간 경과) | disclaimer 명시. status.md ↔ gl-migration-plan.md 양방향 cross-link. 실제 마이그레이션 시 재검증 |
| Spring Data `findBy...Pageable` 오버로드 추론 실패 | 매우 낮음 | 공식 지원 형태 |
| Pageable 미도입으로 향후 sort 요구 시 메서드 폭증 | 낮음 (현재) | 본 Phase는 sort 요구 없음. 미래 요구 발생 시 OrderBy enum 또는 검색 VO 진화 |

---

## 탐색 추가 항목

- `backend/.../interfaces/api/place/PlaceV1Controller.java:28-30` → 컨트롤러 명시 검증 컨벤션
- `backend/.../interfaces/api/chatbot/ChatbotV1Dto.java:15,22,55,61` → record + `@JsonInclude(NON_NULL)` 컨벤션
- `backend/.../infrastructure/group/GroupMemberJpaRepository.java:17` + `GroupMemberRepositoryImpl.java:20` → `Pageable` 인프라 단독 사용 사례
- `backend/.../interfaces/api/ApiResponse.java:1-32` + `ApiControllerAdvice.java` → 글로벌 에러 포맷 (NFR-3)
- `backend/.../test/.../PinV1ControllerIntegrationTest.java:30-99` → 통합 테스트 setup 패턴
- `context/map/architecture.md:40-43` → 주제 문서 테이블 위치
- `context/map/status.md:25` → cross-link 갱신 지점
- `frontend/src/app/pins/page.tsx:15` → AC-8 검증 지점
- `frontend/src/app/map/MapClient.tsx:353-358` → AC-7 회귀 검증 1순위
