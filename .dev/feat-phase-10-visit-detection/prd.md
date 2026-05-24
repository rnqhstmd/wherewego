# PRD: Phase 10 — 장소 방문 감지 ("이 장소에 오셨나요?")

- 작성일: 2026-05-24
- 수정일: 2026-05-24
- 관련 레포: rnqhstmd/wherewego
- 베이스 문서: context/pin/phase-10-visit-detection.md, context/map/phase-11-our-records.md
- 사전 결정: /ttutak:context Q1~Q9 세션 (2026-05-23~24)

## 배경

우리가갈지도는 커플이 함께 가고 싶은 장소를 WISH·REEL 핀으로 저장하는 앱이다. 현재는 핀을 저장한 뒤 실제로 방문했을 때 수동으로 지도를 열어 해당 핀을 찾아 태그를 MEMORY로 변경해야 한다. 이 흐름은 직관적이지 않아 대부분의 사용자가 방문 후에도 핀을 MEMORY로 전환하지 않고 방치한다. 결과적으로 Phase 11 "우리 기록" 화면이 실제 다녀온 장소를 반영하지 못하게 된다. Phase 10은 GPS 위치를 기반으로 저장된 핀 근처에 실제로 머물 때 전환을 자동으로 제안하여, 커플이 방문 기억을 자연스럽게 쌓을 수 있도록 한다.

**현재 제품 상태:**
- GeolocateControl이 지도 화면에 이미 존재하며 `geo.on("geolocate")` 콜백이 구현되어 있다 (`MapboxView.tsx:435-462`)
- PATCH `/api/v1/groups/{groupId}/pins/{pinId}`로 태그 및 메모를 독립 변경 가능한 구조가 갖춰져 있다
- Phase 8 알림 인프라(MANUAL_PIN 패턴)가 존재하며 VISIT_DETECTED 유형 추가로 확장 가능하다
- 알림 CHECK 제약은 현재 `('MANUAL_PIN','CHATBOT_PINS')`만 허용하며, V009 마이그레이션으로 확장이 필요하다

---

## 목표

- WISH·REEL 핀 근처에 실제로 30초 이상 머문 사용자에게 MEMORY 전환을 자동 제안한다
- 사용자가 방문 기억(메모)을 자연스럽게 기록하도록 유도한다
- 전환이 완료되면 짝꿍에게 알림을 전달하여 함께 기억을 공유한다
- Phase 11 "우리 기록 — 추억" 탭에 MEMORY 핀이 정상적으로 집계되도록 한다

**성공 지표:** 방문 감지 후 MEMORY 전환율 (토스트 노출 대비 "네, 다녀왔어요" 클릭 비율)

---

## 요구사항

### 기능 요구사항

#### [근접 감지 — 프론트엔드]

- [Must] **FR-VD-1**: `useVisitDetection` 훅을 신규 생성한다. MapboxView의 `geo.on("geolocate")` 콜백에서 호출되며, GPS 이벤트 발생 시마다 평가를 수행한다. throttle 없음.
- [Must] **FR-VD-2**: 평가 대상은 현재 그룹의 WISH·REEL 태그 핀만이다. MEMORY 태그 핀은 평가에서 제외한다.
- [Must] **FR-VD-3**: `position.coords.accuracy > 50m`이면 해당 GPS 이벤트의 근접 판정 평가 전체를 스킵한다. 평가 없이 다음 이벤트를 기다린다.
- [Must] **FR-VD-4**: Haversine 공식으로 사용자 현재 위치와 각 핀 좌표 간 거리를 계산한다. 100m 이내 핀을 근접 핀 후보로 수집한다.
- [Must] **FR-VD-5**: 근접 핀 후보 중 세션 Set(`shownPinIds`)에 이미 등록된 pinId는 제외한다. 세션 Set은 메모리 내 Set으로 관리하며, MapClient 페이지 unmount 시 리셋된다. localStorage 미사용.
- [Must] **FR-VD-6**: 후보 중 가장 가까운 핀 1개만 평가 대상으로 선정한다.
- [Must] **FR-VD-7**: 선정된 핀에 대해 "첫 진입 시각"을 기록한다. 이후 GPS 이벤트마다 해당 핀이 여전히 100m 이내에 있는지 확인한다. 첫 진입 시각으로부터 30초가 경과한 시점의 GPS 이벤트에서도 100m 이내로 확인되면 방문 감지를 확정하고 토스트를 노출한다.
- [Must] **FR-VD-8**: 30초 경과 전에 사용자가 100m 밖으로 이탈하면 해당 핀의 첫 진입 시각을 초기화한다. 세션 Set에는 추가하지 않는다.

#### [방문 토스트 — 프론트엔드]

- [Must] **FR-VD-9**: 방문 감지가 확정되면 `VisitToast` 컴포넌트를 지도 하단에서 슬라이드 업(translateY 애니메이션)으로 표시한다. UI는 한 번에 1개만 표시한다.
- [Must] **FR-VD-10**: 토스트에는 장소명과 주소를 표시한다. 장소명 앞에 📍 아이콘. 형식: `📍 {장소명} 근처에 계신가요?` + 주소 부줄.
- [Must] **FR-VD-11**: 토스트에 "다음에 올게요" 버튼(보조, 회색 텍스트)과 "네, 다녀왔어요 →" 버튼(주 CTA, `colors.cta` 핑크)을 제공한다.
- [Must] **FR-VD-12**: "다음에 올게요" 클릭 시 토스트를 닫고 해당 pinId를 세션 Set에 추가한다. 세션 중 동일 핀의 토스트는 다시 표시하지 않는다.
- [Must] **FR-VD-13**: "다음에 올게요" 또는 "네, 다녀왔어요 →" 클릭 직후, 다음 geolocate 콜백부터 세션 Set에 없는 나머지 후보 핀 중 가장 가까운 핀에 대한 평가를 자동으로 재개한다.

#### [MEMORY 전환 — 프론트엔드]

- [Must] **FR-VD-14**: "네, 다녀왔어요 →" 클릭 시 즉시 1차 PATCH를 발사한다. 요청: `PATCH /api/v1/groups/{groupId}/pins/{pinId}` body `{ "tag": "MEMORY" }`.
- [Must] **FR-VD-15**: 1차 PATCH 성공 시 마커 confetti 애니메이션을 실행한다.
  - 마커 위치에서 하트 ♡ 3개가 위쪽 fan-out 랜덤 경로로 떠오르며 fade out (200ms 내)
  - 동시에 마커는 scale `1.0 → 1.3 → 1.0` 이중 bounce
  - 마커 아이콘은 confetti 시작과 동시에 WISH/REEL → MEMORY 아이콘으로 교체
  - 전체 애니메이션 지속 시간: 약 600ms
- [Must] **FR-VD-16**: 1차 PATCH 성공 후 confetti 애니메이션 시작과 함께 `VisitMemoSheet`(메모 입력 바텀시트)를 슬라이드 업으로 표시한다. 시트와 애니메이션은 동시에 시작한다.
- [Must] **FR-VD-17**: 메모 시트 상단에 장소명과 방문 날짜(`YYYY년 M월 D일` 형식)를 표시한다. 형식: `✓ {장소명}, 다녀왔어요!` + 방문 날짜.
- [Must] **FR-VD-18**: 메모 시트에 텍스트 입력 영역과 "저장" 버튼, "건너뛰기" 버튼을 제공한다.
- [Must] **FR-VD-19**: "저장" 클릭 시 2차 PATCH를 발사한다. 요청: `PATCH /api/v1/groups/{groupId}/pins/{pinId}` body `{ "memo": "{입력값}" }`. 성공 시 시트를 닫는다.
- [Must] **FR-VD-20**: "건너뛰기" 클릭 시 2차 PATCH를 발사하지 않고 시트를 닫는다. 태그는 이미 MEMORY로 변경된 상태를 유지한다.
- [Must] **FR-VD-21**: 1차 PATCH(태그 변경) 실패 시 토스트를 닫고 인라인 에러 토스트(시스템 레벨)를 표시한다. 메모 시트는 열지 않는다. 해당 pinId는 세션 Set에 추가하지 않는다 (다음 방문 감지 시 재시도 가능).
- [Must] **FR-VD-22**: 2차 PATCH(메모 저장) 실패 시 시트 내 인라인 에러를 표시하고 재시도 가능 상태를 유지한다. 시트를 자동으로 닫지 않는다.

#### [권한 및 비활성 처리 — 프론트엔드]

- [Must] **FR-VD-23**: GeolocateControl이 비활성 상태(위치 권한 거부 또는 미요청)이면 방문 감지 평가를 수행하지 않는다. 추가 안내 UI 없이 조용히 비활성 상태를 유지한다.
- [Must] **FR-VD-24**: 사용자가 GeolocateControl 버튼을 눌러 권한을 허용하면 그 시점부터 방문 감지 평가가 자동으로 활성화된다.

#### [알림 — 백엔드]

- [Must] **FR-VD-25**: `NotificationType` enum에 `VISIT_DETECTED` 유형을 추가한다.
- [Must] **FR-VD-26**: DB `notifications` 테이블의 CHECK 제약을 V009 Flyway 마이그레이션으로 확장한다. `('MANUAL_PIN','CHATBOT_PINS','VISIT_DETECTED')`를 허용한다. 파일명: `V009__add_visit_detected_notification_type.sql`.
- [Must] **FR-VD-27**: `NotificationService`에 `createForVisitDetected(groupId, userId, pinId)` 메서드를 추가한다. 동작은 기존 MANUAL_PIN 패턴과 동일하되, 해당 그룹의 **활성 멤버 전원(등록자 본인 포함)**에게 Notification 1행 + NotificationPin 1행을 생성한다. 본인 포함은 Phase 11 "우리 기록" 도입 전 과도기 용도로, 본인 알림함에도 방문 기록이 남는다 (NotificationItem 은 본인 분기 시 "내가 다녀온 장소"로 라벨링). 트랜잭션은 receiver 단위 `REQUIRES_NEW` 로 처리하며, 부분 UNIQUE 인덱스 `uq_notifications_visit` 위반은 try-catch 로 조용히 스킵하여 race-free 중복 차단을 보장한다.
- [Must] **FR-VD-28**: `PinV1Controller`의 `PATCH /pins/{id}` 핸들러에서 태그가 WISH/REEL → MEMORY로 변경되는 케이스에 한해, 기존 MANUAL_PIN 호출 패턴과 동일하게 try-catch로 `NotificationService.createForVisitDetected(groupId, userId, pinId)`를 호출한다. 메모 작성 여부와 무관하게 태그 전환 성공 즉시 알림을 발송한다.
- [Must] **FR-VD-29**: 알림 상세 조회(`GET /notifications/{id}`) 응답에 VISIT_DETECTED 유형을 처리한다. 응답에는 장소명, 주소, 태그, 현재 메모(항상 최신값 join)를 포함한다. 메모 미작성 시 메모 필드는 null 또는 빈 값으로 반환한다.

#### [알림 — 프론트엔드]

- [Should] **FR-VD-30**: `NotificationItem.tsx`에 VISIT_DETECTED 분기를 추가한다. 알림 목록에서 방문 감지 알림을 구분하여 표시한다. 등록자가 본인(`registeredBy === currentUserId`)이면 "내가 다녀온 장소"로, 짝꿍이면 "{닉네임}님이 다녀온 장소"로 라벨링한다 (FR-VD-27 본인 포함 fan-out 정책과 일관, NotificationPanel 상세 화면 actorLabel 패턴과 동일).
- [Should] **FR-VD-31**: 알림 상세(`NotificationPanel`)에서 VISIT_DETECTED 알림 클릭 시 장소명, 주소, 태그, 현재 메모를 표시한다. 메모 미작성 시 메모란을 비워 표시한다.

### 비즈니스 규칙

- [Must] **BR-VD-1**: 감지 반경은 100m (Haversine 공식, 프론트엔드 계산). 백엔드 거리 계산 없음.
- [Must] **BR-VD-2**: 방문 감지 트리거는 매 `geolocate` 이벤트 콜백 발생 시마다 평가한다. throttle 없음.
- [Must] **BR-VD-3**: GPS 정확도 ≤ 50m 인 이벤트만 근접 판정에 사용한다. 50m 초과 이벤트는 머무름 타이머에도 영향을 주지 않는다.
- [Must] **BR-VD-4**: 30초 머무름은 "첫 진입 시각 기록 → 이후 콜백에서 시간 차이 계산"으로 구현한다. 별도 setInterval 타이머 미사용.
- [Must] **BR-VD-5**: 세션 Set(`shownPinIds`)은 메모리 내에서만 유지한다. MapClient unmount 시 자동 소멸한다. 앱 새로고침 또는 페이지 재방문 시 Set이 초기화되어 동일 핀에 대해 다시 토스트를 표시할 수 있다.
- [Must] **BR-VD-6**: 알림 실패(VISIT_DETECTED 알림 생성 실패)가 태그 PATCH 응답에 영향을 주지 않는다. try-catch로 격리 (notification BR-3 정책 동일 적용).
- [Must] **BR-VD-7**: MEMORY 태그 핀은 감지 대상에서 완전히 제외한다. 단 사용자가 MEMORY로 전환한 직후 해당 핀은 세션 Set에도 추가하여 전환 직후 토스트 재표시를 방지한다.
- [Should] **BR-VD-8**: "다음에 올게요" 클릭 후 해당 pinId는 세션 Set에 추가된다. 이후 동시 후보 핀이 있으면 가장 가까운 다음 핀의 30초 머무름 평가를 이어서 진행한다.

### 품질 기대

- [Should] **QE-VD-1**: 방문 감지 토스트 및 메모 시트가 기존 PinPopup, 알림 패널 등 다른 시트와 동시에 열리지 않는다. 기존 동시 1개 패널 정책(`activeSheet`)을 따른다.
- [Should] **QE-VD-2**: Haversine 거리 계산 로직은 단위 테스트로 검증한다. 100m 경계값(정확히 100m, 99m, 101m)을 포함한다.

---

## 사용자 시나리오

### 골든 패스: 방문 감지 → MEMORY 전환 → 짝꿍 알림

1. 사용자 A가 지도 화면에서 GeolocateControl 버튼을 활성화한다. GPS 정확도 30m의 위치 추적이 시작된다.
2. 사용자 A가 저장해둔 WISH 핀 "온량"(서울 송파구 백제고분로43길) 반경 100m 이내에 실제로 진입한다.
3. 첫 번째 geolocate 이벤트에서 100m 이내가 감지되고 진입 시각이 기록된다.
4. 이후 geolocate 이벤트에서도 100m 이내를 유지한다. 첫 진입 시각으로부터 30초 이상 경과한 이벤트에서 방문 감지가 확정된다.
5. 지도 하단에서 토스트가 슬라이드 업으로 등장: `📍 온량 근처에 계신가요?` + `서울 송파구 백제고분로43길` + [다음에 올게요] [네, 다녀왔어요 →]
6. 사용자 A가 "네, 다녀왔어요 →"를 클릭한다.
7. 1차 PATCH(태그 → MEMORY)가 즉시 발사된다.
8. PATCH 성공: 지도 위 온량 마커에서 하트 ♡ 3개가 위로 떠오르며 사라지고, 마커가 scale bounce하면서 MEMORY 아이콘으로 교체된다. 동시에 메모 시트가 슬라이드 업된다.
9. 메모 시트에 `✓ 온량, 다녀왔어요!` + 오늘 날짜가 표시된다.
10. 사용자 A가 "오늘 드디어 왔다!"를 입력하고 "저장"을 클릭한다.
11. 2차 PATCH(메모 저장)가 발사되고 시트가 닫힌다.
12. 백엔드 PinV1Controller에서 태그 PATCH 완료 직후 `NotificationService.createForVisitDetected`가 호출된다.
13. 짝꿍 사용자 B가 탭을 다시 활성화(`visibilitychange`)하거나 앱에 포커스 복귀 시 알림 목록이 갱신된다. 빨간 점이 표시되고, 알림 패널에 방문 감지 알림이 나타난다.
14. 사용자 B가 알림을 클릭하면 온량의 장소명, 주소, MEMORY 태그, 사용자 A의 메모를 확인한다.

### 예외 흐름: "건너뛰기"로 메모 없이 종료

1. ~8번까지 동일.
2. 사용자 A가 메모 시트에서 "건너뛰기"를 클릭한다.
3. 시트가 닫힌다. 2차 PATCH 미발사. 태그는 MEMORY 상태 유지.
4. 짝꿍 알림은 이미 발송된 상태이며, 알림 상세의 메모란은 비어 있다.

### 예외 흐름: 동시 다수 핀

1. 사용자 A가 100m 이내에 WISH 핀 "온량"과 REEL 핀 "선릉역 카페"가 함께 존재하는 지역에 진입한다.
2. 두 핀 모두 세션 Set에 없으므로, 가장 가까운 "온량"에 대한 30초 머무름 측정이 시작된다.
3. 30초 후 "온량" 토스트가 표시된다.
4. 사용자 A가 "다음에 올게요"를 클릭한다. "온량" pinId가 세션 Set에 추가된다.
5. 다음 geolocate 콜백부터 "선릉역 카페"에 대한 30초 머무름 측정이 시작된다.
6. 30초 후 "선릉역 카페" 토스트가 표시된다.

---

## 엣지케이스

| 상황 | 처리 |
|------|------|
| GPS 정확도 > 50m | 해당 geolocate 이벤트의 평가 전체 스킵. 머무름 타이머에도 영향 없음 |
| 30초 전 100m 이탈 | 진입 시각 초기화. 세션 Set 미추가. 재진입 시 30초 측정 재시작 |
| 토스트 표시 중 위치 이탈 | 토스트 유지. 사용자가 직접 닫기 (자동 닫힘 없음) |
| 이미 MEMORY인 핀 | 감지 대상에서 완전 제외 (FR-VD-2) |
| 1차 PATCH(태그 변경) 실패 | 토스트 닫힘 + 인라인 에러 토스트 표시("장소를 추억으로 옮기지 못했어요. 다시 시도해주세요.", 1.5초 자동 닫힘, `visitErrorMessage` state + setTimeout). 메모 시트 미표시. pinId 세션 Set 미추가 (재시도 가능). AC-VD-14 충족 |
| 2차 PATCH(메모 저장) 실패 | 메모 시트 내 인라인 에러. 시트 유지. 재시도 가능 |
| 위치 권한 거부/미요청 | 감지 비활성. 추가 안내 UI 없음. GeolocateControl 버튼 재활성화 시 자동 재개 |
| 핀이 0개(WISH·REEL 핀 없음) | 평가 대상 없음. 아무 동작 없음 |
| 동시 다수 핀 | 세션 Set 미등록 후보 중 가장 가까운 핀 1개만 30초 측정. 닫힌 후 다음 후보로 자동 이행 |
| 앱 새로고침 / 페이지 재방문 | 세션 Set 소멸. 동일 핀에 대해 30초 머무름부터 재평가 |
| 알림 발송 실패 | try-catch 격리. 태그 PATCH 응답 및 사용자 경험에 영향 없음 |
| 메모 미작성 후 짝꿍이 알림 조회 | 알림 상세 메모란 비움. Phase 11 카드에서 메모 없는 MEMORY 정상 케이스로 처리 |
| GeolocateControl 비활성 중 지도 화면 유지 | geolocate 콜백이 발생하지 않으므로 훅 평가 자체 미실행 |

---

## 영향 범위

**영향받는 기존 기능:**

- `MapboxView.tsx`: `geo.on("geolocate")` 콜백에 `useVisitDetection` 평가 함수 호출 추가
- `MapClient.tsx`: 훅 연결, 토스트/시트 조건부 렌더, 마커 전환 트리거 연결
- `PinV1Controller`: PATCH 핸들러에 VISIT_DETECTED 알림 호출 분기 추가
- `NotificationType` enum: VISIT_DETECTED 추가
- `NotificationItem.tsx`: VISIT_DETECTED 분기 추가
- `NotificationPanel.tsx`: VISIT_DETECTED 상세 표시

**기존 사용자 영향:**

- GeolocateControl을 활성화하지 않은 사용자: 변화 없음
- WISH·REEL 핀이 없는 그룹: 변화 없음
- 이미 MEMORY인 핀만 있는 그룹: 변화 없음

**하위 호환성:**

- 기존 MANUAL_PIN, CHATBOT_PINS 알림 동작 변경 없음
- V009 마이그레이션은 CHECK 제약 확장(기존 값 포함)이므로 기존 알림 데이터 영향 없음
- PATCH API 시그니처 변경 없음. 서버 측 분기 로직만 추가

---

## 수용 기준

| # | 수용 기준 | 대응 |
|---|-----------|------|
| AC-VD-1 | WISH·REEL 핀에서 100m 이내에 30초 이상 머물면 방문 토스트가 표시된다 | FR-VD-7 |
| AC-VD-2 | MEMORY 핀은 100m 이내에 있어도 토스트가 표시되지 않는다 | FR-VD-2, BR-VD-7 |
| AC-VD-3 | GPS 정확도가 50m를 초과하는 이벤트에서는 근접 판정 평가가 실행되지 않는다 | FR-VD-3, BR-VD-3 |
| AC-VD-4 | 30초 이전에 100m 밖으로 이탈하면 토스트가 표시되지 않으며 세션 Set에도 추가되지 않는다 | FR-VD-8 |
| AC-VD-5 | 동일 세션에서 동일 pinId의 토스트는 1회만 표시된다 (다음에 올게요 클릭 또는 전환 완료 후) | FR-VD-5, FR-VD-12 |
| AC-VD-6 | 토스트에 장소명(📍 {장소명} 근처에 계신가요?)과 주소가 표시된다 | FR-VD-10 |
| AC-VD-7 | "다음에 올게요" 클릭 시 토스트가 닫히고 세션 Set에 pinId가 추가된다 | FR-VD-12 |
| AC-VD-8 | "네, 다녀왔어요 →" 클릭 시 즉시 태그 PATCH가 발사된다 | FR-VD-14 |
| AC-VD-9 | 태그 PATCH 성공 시 마커에서 하트 confetti(3개, fan-out, ~200ms) + scale bounce가 동시에 실행되며 마커 아이콘이 MEMORY로 교체된다 | FR-VD-15 |
| AC-VD-10 | 태그 PATCH 성공 시 confetti와 동시에 메모 시트가 슬라이드 업된다 | FR-VD-16 |
| AC-VD-11 | 메모 시트에 "✓ {장소명}, 다녀왔어요!" + `YYYY년 M월 D일` 형식의 날짜가 표시된다 | FR-VD-17 |
| AC-VD-12 | 메모 입력 후 "저장" 클릭 시 메모 PATCH가 발사되고 성공 시 시트가 닫힌다 | FR-VD-19 |
| AC-VD-13 | "건너뛰기" 클릭 시 메모 PATCH가 발사되지 않고 시트가 닫힌다. 태그는 MEMORY 상태를 유지한다 | FR-VD-20 |
| AC-VD-14 | 태그 PATCH 실패 시 메모 시트가 열리지 않고 인라인 에러 토스트가 표시된다. pinId는 세션 Set에 추가되지 않는다 | FR-VD-21 (충족: MapClient `visitErrorMessage` state + 1.5초 자동 닫힘 useEffect) |
| AC-VD-15 | 메모 PATCH 실패 시 시트 내 인라인 에러가 표시되며 시트가 유지된다 | FR-VD-22 |
| AC-VD-16 | GeolocateControl 비활성 상태에서는 방문 감지 평가가 실행되지 않는다 | FR-VD-23 |
| AC-VD-17 | WISH/REEL → MEMORY 태그 전환 성공 시 짝꿍에게 VISIT_DETECTED 알림이 생성된다 (메모 작성 여부 무관) | FR-VD-28 |
| AC-VD-18 | VISIT_DETECTED 알림 생성 실패가 태그 PATCH API 응답에 영향을 주지 않는다 | FR-VD-28, BR-VD-6 |
| AC-VD-19 | V009 마이그레이션 적용 후 notifications 테이블에 VISIT_DETECTED 값 insert가 성공한다 | FR-VD-26 |
| AC-VD-20 | 알림 상세에서 VISIT_DETECTED 알림 클릭 시 장소명, 주소, MEMORY 태그, 현재 메모(없으면 비움)가 표시된다 | FR-VD-29, FR-VD-31 |
| AC-VD-21 | MapClient 페이지 unmount 시 세션 Set이 소멸하여, 재방문 시 동일 핀에 대해 방문 감지가 재시작된다 | FR-VD-5, BR-VD-5 |
| AC-VD-22 | 동시에 100m 이내에 2개 이상의 WISH·REEL 핀이 있을 때, 토스트는 1개만 표시되며 닫힌 후 다음 가까운 핀에 대한 평가가 자동 재개된다 | FR-VD-13, Q5 |

---

## 제외 범위

- 백엔드 거리 계산 (PostGIS 미도입, Haversine은 프론트엔드에서만 수행)
- 위치 권한 거부 시 사용자 안내 UI (조용히 비활성)
- setInterval 기반 머무름 타이머 (geolocate 콜백 시각 비교 방식으로 대체)
- 30초 머무름 임계값 사용자 설정 기능
- MEMORY → WISH/REEL 역방향 전환 기능 (Phase 10 범위 밖)
- 백그라운드 위치 추적 (앱이 포그라운드 + GeolocateControl 활성 상태에서만 동작)
- Phase 11 "우리 기록" 화면 구현 (Phase 11 별도 PRD)
- SSE 실시간 알림 (옵션 B 정책에 따라 현행 fetch 트리거 방식 유지)

---

## 의존성

| 항목 | 설명 |
|------|------|
| Phase 8 알림 인프라 | `NotificationService`, `NotificationItem`, `NotificationPanel` 기반 필수 |
| V009 Flyway 마이그레이션 | `V009__add_visit_detected_notification_type.sql` — CHECK 제약 확장. 백엔드 서비스 기동 전 적용 필요 |
| GeolocateControl + geo.on("geolocate") | `MapboxView.tsx:435-462`에 이미 존재. 콜백에 훅 평가 함수 연결만 필요 |
| PATCH /api/v1/groups/{groupId}/pins/{pinId} | 기존 API 재사용. 시그니처 변경 없음 |
| Phase 11 — 우리 기록 | Phase 10 완료 후 MEMORY 핀 집계를 소비하는 후속 Phase. Phase 10 완료가 선행 조건 |

---

## 결정 이력 (사전 Q&A 9건)

| # | 항목 | 결정 |
|---|------|------|
| Q1 | 세션 정의 | 메모리 Set, 페이지 unmount 시 리셋 |
| Q2 | 트리거 주기 | 매 geolocate 콜백, throttle 없음 |
| Q3 | 진입/통과 구분 | 30초 머무름 임계 |
| Q4 | 정확도 게이트 | accuracy ≤ 50m |
| Q5 | 차순위 핀 | Set 누적 → 다음 콜백부터 자동 평가 |
| Q6 | 권한 UX | 조용히 비활성 |
| Q7 | PATCH 흐름 | 2회 분리, 메모는 선택 |
| Q8 | 알림 | 무조건 발송, VISIT_DETECTED 신규 유형 |
| Q9 | 전환 애니메이션 | 하트 confetti 3개 + scale, ~600ms |
