# 설계서 v2: 핀 상세 — 풀 모달 시트 → 말풍선 오버레이 (iOS P8 영역 2)

> PRD: `prd.md`. design-critic 비판(MUST-ADDRESS 4건) + 사용자 결정 D-4/D-5 반영 확정본.
> 설계 규모: **대형**. 격리 게이트(`import MapboxMaps` 1파일) 유지. 빌드: Windows DoD-A(stub) / 실렌더·시각 Mac DoD-B.

## 확정 결정
- D-1 정식 오버레이(화면좌표 실시간 추적+꼬리), D-2 동일 핀 재탭 유지, D-3 clamp 없음(화면밖 숨김만).
- D-4 BR-2 시트 충돌 시 **일시 숨김** — 표시조건 `selectedPin != nil && activeSheet == .none`(선언적 파생, selectedPinId 보존, 부수효과 금지). 시트 닫으면 복귀.
- D-5 공통 콘텐츠 `PinDetailContent` 추출, `PinBubbleView`가 래핑. **`PinDetailSheet.swift` 이번 작업에서 삭제**.

## 변경 범위
| 파일 | 구분 | 핵심 변경점 |
|------|------|------------|
| `Core/Map/MapRenderer.swift` | 수정 | `struct ScreenPoint{x,y:Double}`(SDK·UIKit 비노출), `MapEvent.markerTapped(pinId:Int, screenPoint:ScreenPoint?)`, `MapEvent.cameraMoved(screenPoint:ScreenPoint?)`, 프로토콜 `func point(for latitude:longitude:)->ScreenPoint?` |
| `Core/Map/MapboxMapView.swift` | 수정 | handleMapTap(215-232) markerTapped에 마커 feature 좌표 투영 운반, onCameraChanged(62-69) 선택핀 추적 시 point(for:)→cameraMoved 방출(게이팅), `MapboxMapRenderer.point(for:)` 실구현, #else stub nil+시그니처 정합, `selectedPin:(lat,lng)?` 추적 입력 |
| `Core/Map/MapContainerView.swift` | 수정 | 지도+오버레이 **동일 ZStack 좌표공간**(alignment:.topLeading + ignoresSafeArea 일괄), GeometryReader geo.size→updateMapSize, BubbleOverlay 관찰 격리, selectedPin 추적입력 통과 |
| `Core/Map/PlaceholderMapView.swift` | 변경 없음 | 렌더러 stub이 point(for:) 담당 |
| `Features/Map/MapViewModel.swift` | 수정 | `@Published private(set) selectedPinScreenPoint:ScreenPoint?`, `lastMapSize`, handle markerTapped 좌표 즉시세팅/cameraMoved distinct+화면밖판정, 재탭 가드(D-2), `clearSelectedPinScreenPoint()`/`updateMapSize()`, 삭제 시 nil |
| `Features/Map/MapView.swift` | 수정 | `.sheet(item:)` PinDetailSheet **제거**, 오버레이를 MapContainerView로 이동, selectedPin/screenPoint/콜백 전달, 표시조건 D-4 |
| `Features/Map/PinBubbleView.swift` | **신규** | 말풍선 컨테이너+꼬리 Path+detailVM(@StateObject)+전체화면 투명배경탭(BR-3). PinDetailContent 래핑 |
| `Features/Map/PinDetailContent.swift` | **신규** | 공통 콘텐츠(섹션+편집버퍼/다이얼로그/picker/cropper @State+액션 바인딩+onRequestClose) |
| `Features/Map/PinDetailSheet.swift` | **삭제** | D-5. Xcode 멤버십 제거 필요 |
| `Features/Map/PinDetailViewModel.swift` | 변경 없음 | 그대로 재사용(weak mapViewModel) |
| `WhereWeGoTests/MapRendererMocks.swift` | 수정 | `MockMapRenderer.point(for:)` + emit(.cameraMoved/.markerTapped(screenPoint)) |
| `WhereWeGoTests/MapViewModelTests.swift` | 수정 | screenPoint 갱신/화면밖 순수판정/재탭 가드/삭제 시 nil |

## MUST-ADDRESS 해소 설계

### ① 좌표계 정렬 (AC-2)
`point(for:)`의 CGPoint = mapView.bounds 로컬 좌표(논리 pt, 원점 좌상단). `.position`은 부모 컨테이너 좌표 기준. **해결**: `MapContainerView`에서 지도(UIViewRepresentable)와 `PinBubbleView`를 **같은 ZStack 자식**으로 두고 `alignment:.topLeading` + `.ignoresSafeArea()`를 ZStack에 일괄 적용. → mapView.bounds(0,0) == ZStack 좌표공간(0,0). `point(for:)` 결과를 `.position(x:pt.x, y:pt.y)`에 그대로 사용. 노치/홈인디케이터 환경에서도 원점 함께 이동(상대오프셋 0).
- 오버레이 위치 = **MapContainerView**(MapView 아님 — MapView ZStack은 ignoresSafeArea(28) vs padding(16) 혼재로 원점 불명확). visitToast/confetti는 MapView ZStack 유지(중앙정렬, 추적 불필요).

### ② markerTapped 좌표 운반 (첫 배치)
handleMapTap이 이미 탭 좌표 보유. **마커 feature 좌표**(탭점 아닌 마커 중심)를 point(for:)로 투영해 `markerTapped(pinId, screenPoint)`로 운반 → VM이 즉시 selectedPinScreenPoint 세팅 → 탭과 동시 표시(지연 0). feature 좌표 실패 시 탭 지점 폴백. `selectedPinCoordinate` 바인딩+updateUIView 강제 emit **제거**. 첫배치=탭이벤트, 추적=onCameraChanged(selectedPin 추적입력은 추적 전용).

### ③ 성능 — onCameraChanged 격리 (QE-1)
3중 차단:
- (a) **방출 게이팅**: onCameraChanged에서 `trackedPinCoordinate == nil`이면 skip(투영·방출 안 함).
- (b) **distinctUntilChanged**: VM handle(.cameraMoved)에서 `next != selectedPinScreenPoint`일 때만 set(반올림 1pt).
- (c) **관찰 단위 분리**: 오버레이를 MapContainerView의 별도 자식 뷰(`BubbleOverlay`)로 빼서 screenPoint 변경 시 그 자식만 무효화. MapView body는 selectedPinScreenPoint 직접 안 읽음 → toast/confetti/필터바 재평가 차단.
- throttle 불필요 판단(매 프레임 작업 = 점1개 투영+1pt diff+position 갱신). DoD-B 실측 최종확인.

### ④ 화면밖 판정 순수함수 (AC-14)
`GeoMath.isPointVisible(_ p:ScreenPoint, in size:CGSize, margin:Double=0)->Bool` 순수함수(bboxContains 패턴 동형). Coordinator는 raw 투영결과만 전달, 안/밖 비교는 VM이 `lastMapSize`(MapContainerView GeometryReader geo.size→updateMapSize 보관)로 판정 → 밖이면 nil(숨김), 복귀 시 재방출 재표시. Windows 단위테스트 대상.

## PinDetailContent / PinBubbleView (D-5)
상태 소유:
- PinBubbleView: `@StateObject detailVM`(PinDetailViewModel), 말풍선 위치/꼬리/컨테이너 스타일, 전체화면 투명배경탭(BR-3 isPhotoBusy 가드).
- PinDetailContent: 편집버퍼(memoText/placeNameText/isEditing*)·다이얼로그(showDeleteConfirm/showPhotoDeleteConfirm)·picker/cropper(showPhotoPicker/pickedImage)·isMutating/inlineError @State. 섹션 = PinDetailSheet 53~485 이관(NavigationStack/ScrollView 래퍼 제거). 액션: 태그/메모/장소명/삭제→mapViewModel 낙관메서드, 사진→detailVM. 삭제 완료→onRequestClose(). `currentPin = mapViewModel.pins.first{$0.id==pin.id}`.
- 앵커링: MapContainerView가 `.position(마커점)`. PinBubbleView 본체를 마커 위로(웹 translate(-50%, calc(-100% -16px)) 동치). 꼬리 Path(웹 svg 22x12 `M 0 0 L 11 11 L 22 0 Z`) 본체 하단 중앙. frame(maxWidth:280), 초과 시 내부 ScrollView(FR-9). 정확 오프셋(GeometryReader vs 고정값)은 DoD-B 미세조정.

## 격리 게이트 (MUST-1)
`import MapboxMaps`는 MapboxMapView.swift 1개(point(for:)/markerScreenPoint는 기존 mapboxMap API, 추가 import 없음). ScreenPoint(Double) SDK·UIKit 비노출. CGSize는 SwiftUI/CoreGraphics(격리 무관). 검증: `grep -rl "import MapboxMaps" ios/WhereWeGo` == 1.

## AC 매핑
AC-1 시트제거+ZStack오버레이 / AC-2 좌표정렬+feature투영+꼬리 / AC-3 onCameraChanged추적 / AC-4 PinDetailContent액션 / AC-5 배경탭 closeBubble / AC-6 .id재생성+전환 / AC-7 deletePin→onRequestClose→nil / AC-8 배경탭 isPhotoBusy가드 / AC-9 표시조건 activeSheet==.none(D-4) / AC-10 stub selectedPin영원nil / AC-11 selectedPin파생nil / AC-12 단일출처파생 / AC-13 재탭 guard(D-2) / AC-14 isPointVisible순수판정+clamp없음(D-3). QE-1 3중차단 / QE-2 단일출처 / QE-3 photoSection스피너.

## 기존 코드 영향 (컴파일 게이트)
- MapEvent.markerTapped 시그니처 변경 + cameraMoved 추가 → handle switch exhaustive 갱신(MapViewModel:445), MockMapRenderer.emit 호출부.
- MapRenderer.point(for:) 추가 → MapboxMapRenderer(#if/#else)·MockMapRenderer 구현 필수(미구현=컴파일에러=게이트).
- MapboxMapView/MapContainerView 생성자 시그니처 변경 → 호출부 동시 갱신.
- PinDetailSheet.swift 삭제 → MapView 참조 제거 후 미참조, Xcode 프로젝트 멤버십 제거.
- 백엔드/프론트 무영향. PinDetailViewModelTests 무변경 통과.

## 구현 순서 (11단계)
1. [Must] MapRenderer.swift — ScreenPoint + MapEvent.markerTapped(pinId:screenPoint:)/cameraMoved(screenPoint:) + point(for:) 시그니처 (의존 없음)
2. [Must] GeoMath.swift — isPointVisible(_:in:margin:) 순수함수 (의존 1)
3. [Must] MapboxMapView.swift — handleMapTap 좌표운반(markerScreenPoint), onCameraChanged 게이팅+cameraMoved, point(for:) 실구현, #else stub nil+시그니처, selectedPin 추적입력 (의존 1)
4. [Must] MapViewModel.swift — selectedPinScreenPoint/lastMapSize, handle markerTapped좌표/cameraMoved distinct+화면밖, 재탭 가드, clear/updateMapSize, 삭제 시 nil (의존 1,2)
5. [Must] PinDetailContent.swift 신규 — PinDetailSheet 콘텐츠 이관+onRequestClose (의존 없음)
6. [Must] PinBubbleView.swift 신규 — 컨테이너+꼬리+배경탭+detailVM, PinDetailContent 래핑 (의존 4,5)
7. [Must] MapContainerView.swift — 동일 ZStack 좌표공간, GeometryReader→updateMapSize, BubbleOverlay 격리, selectedPin 통과 (의존 3,6)
8. [Must] MapView.swift — .sheet 제거, MapContainerView 전달, 표시조건 D-4 (의존 7)
9. [Must] PinDetailSheet.swift 삭제 + Xcode 멤버십 제거 (의존 8)
10. [Must] MapRendererMocks.swift — point(for:) + emit 갱신 (의존 1)
11. [Should] MapViewModelTests.swift — isPointVisible 경계값, markerTapped 좌표세팅, cameraMoved distinct/화면밖, 재탭 가드, 삭제 시 nil (의존 2,4,10)

병렬: 1 후 2·5 병렬. 3·4 병렬(4는 2도 의존). 6은 4·5 후. 7은 3·6 후. 동일파일 충돌 없음.

## 테스트 전략
**Windows(DoD-A)**: GeoMath.isPointVisible 경계값(AC-14). MapViewModelTests(markerTapped 좌표세팅/cameraMoved 화면밖 nil/distinct/재탭 무변화/삭제 시 nil/activeSheet 표시파생). MockMapRenderer point(for:)+emit. PinDetailViewModelTests 무변경 통과(회귀가드).
**Mac(DoD-B)**: AC-2 꼬리 앵커링/feature투영 정확도, AC-3/QE-1 추적+프레임드롭, 첫배치 지연0, 화면밖 복귀, FR-10 애니메이션, compact 폭, 웹 SpeechBubblePopup 시각정합.
**회귀**: git stash 베이스라인 대조. 시그니처 변경 컴파일에러로 누락차단. PinDetailSheet 삭제 후 미참조 확인.
