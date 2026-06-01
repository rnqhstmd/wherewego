import SwiftUI
import PhotosUI

// 사진 라이브러리 단일 선택 피커(설계 §6, FR-16). PHPickerViewController 래핑.
// filter=.images, 단일 선택. 선택 결과 UIImage 를 콜백으로 전달(취소 시 콜백 없이 닫힘).
struct PhotoPickerView: UIViewControllerRepresentable {
    /// 선택된 이미지 콜백(메인 액터). 취소/미선택 시 호출되지 않는다.
    let onPicked: (UIImage) -> Void
    /// 피커 닫기 콜백(선택/취소 공통, 메인 액터).
    let onDismiss: () -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = 1
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onPicked: onPicked, onDismiss: onDismiss)
    }

    @MainActor
    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let onPicked: (UIImage) -> Void
        private let onDismiss: () -> Void

        init(onPicked: @escaping (UIImage) -> Void, onDismiss: @escaping () -> Void) {
            self.onPicked = onPicked
            self.onDismiss = onDismiss
        }

        // PHPickerViewControllerDelegate 는 메인 스레드에서 호출된다(UIKit 보장).
        nonisolated func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let provider = results.first?.itemProvider,
                  provider.canLoadObject(ofClass: UIImage.self) else {
                // 취소(빈 결과) 또는 이미지 미지원 — 닫기만 한다.
                Task { @MainActor in self.onDismiss() }
                return
            }
            // loadObject 완료는 임의 스레드 — 결과를 메인 액터로 옮겨 콜백 호출.
            provider.loadObject(ofClass: UIImage.self) { object, _ in
                let image = object as? UIImage
                Task { @MainActor in
                    if let image {
                        self.onPicked(image)
                    }
                    self.onDismiss()
                }
            }
        }
    }
}
