import UIKit
import SwiftUI
import UniformTypeIdentifiers

// 공유 익스텐션 진입점(설계 §4). NSExtensionPrincipalClass.
// extensionContext 에서 공유 URL 추출 → SwiftUI(ShareRootView) 호스팅. 완료/취소 시 completeRequest.
final class ShareViewController: UIViewController {

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        Task { await start() }
    }

    private func start() async {
        guard let sharedURL = await extractURL() else {
            // 공유 항목에서 URL을 못 찾음 → 조용히 종료.
            close()
            return
        }
        let baseURL = (Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String)
            .flatMap { URL(string: $0) } ?? URL(string: "http://localhost:8080")!
        let api = ShareAPIClient(baseURL: baseURL, tokens: ShareKeychain())
        let viewModel = ShareViewModel(api: api, sharedURL: sharedURL)
        showRoot(viewModel)
    }

    private func showRoot(_ viewModel: ShareViewModel) {
        let root = ShareRootView(viewModel: viewModel, onClose: { [weak self] in self?.close() })
        let host = UIHostingController(rootView: root)
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
    }

    private func close() {
        extensionContext?.completeRequest(returningItems: nil)
    }

    /// 공유 항목에서 첫 URL 추출. public.url 우선, 실패 시 public.text 에서 http(s) URL 파싱.
    private func extractURL() async -> String? {
        guard let items = extensionContext?.inputItems as? [NSExtensionItem] else { return nil }
        for item in items {
            for provider in item.attachments ?? [] {
                if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                    if let loaded = try? await provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil),
                       let url = loaded as? URL {
                        return url.absoluteString
                    }
                }
                if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                    if let loaded = try? await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil),
                       let text = loaded as? String,
                       let url = Self.firstURL(in: text) {
                        return url
                    }
                }
            }
        }
        return nil
    }

    /// 텍스트에서 첫 링크를 추출(인스타가 URL 대신 텍스트로 공유하는 경우 폴백).
    static func firstURL(in text: String) -> String? {
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else {
            return nil
        }
        let range = NSRange(text.startIndex..., in: text)
        return detector.firstMatch(in: text, range: range)?.url?.absoluteString
    }
}
