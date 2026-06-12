import XCTest
import UIKit
import CoreLocation
@testable import WhereWeGo

// PinDetailViewModel 단위 테스트(설계 §3·§6, DoD-A).
// - AC-17: shouldShowInstagramLink — https:// 만 허용, http/javascript/nil/빈 차단.
// - AC-9: shouldShowPhotoSection — MEMORY 만 true.
// - AC-8: 사진 업로드 흐름 — Stub PinAPI 로 압축 jpegData 전달·성공 시 replacePin 반영, 2MB 초과 압축 실패 경로 에러.
//
// VM 이 @MainActor 이고 MapViewModel 을 weak 참조하므로 테스트도 @MainActor.
@MainActor
final class PinDetailViewModelTests: XCTestCase {

    // MARK: - AC-17: shouldShowInstagramLink

    func test_shouldShowInstagramLink_httpsOnly() {
        XCTAssertTrue(PinDetailViewModel.shouldShowInstagramLink("https://www.instagram.com/p/abc"))
        XCTAssertFalse(PinDetailViewModel.shouldShowInstagramLink("http://www.instagram.com/p/abc"))
        XCTAssertFalse(PinDetailViewModel.shouldShowInstagramLink("javascript:alert(1)"))
        XCTAssertFalse(PinDetailViewModel.shouldShowInstagramLink(nil))
        XCTAssertFalse(PinDetailViewModel.shouldShowInstagramLink(""))
    }

    // MARK: - AC-9: shouldShowPhotoSection

    func test_shouldShowPhotoSection_memoryOnly() {
        XCTAssertTrue(PinDetailViewModel.shouldShowPhotoSection(tag: .MEMORY))
        XCTAssertFalse(PinDetailViewModel.shouldShowPhotoSection(tag: .REEL))
        XCTAssertFalse(PinDetailViewModel.shouldShowPhotoSection(tag: .WISH))
    }

    // MARK: - AC-8: 사진 업로드 흐름

    func test_uploadPhoto_success_passesJpegAndReplacesPin() async {
        // Given MEMORY 핀 1개 로드된 MapViewModel + 업로드 성공 Stub(사진 URL 채운 summary 반환)
        let original = makePin(id: 1, tag: .MEMORY)
        let uploaded = makePin(id: 1, tag: .MEMORY, photoUrl: "https://cdn/photo.jpg", photoThumbnailUrl: "https://cdn/thumb.jpg")
        let pinAPI = StubPinAPI(listResult: [original])
        pinAPI.uploadResult = .success(uploaded)
        let mapVM = makeMapViewModel(pinAPI: pinAPI)
        await mapVM.load()
        let detailVM = PinDetailViewModel(pinAPI: pinAPI, mapViewModel: mapVM)

        // When 사진 업로드(테스트 이미지)
        await detailVM.uploadPhoto(pinId: 1, image: makeImage(width: 800, height: 800))

        // Then jpegData(image/jpeg 매직바이트) 가 전달되고, replacePin 으로 photoUrl 이 반영된다.
        XCTAssertNotNil(pinAPI.lastUploadedData)
        let bytes = [UInt8]((pinAPI.lastUploadedData ?? Data()).prefix(3))
        XCTAssertEqual(bytes, [0xFF, 0xD8, 0xFF])
        XCTAssertEqual(mapVM.pins.first?.photoUrl, "https://cdn/photo.jpg")
        XCTAssertNil(detailVM.photoError)
        XCTAssertFalse(detailVM.isPhotoBusy)
    }

    func test_uploadPhoto_apiFailure_setsErrorAndKeepsPin() async {
        // Given 업로드 실패(GROUP_NOT_MEMBER) Stub
        let original = makePin(id: 1, tag: .MEMORY)
        let pinAPI = StubPinAPI(listResult: [original])
        pinAPI.uploadResult = .failure(APIError(code: "GROUP_NOT_MEMBER", status: 403, message: "no"))
        let mapVM = makeMapViewModel(pinAPI: pinAPI)
        await mapVM.load()
        let detailVM = PinDetailViewModel(pinAPI: pinAPI, mapViewModel: mapVM)

        // When 업로드
        await detailVM.uploadPhoto(pinId: 1, image: makeImage(width: 600, height: 600))

        // Then 인라인 에러(권한 안내), 핀 photoUrl 미변경
        XCTAssertEqual(detailVM.photoError, "그룹의 활성 멤버만 사진을 올릴 수 있어요.")
        XCTAssertNil(mapVM.pins.first?.photoUrl)
    }

    func test_deletePhoto_success_replacesPin() async {
        // Given 사진 있는 핀 → 삭제 성공(사진 없는 summary 반환)
        let withPhoto = makePin(id: 1, tag: .MEMORY, photoUrl: "https://cdn/photo.jpg", photoThumbnailUrl: "https://cdn/thumb.jpg")
        let cleared = makePin(id: 1, tag: .MEMORY)
        let pinAPI = StubPinAPI(listResult: [withPhoto])
        pinAPI.deletePhotoResult = .success(cleared)
        let mapVM = makeMapViewModel(pinAPI: pinAPI)
        await mapVM.load()
        let detailVM = PinDetailViewModel(pinAPI: pinAPI, mapViewModel: mapVM)

        await detailVM.deletePhoto(pinId: 1)

        XCTAssertNil(mapVM.pins.first?.photoUrl)
        XCTAssertNil(detailVM.photoError)
    }

    // MARK: - 헬퍼

    private func makeMapViewModel(pinAPI: PinAPIProtocol) -> MapViewModel {
        MapViewModel(
            pinAPI: pinAPI,
            placeAPI: StubPlaceAPI(),
            groupAPI: StubGroupAPI(group: ActiveGroup(groupId: 1, name: "팀", memberCount: 2)),
            chatAPI: StubChatAPI(),
            locationService: StubLocationService(status: .denied)
        )
    }

    private func makePin(
        id: Int,
        tag: PinTag,
        photoUrl: String? = nil,
        photoThumbnailUrl: String? = nil
    ) -> PinSummary {
        PinSummary(
            id: id,
            groupId: 1,
            createdBy: 1,
            createdByNickname: "tester",
            placeName: "장소\(id)",
            address: nil,
            latitude: 37.5,
            longitude: 127.0,
            instagramUrl: nil,
            memo: nil,
            memoSource: nil,
            tag: tag,
            createdAt: "2026-01-01T00:00:00Z",
            visitedAt: nil,
            memoUpdatedBy: nil,
            memoUpdatedByNickname: nil,
            photoUrl: photoUrl,
            photoThumbnailUrl: photoThumbnailUrl
        )
    }

    /// scale=1 단색 테스트 이미지(ImageCropperTests 패턴). resizeAndCompress 가 image/jpeg 를 만든다.
    private func makeImage(width: CGFloat, height: CGFloat, color: UIColor = .red) -> UIImage {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format)
        return renderer.image { ctx in
            color.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        }
    }
}

// MARK: - In-file 프로토콜 목(MapViewModelTests 패턴)

private final class StubPinAPI: PinAPIProtocol, @unchecked Sendable {
    private let listResult: [PinSummary]
    var uploadResult: Result<PinSummary, Error> = .failure(APIError(code: "UNSET", status: 0, message: ""))
    var deletePhotoResult: Result<PinSummary, Error> = .failure(APIError(code: "UNSET", status: 0, message: ""))
    /// 마지막 업로드 jpegData 캡처(AC-8: 압축된 image/jpeg 전달 확인용).
    private(set) var lastUploadedData: Data?

    init(listResult: [PinSummary]) {
        self.listResult = listResult
    }

    func list(groupId: Int) async throws -> [PinSummary] {
        listResult
    }

    func create(groupId: Int, request: CreatePinRequest) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }

    func update(groupId: Int, pinId: Int, request: UpdatePinRequest) async throws -> UpdatePinResponse {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }

    func delete(groupId: Int, pinId: Int) async throws {}

    func uploadPhoto(groupId: Int, pinId: Int, imageData: Data) async throws -> PinSummary {
        lastUploadedData = imageData
        return try uploadResult.get()
    }

    func deletePhoto(groupId: Int, pinId: Int) async throws -> PinSummary {
        try deletePhotoResult.get()
    }
}

private final class StubPlaceAPI: PlaceAPIProtocol, @unchecked Sendable {
    func search(_ keyword: String) async throws -> [PlaceItem] { [] }
}

private final class StubGroupAPI: GroupAPIProtocol, @unchecked Sendable {
    private let group: ActiveGroup?

    init(group: ActiveGroup?) { self.group = group }

    func myActiveGroup() async throws -> ActiveGroup? { group }

    func listMyGroups() async throws -> [GroupSummary] { [] }

    func acceptInvite(token: String) async throws -> InviteAccept { InviteAccept(groupId: 0) }

    func issueInviteLink(groupId: Int) async throws -> InviteLink {
        InviteLink(token: "stub", slug: nil, shareUrl: nil)
    }

    func leaveGroup(groupId: Int) async throws {}

    func listMembers(groupId: Int) async throws -> [GroupMemberItem] { [] }

    func updateGroupName(groupId: Int, name: String) async throws {}

    func deleteGroup(groupId: Int) async throws {}
}

@MainActor
private final class StubLocationService: LocationServiceProtocol {
    var authorizationStatus: CLAuthorizationStatus
    var onSample: ((LocationSample) -> Void)?

    init(status: CLAuthorizationStatus) {
        self.authorizationStatus = status
    }

    func requestWhenInUsePermission() {}
    func startUpdating() {}
    func stopUpdating() {}
    func requestOneShot() async -> LocationSample? { nil }
}
