import SwiftUI

// 지도 분기 컨테이너(설계 §1, MUST-1 규칙2). `#if` 없이 항상 컴파일된다.
// MapConfig.isMapboxConfigured 여부로 MapboxMapView ↔ PlaceholderMapView 를 선택한다.
//
// 이 파일은 Mapbox 타입을 이름으로도 참조하지 않는다(MUST-1):
//  - `MapboxMapView` 는 MapboxMapView.swift 가 #if/#else 양쪽에서 동일 시그니처로 제공하는 타입이므로
//    SPM 추가 여부와 무관하게 이름 참조가 컴파일된다(SDK import 는 그 파일 내부에만 존재).
//  - ScreenPoint/PinSummary/PinBubbleView 는 비-Mapbox 타입이라 여기서 참조해도 격리 위반 아니다.
//  - 스타일/토큰은 MapConfig 단일 진입점에서 읽는다.
//
// 핀 상세 말풍선 오버레이(설계 §6, D-1):
//  - 지도(MapboxMapView)와 말풍선(PinBubbleView)을 동일 ZStack 자식(alignment .topLeading)으로 두고
//    `.ignoresSafeArea()` 를 ZStack 에 일괄 적용 → mapView.bounds(0,0) == ZStack 좌표공간(0,0)(MUST-ADDRESS①).
//    point(for:) 의 화면점을 `.position(x:y:)` 에 그대로 써서 마커에 정렬한다.
//  - GeometryReader geo.size → viewModel.updateMapSize(화면밖 판정 기준, AC-14).
//  - screenPoint 구독은 별도 자식 뷰(BubbleOverlay)로 격리 → 추적 갱신 시 그 자식만 무효화(QE-1 c).
struct MapContainerView: View {
    @ObservedObject var viewModel: MapViewModel

    var body: some View {
        GeometryReader { geo in
            // 동일 ZStack 좌표공간: 지도(0,0) == 오버레이(0,0)(MUST-ADDRESS①). ignoresSafeArea 는 ZStack 에 일괄.
            ZStack(alignment: .topLeading) {
                mapLayer

                // 표시조건 D-4: 선택핀 있고 시트 충돌(activeSheet) 없을 때만 말풍선 렌더(selectedPinId 는 보존).
                // screenPoint 구독을 BubbleOverlay 로 격리 → 추적 갱신이 mapLayer/필터바를 재평가하지 않는다(QE-1 c).
                if let pin = viewModel.selectedPin, viewModel.activeSheet == .none {
                    BubbleOverlay(pin: pin, viewModel: viewModel)
                }
            }
            .ignoresSafeArea()
            // 지도 뷰 크기 갱신(화면밖 판정 기준, AC-14). 회전/레이아웃 변화에 반응.
            .onChange(of: geo.size) { _, size in
                viewModel.updateMapSize(size)
            }
            .onAppear {
                viewModel.updateMapSize(geo.size)
            }
        }
    }

    // MARK: - 지도 레이어(Mapbox / 플레이스홀더 분기)

    @ViewBuilder
    private var mapLayer: some View {
        if MapConfig.isMapboxConfigured {
            MapboxMapView(
                markers: viewModel.markers,
                cameraCommand: $viewModel.cameraCommand,
                fitBoundsCommand: $viewModel.fitBoundsCommand,
                onEvent: viewModel.handle,
                // 선택핀 추적 입력(MUST-ADDRESS③ 게이팅): 선택 핀 좌표만 추적, 없으면 nil → onCameraChanged 방출 skip.
                selectedPin: viewModel.selectedPin.map { ($0.latitude, $0.longitude) },
                styleURL: MapConfig.styleURL,
                accessToken: MapConfig.accessToken
            )
        } else {
            PlaceholderMapView()
        }
    }
}

// MARK: - 말풍선 오버레이(screenPoint 구독 격리, QE-1 c)

/// 선택핀 말풍선을 마커 화면점에 앵커하는 격리 자식 뷰.
/// selectedPinScreenPoint 변경 시 이 뷰만 무효화 → mapLayer/토스트/필터바 재평가를 차단한다(QE-1 c).
/// 화면점이 nil(미투영/화면밖)이면 PinBubbleView 자체를 렌더하지 않는다(D-3 clamp 없음 — 숨김 후 복귀 시 재표시).
/// → 말풍선이 없을 때는 전체화면 배경탭도 함께 사라져 지도 탭을 가로채지 않는다.
/// 마커점(anchor)을 PinBubbleView 에 넘기고, `.position` 보정(본체가 마커 위 + 꼬리 끝이 마커 방향)은 PinBubbleView 내부가 담당(AC-2).
private struct BubbleOverlay: View {
    let pin: PinSummary
    @ObservedObject var viewModel: MapViewModel

    var body: some View {
        if let point = viewModel.selectedPinScreenPoint {
            PinBubbleView(pin: pin, anchor: point, mapViewModel: viewModel)
                // 핀 전환 시 말풍선 상태(detailVM/편집버퍼) 재생성(AC-6) — id 로 뷰 정체성 고정.
                .id(pin.id)
        }
    }
}
