import UIKit

// 핀 상세(정보창) ViewModel(설계 §3·§6, FR-8~12/16~19, AC-8/9/17).
// frontend/src/app/map/_components/MemoTagPanelContent.tsx + MapClient.tsx 정보창/사진 흐름 이식.
//
// 책임:
//  - 사진 추가/삭제 상태(선택 이미지/업로드 진행/에러) 관리.
//  - 사진 업로드: ImageCropper.resizeAndCompress(2MB 게이트) → pinAPI.uploadPhoto → mapViewModel.replacePin.
//  - 사진 삭제: pinAPI.deletePhoto → replacePin.
//  - 분기 헬퍼(순수, 테스트 대상): Instagram https 가드(AC-17), MEMORY 사진 영역 노출(AC-9).
// 비책임: 태그 변경/메모/장소명/삭제 낙관 PATCH 는 MapViewModel 의 공개 메서드에 위임(pins 단일 출처 유지).
@MainActor
final class PinDetailViewModel: ObservableObject {

    // MARK: - 분기 헬퍼(순수, AC-17/AC-9)

    /// Instagram 바로가기 노출 여부(BR-3/AC-17). https:// 로 시작하는 값만 허용(javascript:/http: 차단).
    static func shouldShowInstagramLink(_ url: String?) -> Bool {
        guard let url else { return false }
        return url.hasPrefix("https://")
    }

    /// 사진 영역 노출 여부(BR-5/AC-9). MEMORY 태그일 때만 노출.
    static func shouldShowPhotoSection(tag: PinTag) -> Bool {
        tag == .MEMORY
    }

    // MARK: - 게시 상태(사진)

    /// 사진 업로드/삭제 진행 중(QE-3 로딩 인디케이터). 진행 중에는 버튼 비활성.
    @Published private(set) var isPhotoBusy = false
    /// 사진 작업 인라인 에러 메시지. 표시 후 다음 시도 시 nil 로 초기화.
    @Published var photoError: String?

    // MARK: - 의존성

    private let pinAPI: PinAPIProtocol
    /// 핀 단일 출처(MapViewModel.pins) 갱신 위임. 사진 업로드/삭제 응답을 replacePin 으로 반영.
    /// weak: MapView 가 소유한 MapViewModel 보다 시트 VM 이 늦게 해제되는 경우의 dangling 방어.
    private weak var mapViewModel: MapViewModel?

    init(pinAPI: PinAPIProtocol, mapViewModel: MapViewModel) {
        self.pinAPI = pinAPI
        self.mapViewModel = mapViewModel
    }

    // MARK: - 사진 업로드(FR-16~18, AC-8)

    /// 크롭 완료 이미지를 압축(2MB 게이트) → 업로드 → MapViewModel.replacePin 으로 반영.
    /// 압축 실패(2MB 초과) 시 "파일이 너무 커요", 업로드 실패 시 코드별 메시지.
    func uploadPhoto(pinId: Int, image: UIImage) async {
        guard let mapViewModel, let groupId = mapViewModel.groupId else {
            photoError = "활성 그룹을 찾지 못했어요. 잠시 후 다시 시도해 주세요."
            return
        }
        guard let jpegData = ImageCropper.resizeAndCompress(image) else {
            photoError = "사진 파일이 너무 커요. 다른 사진을 선택해 주세요."
            return
        }
        isPhotoBusy = true
        photoError = nil
        defer { isPhotoBusy = false }
        do {
            let updated = try await pinAPI.uploadPhoto(groupId: groupId, pinId: pinId, imageData: jpegData)
            mapViewModel.replacePin(updated)
        } catch let error as APIError {
            photoError = Self.message(for: error)
        } catch {
            photoError = "사진을 올리지 못했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    // MARK: - 사진 삭제(FR-19)

    /// 사진 삭제 → MapViewModel.replacePin 으로 반영. 호출부(시트)가 confirm 후 호출.
    func deletePhoto(pinId: Int) async {
        guard let mapViewModel, let groupId = mapViewModel.groupId else {
            photoError = "활성 그룹을 찾지 못했어요. 잠시 후 다시 시도해 주세요."
            return
        }
        isPhotoBusy = true
        photoError = nil
        defer { isPhotoBusy = false }
        do {
            let updated = try await pinAPI.deletePhoto(groupId: groupId, pinId: pinId)
            mapViewModel.replacePin(updated)
        } catch let error as APIError {
            photoError = Self.message(for: error)
        } catch {
            photoError = "사진을 삭제하지 못했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    // MARK: - 에러 메시지 매핑(설계 §9)

    /// APIError 코드 → View 친화 한국어 메시지. 업로드 403 GROUP_NOT_MEMBER → 권한 안내.
    private static func message(for error: APIError) -> String {
        switch error.code {
        case "GROUP_NOT_MEMBER":
            return "그룹의 활성 멤버만 사진을 올릴 수 있어요."
        case "PIN_PHOTO_NOT_MEMORY":
            return "추억 핀에만 사진을 추가할 수 있어요."
        case "PIN_PHOTO_TOO_LARGE":
            return "사진 파일이 너무 커요. 다른 사진을 선택해 주세요."
        default:
            return error.message
        }
    }
}
