# PRD: P8 영역1 — iOS 핀 추가 인라인화

## 배경

웹(MapClient)은 ＋ 진입 시 별도 시트 없이 메인 지도 위에 **중앙 고정 십자선 + 얇은 하단 카드**를 오버레이하는 인라인 방식으로 핀 위치를 선택한다. 지도는 하나뿐이며, 사용자가 지도를 드래그하면 중심 좌표가 실시간으로 하단 카드에 반영된다(moveend 추적). 검색 기능은 하단 카드 내 검색창으로 통합된다.

iOS(현재)는 ＋ 탭 또는 EmptyMapCard 진입 시 `AddPlaceSheet` 풀시트를 띄우고, 그 시트 안에 독립 `MapContainerView` 인스턴스를 두어 콕찍기를 처리한다. 검색도 이 시트 안에서 통합 처리한다. 이 구조는 웹과 명확히 다르며 웹 정합 버그로 분류된다(frontend-parity-findings.md §영역1).

이번 작업은 iOS의 핀 추가 진입 흐름을 **메인 지도 위 인라인 오버레이**로 전환하는 것이다. 백엔드·웹 변경 없음.

## 목표

- ＋ 진입 시 풀시트 제거 → 메인 지도 위 중앙 고정 십자선 + 얇은 하단 확정 카드로 교체
- 시트 전용 독립 지도 인스턴스 제거 → 메인 지도 `cameraIdle` 재사용
- `AddPlaceViewModel`의 핵심 로직(onMapMoved / createPin / 역지오코딩 / 검색) 보존
- 웹과 동일한 "지도 드래그 → 중심 좌표 추적 → 하단 카드 표시 → 확정" 흐름 구현

## 요구사항

### 기능 요구사항

- [Must] FR-1: ＋ 탭(FloatingTabBar) 탭 시 `AddPlaceSheet` 풀시트 대신 **인라인 추가 모드**로 전환한다. 탭 선택(selection)은 변경되지 않는다.
- [Must] FR-2: EmptyMapCard "장소 검색하기" 버튼 진입도 인라인 추가 모드로 전환한다(기존 `.addPlace` 시트 경로 통일).
- [Must] FR-3: 인라인 추가 모드 진입 시 메인 지도 위 **중앙 고정 십자선 오버레이**를 표시한다. 십자선은 지도 정중앙에 고정되어 지도가 드래그될 때도 화면상 위치가 변하지 않는다. 웹 `CrosshairOverlay`와 동일하게 가로선·세로선·중앙점으로 구성되며 터치 이벤트를 통과시킨다(`allowsHitTesting(false)`).
- [Must] FR-4: 인라인 추가 모드 진입 시 하단에 **얇은 확정 카드**를 표시한다. 카드 구성 요소: 검색 입력창(FR-10), 역지오코딩 주소 또는 좌표 폴백 표시, 태그 선택(릴스/위시/추억 3종), "여기 등록" 버튼, "취소" 버튼.
- [Must] FR-5: 메인 지도 `cameraIdle` 이벤트를 인라인 추가 모드에서 콕찍기 중심 좌표로 재사용한다. 별도 지도 인스턴스를 생성하지 않는다.
- [Must] FR-6: 지도 드래그(cameraIdle 발생) 시 `AddPlaceViewModel.onMapMoved(center:)`를 호출하여 하단 카드 주소를 실시간 갱신한다. 디바운스 300ms 유지.
- [Must] FR-7: "여기 등록" 확정 시 `AddPlaceViewModel.createPin(tag:)`를 호출한다. 성공 시 인라인 추가 모드를 종료하고 생성된 핀 위치로 카메라 이동한다.
- [Must] FR-8: "취소" 버튼 또는 모드 종료 조건에서 인라인 추가 모드를 종료한다. 모드 종료 시 십자선과 하단 카드가 사라진다.
- [Must] FR-9: 인라인 추가 모드 진입 시 현재 메인 지도 중심 좌표를 초기 콕찍기 위치로 사용한다. 진입 즉시 역지오코딩이 트리거된다. 기존 `AddPlaceViewModel.initialCameraTarget`의 중심 좌표 참조 로직 재사용 가능.
- [Must] FR-10: 하단 확정 카드 내에 **검색 입력창**을 제공한다. 검색어 제출 시 `AddPlaceViewModel.search()`를 호출하고 결과 목록을 카드 위에 표시한다. 결과 선택 시 메인 지도가 해당 좌표로 flyTo되고 하단 카드 주소·확정 위치가 선택 장소로 갱신된다. 콕찍기 전환 시 검색어는 초기화된다(기존 `onMapMoved`의 `query = ""` 유지).
- [Should] FR-11: 인라인 추가 모드 진입 시 메인 지도 줌 레벨이 13 미만이면 아래 우선순위로 줌인한다(웹 MapClient.tsx:1043-1066 동치):
  1. 위치 권한 허용 상태이고 이미 획득한 좌표가 있으면 → 해당 위치로 flyTo(zoom 15)
  2. 위치 권한 미결/요청 가능이면 → one-shot 위치 요청 후 성공 시 flyTo(zoom 15), 실패(5초 타임아웃) 시 현재 지도 중심 유지하며 zoom 14로만 올림
  3. 위치 권한 거부 상태이면 → 현재 지도 중심 유지하며 zoom 14로만 올림

### 비즈니스 규칙

- [Must] BR-1: 인라인 추가 모드 활성 중 다른 탭(채팅·알림·내정보) 전환 시 모드를 자동 종료한다. 작성 중인 위치 정보는 폐기한다.
- [Must] BR-2: 인라인 추가 모드 활성 중 마커 탭(핀 상세 진입)은 차단한다. 십자선 모드 중 선택 충돌을 방지한다.
- [Must] BR-3: 핀 생성 진행 중(`isCreating == true`)에는 지도 드래그에 의한 좌표 갱신이 차단된다(기존 `onMapMoved`의 `guard !isCreating` 유지).
- [Must] BR-4: 역지오코딩 실패 시 좌표 문자열 폴백(`ReverseGeocoder.coordinateFallback`)을 하단 카드에 표시한다. 역지오코딩 진행 중에는 "주소를 찾는 중..." 안내를 표시한다.
- [Must] BR-5: 장소명/좌표 유효성 검증(`validatePinInput`: 장소명 ≤200자, 좌표 범위)은 기존 로직을 그대로 유지한다.
- [Should] BR-6: 인라인 추가 모드 활성 중 룰렛 시트 진입 요청이 들어오면 추가 모드를 먼저 종료 후 룰렛을 연다.

### 품질 기대

- [Should] QE-1: 십자선과 하단 카드의 등장/퇴장에 자연스러운 전환 애니메이션(opacity 또는 slide)을 적용한다.
- [Should] QE-2: 하단 카드가 FloatingTabBar와 겹치지 않도록 탭바 높이만큼 bottom padding을 확보한다.

## 사용자 시나리오

### 정상 흐름 — 콕찍기로 핀 추가

1. 사용자가 ＋ 탭을 탭한다.
2. 메인 지도 위 중앙에 십자선이 나타나고 하단에 확정 카드가 슬라이드업된다.
3. (FR-11 조건 해당 시) 줌 < 13이면 자동 줌인이 발생한다.
4. 진입 즉시 현재 지도 중심 좌표의 역지오코딩이 시작되어 하단 카드에 "주소를 찾는 중..."이 표시된다.
5. 역지오코딩 완료 시 주소가 하단 카드에 표시된다.
6. 사용자가 지도를 드래그하면 십자선은 화면 중앙에 고정된 채 지도만 이동하고, 300ms 디바운스 후 새 중심의 역지오코딩이 다시 트리거된다.
7. 원하는 위치에서 태그를 선택하고 "여기 등록"을 탭한다.
8. 핀 생성 성공 시 인라인 모드가 종료되고 생성된 핀 위치로 지도가 flyTo된다.

### 정상 흐름 — 검색으로 핀 추가

1. 사용자가 ＋ 탭 진입 후 하단 카드의 검색창에 장소명을 입력하고 제출한다.
2. 검색 결과 목록이 카드 위에 표시된다.
3. 결과를 선택하면 메인 지도가 해당 위치로 flyTo되고 하단 카드에 장소명·주소가 갱신된다.
4. 태그를 선택하고 "여기 등록"을 탭하면 핀이 등록된다.

### 정상 흐름 — 취소

1. 사용자가 ＋ 탭 진입 후 "취소"를 탭한다.
2. 십자선과 하단 카드가 사라지고 지도가 원래 상태로 돌아온다.

### 예외 흐름 — 다른 탭 전환 중 모드 종료

1. 인라인 추가 모드 활성 중 사용자가 채팅 탭을 탭한다.
2. 인라인 추가 모드가 자동 종료(위치 정보 폐기)되고 채팅 탭으로 전환된다.

### 예외 흐름 — 역지오코딩 실패

1. 지도 드래그 후 역지오코딩 API 호출 실패.
2. 하단 카드에 좌표 문자열 폴백(예: `37.5665000, 126.9780000`)이 표시된다.
3. "여기 등록"은 정상 활성화되어 등록을 계속할 수 있다.

### 예외 흐름 — 줌 < 13 진입, 위치 권한 거부

1. ＋ 탭 진입 시 줌 레벨 8, 위치 권한 거부 상태.
2. 현재 지도 중심을 유지하면서 zoom 14로만 올린다.
3. 이후 정상적으로 콕찍기·검색 흐름으로 진행된다.

### 엣지 케이스

- EmptyMapCard에서 진입 시 ＋ 탭과 동일한 인라인 모드 진입 흐름을 따른다.
- 인라인 추가 모드 중 룰렛 버튼 탭 → 추가 모드 종료 후 룰렛 시트 열림.
- 인라인 추가 모드 중 방문 토스트 표시 → 토스트는 십자선 위에 ZStack 레이어로 표시되며 추가 모드는 유지된다.
- 핀 생성 중(`isCreating`) 지도 드래그 → 좌표 갱신 차단(`onMapMoved`의 guard).
- 네트워크 오류로 핀 생성 실패 → 하단 카드에 에러 메시지 표시, 모드 유지.
- 그룹 미선택(`groupId == nil`) 상태에서 "여기 등록" → 에러 메시지 표시.
- 검색 결과 선택 후 지도 드래그 → 콕찍기 모드로 전환되고 검색 선택 결과가 초기화된다(기존 `onMapMoved`의 `selectedPlace = nil` 유지).
- 검색 결과 0건 → "검색 결과가 없어요" 안내 표시.
- Mapbox 토큰 미설정 시(`PlaceholderMapView` 폴백) → 십자선은 표시되나 역지오코딩 미동작, 좌표 폴백으로 등록 가능 여부는 Mac 검증 필요(DoD-B).

## 영향 범위

**영향받는 기존 기능:**
- `AddPlaceSheet` — 풀시트 + 독립 지도 인스턴스 구조가 인라인 오버레이로 전면 교체됨. 파일 자체는 신규 `InlineAddPlaceOverlay` 류 뷰로 재구성되거나 내부 구조가 대폭 변경됨
- `MainTabView` — `showAddPlace` 플래그(시트 표시) → 인라인 모드 토글 신호로 변경. `.sheet(isPresented: $showAddPlace)` 제거
- `MapView` — `.sheet(isPresented: addPlaceSheetBinding)` 제거. `loadedOverlay` ZStack에 십자선·확정 카드 오버레이 추가
- `MapViewModel.ActiveSheet` — `.addPlace` case의 의미가 시트 표시 → 인라인 모드 활성으로 변경되거나, 별도 `@Published var isAddingPin: Bool` 상태로 분리됨
- `MapboxMapView(Coordinator)` — `cameraIdle` 이벤트가 기존 방문 감지용 + 인라인 추가 모드 중심 추적으로 병용됨(이미 노출되어 있어 추가 SDK 변경 불필요)

**기존 사용자 영향:**
- ＋ 진입 UX가 풀시트 → 인라인으로 변경됨. 기존 핀 데이터, 그룹 정보에는 영향 없음.

**하위 호환성:**
- 백엔드 API 변경 없음. `createPin` 요청 구조 동일.

## 수용 기준

### 코드/로직 레벨 확인 가능

- AC-1: `MainTabView`의 ＋ 탭 액션이 `AddPlaceSheet` 시트 표시(`showAddPlace = true` + `.sheet`) 경로가 아닌 인라인 추가 모드 활성 신호로 변경되어 있다. → [FR-1]
- AC-2: `MapView`(또는 `MapContainerView`)의 ZStack에 `isAddingPin == true` 조건부로 십자선 오버레이 뷰가 추가되어 있다. 뷰는 `allowsHitTesting(false)`이다. → [FR-3]
- AC-3: `MapView`(또는 `MapContainerView`)의 ZStack에 `isAddingPin == true` 조건부로 하단 확정 카드 뷰가 추가되어 있다. 카드는 검색 입력창, 주소/좌표 표시 영역, 태그 선택(3종), "여기 등록" 버튼, "취소" 버튼을 포함한다. → [FR-4, FR-10]
- AC-4: `MapView`의 `.sheet(isPresented: addPlaceSheetBinding)` 코드가 제거되고, `MainTabView`의 `.sheet(isPresented: $showAddPlace)` 코드가 제거되어 있다. → [FR-1, FR-2]
- AC-5: 메인 지도의 `cameraIdle` 이벤트 핸들러가 인라인 추가 모드 활성(`isAddingPin == true`) 시 `AddPlaceViewModel.onMapMoved(center:)`를 호출하도록 분기되어 있다. → [FR-5, FR-6]
- AC-6: `AddPlaceViewModel.onMapMoved`의 디바운스 300ms, `resolveAddress` 역지오 로직, `ReverseGeocoder.coordinateFallback` 폴백이 변경 없이 재사용된다. → [FR-6, BR-4]
- AC-7: `AddPlaceViewModel.createPin(tag:)`가 인라인 확정 카드의 "여기 등록"에 연결되어 있다. `didCreate == true` 관찰 시 `isAddingPin = false`(또는 동등한 모드 종료 처리)가 실행된다. → [FR-7]
- AC-8: 하단 확정 카드의 검색 입력창이 `AddPlaceViewModel.query`에 바인딩되어 있고, 제출 시 `AddPlaceViewModel.search()`를 호출한다. → [FR-10]
- AC-9: 검색 결과 선택 시 `AddPlaceViewModel.selectResult(_:)`가 호출되고, 메인 지도 `cameraCommand`에 해당 좌표의 `CameraTarget`이 설정된다. → [FR-10]
- AC-10: `AddPlaceViewModel.onMapMoved` 내 `query = ""` / `selectedPlace = nil` 초기화 코드가 유지된다(콕찍기 전환 시 검색 상태 초기화). → [FR-10]
- AC-11: `MapViewModel.ActiveSheet`에서 `.addPlace` case가 제거되거나 인라인 모드를 나타내는 `isAddingPin: Bool` 등의 별도 Published 상태가 추가되어 있다. → [FR-1, FR-2]
- AC-12: 탭 전환(`selection` 변경) 시 인라인 추가 모드를 종료하는 로직이 `MapView.onChange(of: selection)` 또는 `MainTabView`에 존재한다. → [BR-1]
- AC-13: 인라인 추가 모드 활성 중 `selectedPinId` 세팅이 차단되거나 무시되는 조건 분기가 존재한다. → [BR-2]
- AC-14: 하단 확정 카드의 bottom padding 또는 offset 값이 FloatingTabBar 높이 이상으로 코드에 명시되어 있다. → [QE-2]
- AC-15: `AddPlaceSheet.swift`의 독립 `MapContainerView` 인스턴스 관련 코드(`mapCameraCommand`, `mapFitBoundsCommand` @State, 독립 `MapContainerView` 선언)가 제거되었거나 인라인 방식으로 대체되었다. → [FR-5]
- AC-16: 인라인 추가 모드 진입 시 현재 지도 줌 레벨을 읽어 13 미만인 경우 조건 분기 코드가 존재한다. 분기 내 위치 권한 상태 확인 및 `flyTo` 호출이 포함된다. → [FR-11]

### Mac 시각·런타임 검증 필요(DoD-B)

- AC-B1: 시뮬레이터/실기기에서 ＋ 탭 시 풀시트가 뜨지 않고 메인 지도 위에 십자선과 하단 카드가 나타난다.
- AC-B2: 지도 드래그 시 십자선이 화면 정중앙에 고정되어 있고, 하단 카드 주소가 드래그 정지 후 갱신된다.
- AC-B3: 역지오코딩 진행 중 "주소를 찾는 중..." 문구가 하단 카드에 표시된다.
- AC-B4: 역지오코딩 실패 시 좌표 폴백 문자열이 하단 카드에 표시된다.
- AC-B5: "여기 등록" 후 인라인 모드가 종료되고 생성된 핀 위치로 지도가 flyTo된다.
- AC-B6: 인라인 추가 모드 중 다른 탭 전환 시 십자선·하단 카드가 사라진다.
- AC-B7: 하단 확정 카드가 FloatingTabBar와 겹치지 않는다.
- AC-B8: EmptyMapCard "장소 검색하기" 진입도 동일한 인라인 모드로 동작한다.
- AC-B9: 검색창에 장소명 입력 후 결과 선택 시 메인 지도가 해당 위치로 flyTo되고 하단 카드가 갱신된다.
- AC-B10: 줌 < 13 상태에서 ＋ 진입 시 위치 권한 상태에 따라 적절한 줌인이 발생한다(권한 허용 → zoom 15 현재 위치, 거부 → zoom 14 현재 중심 유지).

## 제외 범위

- 검색 탭을 별도 독립 패널로 분리하는 웹 정합 재구성은 이번 범위 외 (검색은 하단 카드 내 통합으로 확정)
- 핀 추가 후 태그·메모 편집 단계의 웹 정합(MemoTagPanelContent 동치)은 별도
- 영역2(핀 상세 말풍선), 영역3(채팅), 영역4(플로팅바) 수정은 이번 범위 외
- `point(for:)` 좌표→화면점 투영 노출(영역1·2 공통 선행)은 영역2 작업 시 처리; 이번 영역1은 `cameraIdle` 중심 좌표 재사용만으로 요건 충족
- Mapbox 토큰 발급 및 Mac 최종 빌드 검증(DoD-B)
