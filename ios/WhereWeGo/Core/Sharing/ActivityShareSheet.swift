import SwiftUI
import UIKit

// 시스템 공유시트 래퍼(설계 §8, Q2, FR-16). UIActivityViewController 를 SwiftUI 에 노출.
// PhotoPickerView 의 UIViewControllerRepresentable 선례 모방.
// iPad popover sourceView 보강은 iPad 정식지원 시점(현 iPhone 타깃 .sheet 안전, 범위 밖).
struct ActivityShareSheet: UIViewControllerRepresentable {
    /// 공유 대상 항목(텍스트 등). 빈 배열이면 공유시트가 항목 없이 열린다.
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
