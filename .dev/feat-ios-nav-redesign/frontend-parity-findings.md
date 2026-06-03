# P8 — iOS↔프론트엔드 기능 정합성 분석 (다음 Phase 작업 기준)

> 작성: 2026-06-04. P7(내비 재설계) 머지 후, 시뮬레이터 실행하며 발견한 웹↔iOS 동작 차이.
> 다음 작업(P8) = **프론트엔드(frontend/) 코드 기준으로 iOS 기능 정합성 확인 및 수정**.
> 전제: 카카오 로그인·Mapbox 지도(웹과 동일 커스텀 스타일)·폰트는 동작 확인됨.

## 핵심 결론
P7 재설계가 **의도적으로 웹과 다르게** 만든 부분(인앱 채팅·5탭 플로팅)이 있어, "웹과 동일"이 영역별로 다른 의미를 가짐:
- 영역 1·2(핀 추가·핀 상세) = **명확한 웹 정합 버그** → iOS를 웹에 맞춰 수정.
- 영역 3·4(채팅·5탭바) = **웹엔 원래 없는 기능** → 제품 결정 필요(제거 vs 유지).

## 영역 1: 핀 추가 — 인라인화 (난이도: 대)
- 웹: 메인 지도 위 **인라인 십자선**(`frontend/src/app/map/_components/CrosshairOverlay.tsx`) + 얇은 하단 카드(`AddPinPickerContent.tsx`). `map.getCenter()`+`moveend`로 중심 추적. 검색/추가가 별도 진입점.
  - 근거: `frontend/src/app/map/MapClient.tsx:2055-2057`, `1035-1067`, `1112-1136`.
- iOS 현재: ＋ → **별도 시트(`AddPlaceSheet`)**, 시트 안 또 다른 지도 인스턴스에서 콕찍기.
  - 근거: `ios/WhereWeGo/Features/Map/AddPlaceSheet.swift:142-163`, `MainTabView.swift:127-129`, `MapView.swift:106-108`.
- 수정안: ＋/EmptyMapCard 진입을 **메인 `MapContainerView` 위 중앙 고정 핀 오버레이 + 얇은 하단 확정 카드**로. 중심 좌표는 메인 지도 `cameraIdle`(`MapboxMapView.swift:62-69`) 재사용. 시트 전용 지도 제거. `AddPlaceViewModel.onMapMoved/createPin` 로직은 재사용.

## 영역 2: 핀 상세 — 말풍선 팝업 (난이도: 대)
- 웹: 마커에 붙는 **말풍선(SpeechBubblePopup, 꼬리 포함)**. `map.project([lng,lat])`로 화면좌표 추적, `move/zoom`마다 갱신.
  - 근거: `frontend/src/app/map/_components/PinPopup.tsx:123-138`, `frontend/src/components/ui/SpeechBubblePopup.tsx:193-204,497-521`, `MapClient.tsx:2058-2090`.
- iOS 현재: `selectedPinId` → **풀 모달 시트 `PinDetailSheet`**("핀 상세" + 닫기).
  - 근거: `ios/WhereWeGo/Features/Map/MapView.swift:102-104`, `PinDetailSheet.swift:53-88`.
- 수정안: `.sheet` 제거 → `loadedOverlay` ZStack에 **말풍선 오버레이**(신규 `PinBubbleView`). 마커 앵커링 = Mapbox iOS `mapboxMap.point(for: coord)`를 `MapboxMapView`/`MapRenderer`에 노출(현재 미노출), `onCameraChanged`마다 선택핀 화면좌표 재계산 → SwiftUI offset. 기존 액션(`changeTag/saveMemo/savePlaceName/deletePin`) 재사용.
  - 간이안(중): 시트 대신 `.presentationDetents([.height(280)]) + .presentationBackgroundInteraction(.enabled)` 비-모달 카드 — "전환 느낌"만 제거(말풍선 꼬리·추적 미구현).
- 공통 선행: 영역 1·2 모두 **좌표→화면점 투영(`point(for:)`) 노출 + 메인 지도 ZStack 오버레이 패턴**이 기반. 한 번에 설계하면 재사용.

## 영역 3: 채팅 "재연결중…" (제품 결정 필요)
- **웹엔 인앱 실시간 채팅이 없음**(STOMP/WebSocket/SockJS 의존성·코드 0건). 웹 "봇"=카카오톡 채널 6자리 연동 코드 화면(`frontend/src/app/bot/connect/BotConnectClient.tsx`), 실제 대화는 카카오톡 앱.
- iOS는 P7에서 **네이티브 인앱 STOMP 채팅 신규 구현** → 연결 실패로 "재연결중" 배너 지속(`ChatScrollContainer.swift:42-53`, `BotChatViewModel.swift:29,275-283`).
- 연결 실패 유력 원인: `API_BASE_URL` 폴백/`/ws/chat` 도달성, userId/토큰 미확보 시 subscribe 거부 루프(`ChatRealtimeService.swift:251-266,348-380`, `StompClient.swift:259-278`). 백엔드 `/ws/chat`(`WebSocketStompConfig.java:30`)은 정상.
- **결정 필요**: (A) 웹 정합 = 인앱 채팅 제거/숨김 + 카카오 연동 화면으로 대체, (B) 유지 + 연결 진단·루프 억제. 4탭 전환 시 영역 4와 함께 결정.

## 영역 4: 하단 플로팅 5탭바 (제품 결정 + 시각 수정)
- **웹엔 5탭 플로팅 바 없음**. 웹=지도 전용 액션바(`ActionBar.tsx`, 검색/추가/어디갈까/알림 둥근 사각 카드) + 상단 프로필(`MobileTopNav.tsx`)/좌측 필(`DesktopActionPill.tsx`).
- iOS=P7 신규 5탭 플로팅 Capsule(`FloatingTabBar.swift`).
- "이상함" 원인: ① **Liquid Glass 미구현**(iOS26 분기/폴백 동일 코드 = 불투명 흰 캡슐, `FloatingTabBar.swift:100-122`) ② safe area 미처리 ③ **콘텐츠 겹침**(내위치/룰렛 버튼 `MapView.swift:263,252`, 채팅 입력바 `BotChatView.swift:122-124`가 탭바에 가림).
- 수정안: Liquid Glass(`.glassEffect`/`.ultraThinMaterial`) 적용, safe area inset, 지도 플로팅 버튼·탭 콘텐츠 하단 패딩을 탭바 높이만큼 확보. 5탭 유지 vs 웹 액션바 회귀는 결정 필요(5탭 유지가 현실적).

## 우선순위
1. 영역 3(재연결중) — 제품 결정 우선(웹엔 없음 → 제거가 정합). + API_BASE_URL/ws 진단(소).
2. 영역 4(플로팅바) — Liquid Glass + 겹침(소~중), 즉시 체감 개선.
3. 영역 2(핀 상세 말풍선) — 간이안(중) 또는 정식 오버레이(대).
4. 영역 1(핀 추가 인라인) — 대, 메인지도 오버레이 + cameraIdle.

## 리스크
- 영역 3·4 "웹과 동일"의 정의 모호(웹엔 없음) → 제거 시 P7 작업 상당 폐기. **사용자 확인 필수.**
- 영역 1·2: `point(for:)` 투영 노출 공통 선행, 마커 추적 성능 튜닝.
- 영역 3 연결 실패는 환경 의존(서버/토큰/네트워크) — `StompClient`/`ChatRealtimeService` 실패 로깅 선행.
