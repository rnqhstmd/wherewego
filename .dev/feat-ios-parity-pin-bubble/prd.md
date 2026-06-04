# PRD: 핀 상세 — 풀 모달 시트 → 말풍선 오버레이 (P8 영역 2)

> iOS↔프론트엔드 정합성(P8) 4영역 중 **영역 2**. 분류: 명확한 웹 정합 버그(제품 결정 불필요, 웹에 맞춰 수정).
> 분석 원본: `.dev/feat-ios-nav-redesign/frontend-parity-findings.md` (영역 2), `.dev/feat-ios-native-swiftui/roadmap.md` (P8).

## 확정 결정 (2026-06-04, 사용자 승인)
- **D-1 구현 방식**: 정식 말풍선 오버레이 — 마커 화면좌표 실시간 추적 + 꼬리 포함. 웹 `SpeechBubblePopup` 완전 정합. (간이 비-모달 카드 안 폐기)
- **D-2 동일 핀 재탭**: 말풍선 **유지**(웹 동일). 토글(재탭 닫힘) 아님.
- **D-3 화면 가장자리**: 위치 **clamp 없음**(웹 overflow clip 동일). 마커가 화면 밖으로 나가면 말풍선 **숨김**(FR-8)만 적용.

## 배경
현재 iOS는 핀 탭 시 `MapView.swift`의 `selectedPinId` → `.sheet`로 `PinDetailSheet`(NavigationStack + ScrollView 풀 모달)를 띄워 지도를 완전히 가린다. 사용자는 핀의 위치 맥락을 잃는다. 웹은 동일 상황에서 `SpeechBubblePopup`(꼬리 달린 말풍선)을 마커 위에 띄우고, `map.project([lng,lat])`로 화면좌표를 추적하여 pan/zoom 시 마커를 실시간으로 따라간다. 상세 내용·액션은 동일. 좌표→화면점 투영(`mapboxMap.point(for:)`)은 현재 `MapboxMapView`에 미노출 상태다.

## 목표
- 핀 탭 시 지도가 가려지지 않고 말풍선이 마커 위에 붙어 표시되며 pan/zoom 시 실시간 추적
- 웹 `SpeechBubblePopup`과 시각·상호작용 정합
- 기존 핀 상세 액션(태그·장소명·메모·사진·삭제) 동작 무변경 유지

### 비목표
- 영역 1(핀 추가 인라인화)·3(채팅)·4(탭바) 변경
- 백엔드·프론트엔드 코드 변경
- 말풍선 UI 외 새 핀 상세 기능 추가
- Mapbox 미설정(stub) 환경 시각 검증(DoD-B: Mac+토큰)

## 요구사항

### 기능 요구사항
- [Must] FR-1: 마커 탭 시 `PinDetailSheet` 풀 모달 대신 `PinBubbleView` 오버레이가 지도 위에 표시. 지도는 말풍선 뒤에서 계속 보임.
- [Must] FR-2: 말풍선은 선택 마커의 화면좌표 위에 앵커링. 하단 꼬리가 마커 중심 위를 가리킴.
- [Must] FR-3: 지도 pan·zoom(및 회전/피치) 시 선택 마커 화면좌표를 `onCameraChanged`마다 재계산하여 말풍선이 실시간 추적.
- [Must] FR-4: 기존 핀 상세 액션 — 태그 변경(REEL/WISH/MEMORY), 장소명 편집, 메모 편집, 사진 업로드/삭제, 핀 삭제 — 이 말풍선 내에서 동일 작동. `PinDetailViewModel` 액션 로직 재사용.
- [Must] FR-5: 말풍선 바깥 지도 영역 탭 시 말풍선 닫힘 + 선택 해제(`selectedPinId = nil`).
- [Must] FR-6: 다른 마커 탭 시 현재 말풍선 즉시 해제 + 새 마커 말풍선 표시(모드/에러/편집 상태 초기화 = 웹 `trackedPinId` 리셋 동치).
- [Must] FR-7: 핀 삭제 확인 후 삭제 완료 시 말풍선 자동 닫힘.
- [Must] FR-11 (D-2): 이미 선택된 핀을 재탭해도 말풍선 유지(웹 동일, 토글 아님). 상태 변화 없음.
- [Should] FR-8 (D-3): 마커가 화면 가장자리 밖으로 이동하면 말풍선 숨김(화면 안으로 복귀 시 재표시). 위치 clamp는 적용하지 않음.
- [Should] FR-9: 말풍선 콘텐츠가 최대 높이 초과 시 내부 스크롤 활성화(팝업 자체 크기는 고정 최대값 이내).
- [Could] FR-10: 말풍선 표시/해제 시 짧은 fade 또는 scale 애니메이션.

### 비즈니스 규칙
- [Must] BR-1: 동시 1개 말풍선. `MapViewModel.selectedPinId` 단일 선택 상태 그대로 사용.
- [Must] BR-2: 말풍선이 열린 동안 `addPlace`/`roulette`/`visitMemo` 시트가 열리면 말풍선 닫힘(`activeSheet` 동시 1패널 규칙 유지).
- [Must] BR-3: 사진 업로드/삭제 진행 중에는 바깥 탭 닫기 제스처 무시. 완료 후 닫기 가능.
- [Must] BR-4: `MapboxMapView`에 좌표→화면점 투영 `point(for:)` 노출. `onCameraChanged`마다 선택 핀 화면좌표 재계산 → SwiftUI offset 반영. (`MapRenderer` 프로토콜에도 동등 시그니처 노출, `import MapboxMaps` 단일 격리 게이트 유지)
- [Must] BR-5: Mapbox 미설정(stub/PlaceholderMapView)에서는 마커 탭 자체가 불가하므로 말풍선 미표시. 별도 분기 불필요(투영 메서드는 stub에서 nil 반환).
- [Should] BR-6: `currentPin`(`MapViewModel.pins` 단일 출처)이 nil이 되면(다른 사용자 삭제 등) 말풍선 자동 닫힘. 기존 `PinDetailSheet`의 `currentPin == nil` → dismiss 동일.

### 품질 기대
- [Should] QE-1: 지도 pan/zoom 중 말풍선 위치 갱신이 프레임 드롭 없이 따라옴(시뮬레이터 확인, DoD-B).
- [Should] QE-2: 빠른 연속 마커 탭 시 말풍선이 잘못된 핀 데이터를 표시하지 않음(loadDetail race 방지).
- [Should] QE-3: 사진 로딩 중 상태가 말풍선 내 시각 표시(스피너/플레이스홀더).

## 사용자 시나리오
**정상**: 마커 탭 → 마커 위 꼬리 말풍선 표시(지도 유지) → 지도 드래그 시 말풍선 추적 → 메모 수정·저장(말풍선 유지, 내용 갱신) → 바깥 탭 닫힘.
**다른 핀 탭**: 말풍선 A 열린 상태에서 B 탭 → A 사라지고 B 위치에 새 말풍선 → 모드/에러/편집 초기화.
**핀 삭제**: 삭제 버튼 → 확인 다이얼로그 → 확인 시 삭제+말풍선 자동 닫힘+마커 제거 / 취소 시 유지.
**예외**: 사진 업로드 중 바깥 탭 → 완료 전 닫힘 무시(BR-3). 마커 화면밖 이동 → 숨김, 복귀 시 재표시(FR-8). 편집 중 다른 시트 열림 → 말풍선 닫힘, 편집 내용 미저장 폐기(기존 동작 유지, 저장 확인 없음).

## 엣지 케이스
- 지도 회전(bearing)/피치(tilt) 변경: `onCameraChanged` 처리로 정상 추적.
- 동일 핀 연속 빠른 탭: 중복 loadDetail 방지. 이미 선택된 핀 재탭 시 상태 변화 없음(D-2 유지).
- 메모/사진 없는 핀: 빈 영역이어도 말풍선 정상 표시(내부 레이아웃이 빈 상태 처리).
- 화면 상단/좌우 끝 마커: 말풍선이 화면 경계 벗어나면 잘림(D-3 clamp 없음).
- 작은 화면(iPhone SE): 말풍선 최대 폭 제한 적용.
- `loadDetail` 네트워크 중 다른 핀 탭: 이전 요청 취소 또는 결과 무시(race 방지).

## 영향 범위
- 변경: `MapboxMapView.swift`(`point(for:)` 투영 노출 + `onCameraChanged` 선택핀 화면좌표 방출), `MapRenderer.swift`(프로토콜에 투영 시그니처), `MapView.swift`(`.sheet` PinDetailSheet 연결 제거), `MapContainerView.swift`(ZStack 말풍선 오버레이 삽입), `MapViewModel.swift`(선택 핀 화면좌표 상태 추가).
- 신규: `PinBubbleView.swift`(말풍선 오버레이 뷰).
- 재사용: `PinDetailViewModel` 액션 메서드 그대로 호출, `MapViewModel.selectedPinId` 상태 그대로.
- 비활성화: `PinDetailSheet.swift`(시트 연결 제거. 파일 삭제는 별도 정리로 가능 — 본 작업은 비활성화만).
- 백엔드/프론트엔드 영향: 없음(API 계약 무변경, 웹은 정합 기준일 뿐).

## 수용 기준
- AC-1: 마커 탭 시 풀 모달 시트가 열리지 않고 마커 위 말풍선 오버레이 표시, 지도 계속 보임. → FR-1
- AC-2: 말풍선 꼬리가 해당 마커 화면 중심 위를 가리킴. → FR-2
- AC-3: 지도 pan 중 말풍선이 마커 실시간 추적. zoom 변경 시도 동일. → FR-3
- AC-4: 말풍선 내 태그 변경·장소명·메모·사진 업로드·삭제 정상 작동. → FR-4
- AC-5: 바깥 지도 탭 시 말풍선 닫힘 + `selectedPinId` nil. → FR-5
- AC-6: 말풍선 A 열린 상태에서 B 탭 → A 사라지고 B 말풍선 표시. → FR-6, BR-1
- AC-7: 핀 삭제 확인 완료 후 말풍선 자동 닫힘. → FR-7
- AC-8: 사진 업로드 중 바깥 탭해도 말풍선 닫히지 않음. → BR-3
- AC-9: `addPlace`/`roulette`/`visitMemo` 시트가 열린 동안 말풍선 **일시 숨김**(`selectedPinId` 보존, 시트 닫으면 복귀). → BR-2, D-4
- AC-10: stub 환경에서 마커 탭 미발생 → 말풍선 미표시. → BR-5
- AC-11: `currentPin` nil 감지 시 말풍선 자동 닫힘. → BR-6
- AC-12: 연속 빠른 다른 핀 탭 시 최종 선택 핀 데이터만 표시. → QE-2
- AC-13: 이미 선택된 핀 재탭 시 말풍선 유지(닫히지 않음). → FR-11(D-2)
- AC-14: 마커가 화면 밖으로 나가면 말풍선 숨김, 복귀 시 재표시(위치 clamp 없음). → FR-8(D-3)

## 제외 범위
- `PinDetailSheet.swift` 파일 삭제(비활성화만, 정리는 별도 커밋).
- 말풍선 내 사진 크롭/편집 뷰(기존 동작 재사용).
- 웹 `PinPopup` 좌표 편집 모드(iOS 미해당 시 제외).
- DoD-B(Mac+토큰) 실렌더 시각 검증(동작 요구사항만 정의, 시각 검증은 Mac에서 별도).
