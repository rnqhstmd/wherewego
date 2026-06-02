# Phase 7 — 태그 3종 리뉴얼 PRD

## 배경

MVP에서 PLACE / MEMORY 2종으로 운영 중이나, PLACE 하나가 "릴스에서 발견한 곳(챗봇 자동 등록)"과 "내가 저장한 가보고 싶은 곳(웹 직접 등록)" 두 의도를 혼재시켜 지도 맥락이 불명확하다. ~2,500핀 중 REEL 비중 약 60% 예상.

현재 상태 (코드 확인):
- Backend: `PinTag` enum이 `PLACE / MEMORY` 2종. `Pin.autoFromInstagram()` / `Pin.fromSelection()` 모두 `PinTag.PLACE` 고정. `chk_pins_tag CHECK (tag IN ('PLACE', 'MEMORY'))`.
- Frontend: `PinTag` 타입 `"PLACE" | "MEMORY"`. 웹 등록 UI(`MemoTagPanelContent`)에 PLACE/MEMORY 2종 칩. 룰렛 기본 풀 `["PLACE"]`. `MapClient.includeMemory` 토글 ON 시 `["PLACE", "MEMORY"]`. 디자인 토큰 `pinPlace: "#7BB3E8"` / `pinMemory: "#F4A8B0"`.

PLACE → REEL(발견) + WISH(설렘)로 분리하여 등록 경로와 사용자 의도를 1:1 대응시킨다.

---

## 범위

**포함:** enum 3종 확장 및 DB 마이그레이션, 챗봇 기본값 변경, 웹 등록 UI 2종(WISH/MEMORY), 지도 마커 3종 시각 구분, 룰렛 후보 풀 갱신, 관련 테스트 동기화, 컨텍스트 문서(`glossary.md`, `architecture.md`) 갱신

**제외:** visited 기능 재도입, 그룹 권한 변경, 인스타그램 URL 수정 기능(별도 Phase 예정), 태그별 지도 필터 토글(현재 항상 전체 렌더)

---

## 요구사항

### [Must] 필수

- **FR-7-1**: `PinTag` enum을 REEL / WISH / MEMORY 3종으로 확장한다. 기존 PLACE 값은 Expand-Contract 마이그레이션 완료 후 제거된다.
- **FR-7-2 (Expand — V006)**: `chk_pins_tag` CHECK 제약에 `REEL`, `WISH` 값을 추가한다. `PLACE`는 이 단계에서 유지된다. 코드 배포 없이 DB만 변경한다.
- **FR-7-3 (Migrate — V007)**: `UPDATE pins SET tag='REEL' WHERE tag='PLACE'` 일괄 변환을 단일 트랜잭션으로 실행한다. 이 Flyway 스크립트와 새 코드(REEL/WISH/MEMORY)를 동시에 배포한다.
- **FR-7-4 (Contract — V008)**: CHECK 제약에서 `PLACE` 값을 제거한다. V007 배포 안정 확인 후 별도 배포한다.
- **FR-7-5**: 챗봇 자동 등록(`Pin.autoFromInstagram()`, `Pin.fromSelection()`)의 기본 태그를 `PinTag.PLACE` → `PinTag.REEL`로 변경한다.
- **FR-7-6**: 웹 직접 등록 UI(`MemoTagPanelContent`)에서 WISH(설렘) / MEMORY(추억) 2종만 선택 가능하도록 변경한다. REEL은 웹 등록 시 선택지로 노출하지 않는다.
- **FR-7-7**: 지도 마커를 3종으로 시각 구분한다.
  - REEL: 연보라 `#C5B4E3`, 인스타그램 스타일 아이콘 (둥근 정사각형 + 렌즈 형태 SVG)
  - WISH: 민트 `#A8E6CF`, 동그라미 (기존 PLACE 마커 형태 유지, 색상만 변경)
  - MEMORY: 핑크 `#FFB3C6`, 하트 (기존 MEMORY 마커 형태 유지, 색상 `#F4A8B0` → `#FFB3C6` 변경)
- **FR-7-8**: 룰렛 기본 후보 풀을 `["REEL", "WISH"]`로 변경한다. `includeMemory` 토글 ON 시 `["REEL", "WISH", "MEMORY"]`로 확장한다.

### [Should] 권장

- **FR-7-9**: 핀 편집 다이얼로그(`PinEditDialog`)에서 태그 변경 시 REEL / WISH / MEMORY 3종 모두 선택 가능하다. (REEL은 등록은 챗봇 전용이지만, 기존 핀의 태그 수정 시에는 허용한다. 사용자가 잘못 저장된 태그를 자유롭게 수정 가능하도록 등록 경로 제한을 두지 않는다 — Q1 사용자 결정.)
- **FR-7-10**: 핀 목록 필터(`TagFilter`)를 전체 / REEL / WISH / MEMORY 4종으로 갱신한다.
- **FR-7-11**: `PinTag` 칩 컴포넌트(`PinTag.tsx`)를 3종 표시명과 색상으로 갱신한다. (REEL: "발견" / WISH: "설렘" / MEMORY: "추억")
- **FR-7-12**: 컨텍스트 문서 `context/tag/glossary.md`, `context/tag/architecture.md`를 3종 기준으로 갱신한다.

### [Could] 선택

- **FR-7-13**: 룰렛 결과 UI(`RouletteResultContent`)에서 태그 표시명을 3종으로 갱신한다.

---

## 수용 기준

- **AC-1**: 챗봇을 통해 인스타 릴스 URL을 등록하면, 생성된 핀의 `tag` 값이 `REEL`이다. → [FR-7-5]
- **AC-2**: 웹 직접 등록 패널(`새 핀 추가`)에서 태그 선택지가 "설렘"과 "추억" 2종만 표시된다. REEL / 발견 선택지는 노출되지 않는다. → [FR-7-6]
- **AC-3**: V007 실행 후 DB에 `tag='PLACE'`인 핀이 0건이다. → [FR-7-3]
- **AC-4**: V008 실행 후 `chk_pins_tag` CHECK 제약 정의에 `PLACE`가 포함되지 않는다. → [FR-7-4]
- **AC-5**: 지도에서 REEL 핀은 연보라(`#C5B4E3`) 인스타그램 스타일 아이콘으로, WISH 핀은 민트(`#A8E6CF`) 동그라미로, MEMORY 핀은 핑크(`#FFB3C6`) 하트로 각각 구분 표시된다. → [FR-7-7]
- **AC-6**: 룰렛 실행 시 `includeMemory` 토글 OFF 상태에서 후보 풀이 REEL + WISH 핀만으로 구성된다. MEMORY 핀은 후보에 포함되지 않는다. → [FR-7-8]
- **AC-7**: 룰렛 실행 시 `includeMemory` 토글 ON 상태에서 후보 풀이 REEL + WISH + MEMORY 핀으로 구성된다. → [FR-7-8]
- **AC-8**: REEL / WISH / MEMORY 핀이 모두 0건인 반경에서 룰렛 실행 시 "exhausted" 결과가 반환된다. → [FR-7-8]
- **AC-9**: V006 배포 후 기존 코드(PLACE 사용)가 정상 동작한다. DB에 `PLACE` 값이 여전히 유효하게 저장된다. → [FR-7-2]
- **AC-10** [Should]: 핀 편집 다이얼로그에서 기존 REEL 핀의 태그를 WISH 또는 MEMORY로 변경하고 저장하면 반영된다. 또한 WISH/MEMORY → REEL 변경도 허용된다. → [FR-7-9]
- **AC-11** [Should]: 핀 목록 필터에 "발견 / 설렘 / 추억 / 전체" 4종 탭이 표시된다. → [FR-7-10]

---

## 비기능 요구사항

- **마이그레이션 원자성**: V007 `UPDATE`는 단일 트랜잭션으로 실행. ~2,500핀 규모에서 실행 시간 < 1초 예상, 서비스 중단 없음.
- **배포 순서 강제**: V006은 코드 변경 없이 먼저 배포. V007은 새 코드와 동시 배포. V008은 별도 안정 확인 후 배포. 이 순서를 어기면 서비스 다운 가능.
- **롤백 가능 구간**: V006 배포 후 ~ V007 배포 전 사이에서만 롤백 안전. V007 이후 REEL/WISH 핀을 PLACE로 되돌리는 자동화 수단 없음 (데이터 비가역적 변환 — 아래 엣지케이스 참조).
- **기존 핀 이력 보존**: 마이그레이션 후 핀 ID, 생성일시, 메모, 장소명 등 모든 컬럼은 변경 없음.

---

## 마이그레이션 전략 (Expand-Contract)

| 단계 | Flyway | SQL 요약 | 코드 상태 |
|------|--------|----------|-----------|
| **1단계 Expand** | V006 | `ALTER TABLE pins DROP CONSTRAINT chk_pins_tag; ALTER TABLE pins ADD CONSTRAINT chk_pins_tag CHECK (tag IN ('PLACE','REEL','WISH','MEMORY'))` | 기존 코드(PLACE 사용) 그대로 배포 |
| **2단계 Migrate** | V007 | `UPDATE pins SET tag='REEL' WHERE tag='PLACE'` (단일 트랜잭션) | 새 코드(REEL/WISH/MEMORY) 동시 배포 |
| **3단계 Contract** | V008 | `ALTER TABLE pins DROP CONSTRAINT chk_pins_tag; ALTER TABLE pins ADD CONSTRAINT chk_pins_tag CHECK (tag IN ('REEL','WISH','MEMORY'))` | 코드 변경 없음 |

- V006 ~ V007 사이: PLACE 여전히 유효 → 롤백 가능
- V007 이후: PLACE 핀 0건, REEL/WISH 코드 활성화 → 롤백 시 데이터 손실 발생 (하단 엣지케이스 참조)

---

## 영향 범위

**Backend:**
- `PinTag.java` — enum 2종 → 3종 (REEL/WISH/MEMORY)
- `Pin.java` — `autoFromInstagram()`, `fromSelection()` 기본값 `PLACE` → `REEL`
- `V006__expand_tag_constraint.sql`, `V007__migrate_place_to_reel.sql`, `V008__contract_tag_constraint.sql` 신규 추가
- 관련 테스트: `PinTest.java`, `PinV1ControllerIntegrationTest.java`, `PinCreateIT.java`, `PlaceSelectionHandler` 테스트 — enum 변경 동기 갱신

**Frontend:**
- `lib/api/types.ts` — `PinTag` 타입 `"REEL" | "WISH" | "MEMORY"`로 변경
- `lib/design/tokens.ts` — `pinPlace: "#7BB3E8"` 제거, `pinReel: "#C5B4E3"` / `pinWish: "#A8E6CF"` 추가, `pinMemory: "#F4A8B0"` → `"#FFB3C6"` 수정
- `components/ui/PinDot.tsx` — `"place"` 타입 제거, `"reel"` / `"wish"` 추가. REEL SVG 아이콘 신규 추가
- `components/ui/PinTag.tsx` — `PinTagType` 3종, 레이블/색상 갱신
- `app/map/_components/MemoTagPanelContent.tsx` — PLACE 칩 제거, WISH/MEMORY 2종으로 교체
- `app/map/_lib/roulette.ts` — `pickRandomWithExpansion` 기본값 `["PLACE"]` → `["REEL", "WISH"]`
- `app/map/MapClient.tsx` — `tagsAllowed` 구성: `includeMemory` OFF → `["REEL","WISH"]`, ON → `["REEL","WISH","MEMORY"]`. PLACE 참조 전부 제거
- `app/pins/_components/TagFilter.tsx` — PLACE/MEMORY 2종 → REEL/WISH/MEMORY 3종
- `app/pins/_components/PinEditDialog.tsx` — 태그 라디오 3종으로 갱신
- 관련 테스트: `PinTag.test.tsx`, `PinDot.test.tsx`, `roulette.test.ts` 동기 갱신

**Context 문서:**
- `context/tag/glossary.md` — 2종 기준 → 3종으로 갱신
- `context/tag/architecture.md` — 2종 기준 → 3종으로 갱신
- `context/tag/status.md` — FR-TAG-7~11 완료 표시

---

## 엣지케이스

**E-1. V006 배포 후 V007 배포 전 챗봇 요청 유입**
기존 코드가 그대로 동작하므로 챗봇 자동 등록 핀은 `PLACE`로 저장된다. V006에서 `PLACE`가 CHECK 제약에 여전히 유효하므로 DB 오류 없음. V007 실행 시 해당 핀도 `REEL`로 일괄 변환된다. 사용자 경험 영향 없음.

**E-2. V007 배포 후 롤백 필요 시**
V006 상태(코드 + DB)로 되돌릴 수 있으나, V007에서 `REEL`/`WISH`로 변환된 핀을 `PLACE`로 자동 복구하는 수단이 없다. 롤백 시 REEL/WISH 태그 핀은 구코드에서 알 수 없는 enum 값으로 취급될 수 있다. **롤백 전 수동 `UPDATE pins SET tag='PLACE' WHERE tag='REEL'` 실행이 선행되어야 한다.** 이 절차를 운영 플레이북에 명시한다.

**E-3. 핀 상세/편집에서 REEL 태그 변경 가능 여부**
REEL은 챗봇 전용 등록 경로이지만, 기존 REEL 핀을 편집 다이얼로그에서 WISH/MEMORY로 변경하는 것은 허용한다 [FR-7-9, Should]. WISH/MEMORY → REEL 변경도 다이얼로그에서 허용한다 (3종 모두 라디오 선택 가능 — Q1 사용자 결정). 단, 웹 신규 등록 시 REEL은 선택 불가 [FR-7-6, Must].

**E-4. 룰렛 후보 0건 (모든 핀이 MEMORY)**
`pickRandomWithExpansion`이 `{ kind: "exhausted" }` 반환. 현재 코드에서 이미 exhausted 분기가 처리되어 있으므로 추가 대응 불필요. `includeMemory` 토글 ON 유도 안내 문구 표시는 기존 동작 유지.

**E-5. V007 단일 트랜잭션 중 실패**
Flyway가 트랜잭션 롤백을 처리하므로 부분 변환 상태는 발생하지 않는다. `PLACE` 핀이 그대로 유지된 채 V007 재시도 가능.

**E-6. MEMORY 토큰 색상 변경(`#F4A8B0` → `#FFB3C6`) 사이드이펙트**
기존 마커, `PinTag` 칩, `PinDot` 등 `colors.pinMemory`를 참조하는 모든 컴포넌트가 색상 변경의 영향을 받는다. 디자인 토큰 단일 수정으로 전파되므로 누락 위험은 낮으나, 참조 컴포넌트 전체 시각 확인이 필요하다.

---

## 영향받지 않는 것 (Non-goals)

- 기존 핀의 ID, 생성일시, 메모, 장소명, instagramUrl 등 태그 외 모든 컬럼 — V007에서 변경하지 않는다.
- `visited` 기능 — 이미 제거됨, 재도입 없음.
- 그룹 권한 / 멤버십 정책 — 변경 없음.
- 핀당 1태그 강제 — 현재 정책 유지.
- 인스타그램 URL 수정 기능 — 별도 Phase.
- 지도 일반 마커 필터 토글 — 현재 항상 전체 렌더 유지.
- 100명 미만 사용자에 대한 마이그레이션 공지 — 무중단 배포로 별도 공지 없이 진행.
