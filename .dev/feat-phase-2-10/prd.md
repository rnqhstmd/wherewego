# PRD: Phase 2.10 — 잔여 후속 통합

## 1. 배경 (Why)

### 현재 제품 상태

**① 핀 장소 좌표 수정**

Phase 2.8에서 핀 도메인 UX 완성을 진행하면서, 좌표 수정(지도 picker 재사용)은 구현 범위에서 의도적으로 분리되었다. 현재 상태는 다음과 같다.

- `PinUpdateCommand`는 8개 필드(memo/tag/placeName/address × Provided 패턴)만 지원한다. 좌표(`latitude`/`longitude`) 수정은 API 수준에서 불가능하다 (`PinUpdateCommand.java`에서 확인).
- `Pin.latitude`/`Pin.longitude`는 `BigDecimal(10,7) NOT NULL` 컬럼이다 (V001 스키마에서 확인).
- 지도 화면 `PinPopup` ⋮ 메뉴에는 삭제 액션이 있으나, 좌표 수정 진입점은 없다.

**② 카카오 i 오픈빌더 PLACE_SELECTION 동작 검증**

Phase 2.6 PR-C에서 이월된 항목이다. 코드 구현은 이미 완료된 상태다.

- `PlaceSelectionHandler.java`에 `clientExtra.placeId` 우선 + `params.placeId` 폴백 로직이 구현되어 있다.
- Phase 2.7에서 PLACE_SELECTION E2E IT 5케이스(정상/만료/미연동/그룹 미가입/중복 핀)가 보강 완료되었다 (`context/chatbot/status.md`에서 확인).
- 미완료 항목은 **카카오 i 오픈빌더 콘솔에서의 시나리오 설정**: 버튼 `action="message"` + `extra.placeId` 필드 매핑이 실제로 동작하는지 실기기로 확인하는 E2E 검증이다.

**③ Pretendard 폰트 연결 미완료 + 문서 정합화**

`context/map/status.md`는 "Phase 2.10 예정: Pretendard 폰트 self-host 전환 (현재 CDN)"이라고 기재하고 있다. 실제 코드 점검 결과는 다음과 같다.

- **완료된 부분**: `frontend/public/fonts/PretendardVariable.woff2`(Pretendard Variable v1.3.9, ~2MB)가 존재하며, `layout.tsx`에서 `next/font/local`로 `--font-sans` CSS 변수에 주입하고 있다. self-host 바이너리 파일 배치와 변수 주입은 완료 상태다.
- **HIGH 문제**: `frontend/src/app/globals.css:51`에 `body { font-family: Arial, Helvetica, sans-serif }`가 하드코딩되어 있어, `--font-sans` 변수가 body에 실제로 적용되지 않는다. 디자인 토큰(`tokens.ts`의 `fonts.sans = "var(--font-sans)"`) 의도와 충돌하며, 사용자에게는 Pretendard가 아닌 Arial이 표시된다.
- **MEDIUM**: Noto Serif KR, Gowun Batang, JetBrains Mono 3종은 `next/font/google` CDN 로딩이 유지 중이다. 이는 이번 Phase 제외 범위다.
- **LOW**: `layout.tsx`에 `Geist_Mono`가 추가 로딩되나("다른 페이지 호환" 주석) 실사용처가 불명확하다. 이는 이번 Phase 제외 범위다.

결론: Pretendard 파일은 존재하지만 body에 실제로 연결되지 않은 상태다. `globals.css` body 폰트 수정이 이번 Phase의 실질 코드 작업이다.

### 변경이 필요한 이유

Phase 2.8·2.9가 완료되면서 MVP 기능의 핵심은 동작하고 있다. Phase 2.10은 MVP 운영 단계에서 남겨둔 세 가지 잔여 항목을 단일 PR로 통합 정리하여, 이후 Phase 3.0(Group N인 확장 등 비즈니스 정책 확장)으로 넘어가기 전에 기술 부채를 해소하는 것이 목적이다.

---

## 2. 요구사항 (What)

### 2.1 핀 도메인 [FR-PIN]

기존 FR-PIN-1~6에 이어 번호를 부여한다.

**[Must] FR-PIN-7: 핀 장소 좌표 수정 — 백엔드 API 지원**

`PATCH /api/v1/groups/{groupId}/pins/{pinId}` 엔드포인트가 `latitude`/`longitude` 필드를 수신하여 핀의 지도 좌표를 변경할 수 있다. `PinUpdateCommand`에 좌표 필드 2개(및 Provided 패턴)가 추가된다. 좌표 유효성(위도 -90~90, 경도 -180~180, 소수점 7자리 이하)을 서버에서 검증하고, 범위 초과 시 `PIN_COORDINATE_INVALID` 에러를 반환한다. 활성 그룹 멤버만 수정 가능하다(기존 `requireActiveMembership` 권한 검증 재사용). 기존 `PATCH` 요청에 좌표 필드를 전달하지 않으면 좌표는 변경되지 않는다(Provided 패턴 일관 적용).

**[Must] FR-PIN-8: 핀 장소 좌표 수정 — 지도 picker UX**

지도 화면 `PinPopup` ⋮ 메뉴에 "좌표 수정" 항목이 추가된다. 사용자가 해당 항목을 선택하면 기존 지도 picker(핀 직접 등록 시 사용하는 십자선 좌표 지정 UI)를 재사용하여 새 좌표를 선택할 수 있다. 새 좌표를 확인하면 `PATCH` 호출 후 지도 화면의 마커 위치가 즉시 갱신된다(`useOptimistic` 기존 `patch` 리듀서 확장). 실패 시 자동 롤백된다. `/pins` 편집 다이얼로그는 텍스트 필드(placeName/address/memo/tag) 편집만 담당하며, 좌표 수정 진입점을 추가하지 않는다.

**[Must] FR-PIN-9: Phase 2.8~2.9 핀 기능 회귀 없음**

`useOptimistic` `patch|remove` 리듀서, supercluster 클러스터링, `PinPopup` 말풍선 좌표 계산(`map.project`), `PinDot`/`PinTag`/`SpeechBubblePopup` 디자인 토큰, 페이지네이션 API(Phase 2.9 FR-1~FR-4), Phase 2.8 AC 1~17건 — 모두 유지된다.

### 2.2 챗봇 도메인 [FR-BOT]

기존 FR-BOT-1~8에 이어 번호를 부여한다.

**[Must] FR-BOT-9: PLACE_SELECTION 버튼 카카오 빌더 시나리오 설정 완료**

카카오 i 오픈빌더 콘솔에서 PLACE_SELECTION 버튼의 `action="message"` + `extra.placeId` 매핑이 실제로 설정된다. 이 설정은 운영 작업(빌더 콘솔 접근)이며, 코드 변경은 없다.

**[Must] FR-BOT-10: PLACE_SELECTION E2E 동작 검증**

Phase 2.7에서 작성된 IT 5케이스(정상/만료/미연동/그룹 미가입/중복 핀) 회귀 통과를 확인하고, 카카오톡 실기기에서 1회 수동 E2E 검증을 수행한다. 실기기 검증은 복수 장소 검색 결과 리스트 카드에서 장소 선택 버튼을 눌렀을 때 `PlaceSelectionHandler`가 `extra.placeId`를 정상 수신하고 핀이 등록되는 전체 흐름을 확인하는 것으로 한정한다. 검증 절차와 결과는 PR 본문에 기록한다. 추가 자동화 IT 케이스는 작성하지 않는다.

### 2.3 지도/인프라 도메인 [FR-MAP]

기존 FR-MAP-1~5에 이어 번호를 부여한다.

**[Must] FR-MAP-6: `context/map/status.md` 문서 정합화**

"Phase 2.10 예정: Pretendard 폰트 self-host 전환 (현재 CDN)" 항목을 실제 상태(self-host 바이너리 배치 및 `next/font/local` 주입 완료, body 연결은 FR-MAP-8로 처리)로 갱신하여 코드 사실과 문서를 일치시킨다.

**[Should] FR-MAP-7: Mapbox 토큰 회전 SOP 운영자 가이드 작성**

Mapbox 액세스 토큰을 안전하게 교체하는 절차를 운영자가 따를 수 있도록 `context/map/mapbox-token-sop.md`에 문서화한다. 포함 내용: 토큰 발급 위치(Mapbox 대시보드), URL Restriction 설정, 환경 변수 갱신 방법, 배포 트리거 절차. 문서 작성 완료 후 `context/map/status.md`에서 해당 파일로 cross-link를 추가한다.

**[Must] FR-MAP-8: `globals.css` body 폰트를 디자인 토큰(`--font-sans`)으로 연결**

`frontend/src/app/globals.css`의 `body { font-family: Arial, Helvetica, sans-serif }` 하드코딩을 `font-family: var(--font-sans), Arial, Helvetica, sans-serif`로 변경하여, `layout.tsx`에서 주입된 Pretendard(`--font-sans`)가 실제 body에 적용되도록 한다. Arial은 폴백으로 유지한다.

---

## 3. 범위 (Scope)

### 3.1 포함

| 항목 | 근거 |
|------|------|
| 핀 좌표 수정 백엔드 API (`PinUpdateCommand` 좌표 필드 확장 + `PATCH` 처리) | FR-PIN-7 |
| 핀 좌표 수정 지도 picker UX (`PinPopup` ⋮ 메뉴 진입, 기존 picker 재사용) | FR-PIN-8 |
| 카카오 i 오픈빌더 PLACE_SELECTION 시나리오 설정 (운영 작업, 코드 변경 없음) | FR-BOT-9 |
| PLACE_SELECTION Phase 2.7 IT 5케이스 회귀 통과 + 실기기 1회 수동 E2E 검증 | FR-BOT-10 |
| `context/map/status.md` Pretendard 항목 문서 정합화 | FR-MAP-6 |
| Mapbox 토큰 회전 SOP (`context/map/mapbox-token-sop.md` + cross-link) | FR-MAP-7 |
| `globals.css` body 폰트 `var(--font-sans)` 연결 | FR-MAP-8 |
| Phase 2.8~2.9 기능 회귀 없음 확인 | FR-PIN-9 |

**단일 PR 정책**: 위 3개 항목(① pin 좌표 수정, ② chatbot 검증, ③ map 문서/SOP/폰트 연결)을 하나의 PR `feat/phase-2-10`으로 통합한다. 각 항목이 기능적으로 독립적이므로 분리가 더 자연스럽다는 반론이 있으나, 사용자가 단일 PR 통합을 의식적으로 선택하였으므로 이를 따른다 (관련 위험은 6절 참조).

### 3.2 제외

| 항목 | 이유 |
|------|------|
| 삭제 핀 복원 기능 | 사용자 결정으로 이번 Phase에서 제거. 필요 시 별도 작업 |
| Noto Serif KR, Gowun Batang, JetBrains Mono Google Fonts CDN → self-host 전환 | 별도 Phase로 분리. 이번 Phase ROI 대비 범위 초과 |
| `Geist_Mono` 실사용처 정리 | 실사용처 미확인 상태. 별도 Phase에서 분리 검토 |
| Mapbox URL Restriction 실제 적용 (대시보드 직접 설정) | SOP 가이드 절차 내 설명 포함이나, 실제 Restriction 적용은 운영자 직접 수행 |
| `/pins` UI 페이지네이션 컨트롤 | Phase 2.9에서 명시적으로 제외. 필요 시 별도 작업 |
| DOM Marker → GL symbol layer 마이그레이션 | 그룹 핀 500건 미도달 시 Phase 2.9 결정 유지 |
| Phase 3.0 항목 (Group N인 확장, 재가입 정책 등) | 별도 PRD |

---

## 4. 수용 기준 (Acceptance Criteria)

### 핀 좌표 수정 [FR-PIN-7, FR-PIN-8]

**AC-1**: `PATCH /api/v1/groups/{groupId}/pins/{pinId}` 요청에 `latitude: 37.5665`, `longitude: 126.9780`을 포함하면, 핀의 지도 좌표가 해당 값으로 변경되고 지도 화면의 마커 위치가 즉시 이동한다 → [FR-PIN-7, FR-PIN-8]

**AC-2**: 위도가 -90~90 범위를 벗어나거나 경도가 -180~180 범위를 벗어난 좌표로 수정 요청 시, 400 응답과 함께 `PIN_COORDINATE_INVALID` 에러 코드가 반환된다 → [FR-PIN-7]

**AC-3**: 비활성 그룹 멤버(탈퇴자)가 좌표 수정 요청 시 권한 오류가 반환된다 → [FR-PIN-7]

**AC-4**: 지도 화면 `PinPopup` ⋮ 메뉴에서 "좌표 수정"을 선택하면 기존 picker UI가 활성화되고, 새 좌표를 확인하면 서버 응답 전에 마커가 즉시 이동하며, 요청 실패 시 원래 위치로 자동 롤백된다 → [FR-PIN-8]

**AC-5**: 좌표 수정이 성공한 후 기존 `placeName`, `address`, `memo`, `tag` 값은 변경되지 않는다 → [FR-PIN-7]

**AC-6**: 기존 `PATCH` 요청에 좌표 필드를 포함하지 않으면 핀의 좌표가 변경되지 않는다 → [FR-PIN-7]

### PLACE_SELECTION E2E [FR-BOT-9, FR-BOT-10]

**AC-7**: 카카오 i 오픈빌더 콘솔에서 PLACE_SELECTION 시나리오의 버튼 `action`이 `"message"`, `extra.placeId`가 올바른 캐시 키로 설정된 상태가 확인된다 → [FR-BOT-9]

**AC-8**: 카카오톡 실기기에서 복수 장소 검색 결과 중 하나를 선택하면, 핀이 정상 등록되고 완료 알림이 반환되는 전체 흐름이 확인된다. 검증 절차와 결과가 PR 본문에 기록된다 → [FR-BOT-10]

**AC-9**: Phase 2.7에서 작성된 IT 5케이스(정상/만료/미연동/그룹 미가입/중복 핀)가 이번 변경 후에도 모두 통과한다 → [FR-BOT-10]

### 문서 정합화 + SOP + 폰트 연결 [FR-MAP-6, FR-MAP-7, FR-MAP-8]

**AC-10**: `context/map/status.md`에서 "Phase 2.10 예정: Pretendard 폰트 self-host 전환 (현재 CDN)" 항목이 실제 완료 상태(self-host 바이너리 배치 및 `next/font/local` 주입 완료)로 갱신된다 → [FR-MAP-6]

**AC-11**: `context/map/mapbox-token-sop.md`가 신규 작성되며, 토큰 발급·URL Restriction 설정·환경 변수 갱신·배포 트리거 절차를 포함한다. `context/map/status.md`에서 해당 파일로 cross-link가 추가된다 → [FR-MAP-7]

**AC-12**: `frontend/src/app/globals.css`의 body `font-family`가 `var(--font-sans), Arial, Helvetica, sans-serif`로 변경되어, 브라우저에서 body 텍스트에 Pretendard가 실제로 렌더된다 → [FR-MAP-8]

### 회귀 방지 [FR-PIN-9]

**AC-13**: Phase 2.8 AC 1~17건이 이번 변경 후에도 모두 충족된다 → [FR-PIN-9]

**AC-14**: Phase 2.9 페이지네이션 API(`page`/`size` + `totalCount`/`hasNext` 선택 응답, 하위 호환)가 이번 변경 후에도 정상 동작한다 → [FR-PIN-9]

**AC-15**: `useOptimistic` `patch|remove` 리듀서, supercluster 클러스터링, `PinPopup` 말풍선 좌표 계산이 이번 변경 후에도 정상 동작한다 → [FR-PIN-9]

---

## 5. 비기능 요구사항 (NFR)

**[Must] NFR-1: 회귀 안전성**
`PinUpdateCommand` 좌표 필드 확장은 기존 `updatePin` 경로에 영향을 주지 않는다. 좌표 필드 미전달 시 Provided 패턴에 의해 좌표는 변경되지 않으며, 기존 텍스트 필드 수정 흐름도 그대로 유지된다.

**[Should] NFR-2: 좌표 수정 낙관적 UI 일관성**
좌표 수정 성공/실패 UX가 기존 tag 수정, 삭제 액션의 `useOptimistic` 패턴과 동일한 방식으로 동작한다. 사용자가 다른 수정 액션과 다른 피드백 패턴을 경험하지 않는다.

**[Should] NFR-3: 에러 응답 일관성**
`PIN_COORDINATE_INVALID` 에러 코드가 기존 `CoreException` / `ApiResponse` 오류 포맷을 따른다 (`ErrorType.java` 추가).

**[Should] NFR-4: SOP 문서 유지보수성**
Mapbox 토큰 회전 SOP 문서는 운영자(비개발자 포함)가 단독으로 따를 수 있는 단계별 절차를 포함한다.

---

## 6. 위험 및 가정 (Risks & Assumptions)

### 위험

| 위험 | 가능성 | 대응 |
|------|--------|------|
| **단일 PR 통합으로 인한 리뷰 부담** — ① pin 백엔드+프론트엔드, ② chatbot 운영 작업, ③ map 문서+폰트 수정이 하나의 PR에 묶여 리뷰 범위가 불명확해질 수 있다. 각 항목이 기능적으로 독립적이므로 분리가 더 자연스럽다는 반론이 있다. | 중간 | 사용자가 의식적으로 단일 PR을 선택하였으므로 따른다. PR 본문에 도메인별 섹션(① pin / ② chatbot / ③ map)을 명확히 분리하여 리뷰 가독성을 확보한다. |
| **picker UX 재사용 복잡도** — 기존 지도 picker는 신규 핀 등록 플로우와 결합되어 있다. "좌표 수정 모드"로 재진입 시 `MapboxView.tsx` 상태 관리가 복잡해질 수 있다. | 중간 | 구현 단계에서 `MapboxView.tsx`의 picker 상태 분기를 사전 검토한다. 신규 등록 플로우를 건드리지 않는 방향으로 설계한다. |
| **`globals.css` 폰트 변경의 시각적 회귀** — 기존 Arial로 렌더되던 UI가 Pretendard로 교체되면서 자간/행간 차이로 레이아웃이 일부 틀어질 수 있다. | 낮음 | PR 리뷰 시 주요 화면(지도, /pins, 챗봇 응답)을 시각적으로 확인한다. Pretendard는 이미 디자인 토큰의 의도된 폰트이므로 회귀가 아닌 의도된 수정이다. |
| **카카오 i 오픈빌더 콘솔 접근** — 빌더 시나리오 설정은 운영 작업으로, 콘솔 권한이 없으면 FR-BOT-9를 진행할 수 없다. | 낮음 | 빌더 콘솔 권한 확인을 Phase 착수 전 선행한다. 권한이 없으면 FR-BOT-9를 운영 작업 별도 분리로 처리한다. |

### 가정

- 카카오 빌더 콘솔 버튼 `action="message"` + `extra.placeId` 전송은 빌더 플랫폼 스펙상 지원되는 기능이다 (Phase 2.6 설계 당시 확인된 사항).
- `PinUpdateCommand`에 Provided 패턴을 적용하면 기존 8필드와 좌표 2필드가 독립적으로 동작하여 하나의 `PATCH` 호출로 좌표만, 텍스트 필드만, 또는 복합 수정이 모두 가능하다.
- `globals.css` body 폰트 수정은 Pretendard 파일 자체의 배포 방식을 변경하지 않는다. 이미 `public/fonts/`에 바이너리가 존재하고 `layout.tsx`에서 `--font-sans`로 주입 중이므로 추가 빌드 설정 변경이 없다.
- main 브랜치 머지는 이 Phase의 모든 [Must] 항목 완료 후 일괄 수행한다.

---

## 7. 영향 도메인 (Impact)

| 도메인 | 영향 | 비고 |
|--------|------|------|
| **pin (백엔드)** | `PinUpdateCommand` 좌표 필드 2개 확장, `PinV1Controller` `PATCH` 처리 확장, `PinV1Dto` 요청 DTO 좌표 필드 추가, `ErrorType.java` 에러 코드 1개 추가(`PIN_COORDINATE_INVALID`) | 기존 `updatePin`/`softDeletePin` 경로 영향 없음 |
| **pin (프론트엔드 /map)** | `PinPopup.tsx` ⋮ 메뉴에 "좌표 수정" 항목 추가, `MapboxView.tsx` picker 재사용 좌표 수정 모드, `useOptimistic` `patch` 리듀서 좌표 반영 확장 | 기존 DOM Marker 패턴 유지 |
| **pin (프론트엔드 /pins)** | 변경 없음. `PinEditDialog`는 텍스트 필드 편집만 유지 | 좌표 수정 진입점 추가 없음 |
| **pin (공통 API 클라이언트)** | `frontend/src/lib/api/pin.ts` `updatePin` 시그니처에 좌표 선택적 파라미터 추가, `types.ts` 요청 타입 좌표 필드 추가 | 기존 호출부 영향 없음 |
| **chatbot (운영)** | 카카오 i 오픈빌더 콘솔 시나리오 설정 (코드 변경 없음) | 운영 작업 |
| **map (CSS)** | `frontend/src/app/globals.css` body `font-family` 수정 | `var(--font-sans)` 연결, Arial 폴백 유지 |
| **context/map/ 문서** | `status.md` Pretendard 항목 정합화, `mapbox-token-sop.md` 신규 작성, `status.md`에서 SOP cross-link 추가 | 코드 변경 없음 |
| **context/pin/status.md** | FR-PIN-7, FR-PIN-8 완료 기재, Phase 2.10 완료 갱신 | Phase 완료 후 |
| **context/chatbot/status.md** | FR-BOT-9, FR-BOT-10 완료 기재, Phase 2.6 PR-C 이월 항목 해소 갱신 | Phase 완료 후 |
