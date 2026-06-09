import SwiftUI

// 공유 익스텐션 UI(설계 §5). 그룹 체크박스 멀티선택 + 보내기 + 상태별 화면.
// 앱 디자인 토큰 미import(자족) — 최소 팔레트(브랜드 cta 색만 복제).
struct ShareRootView: View {
    @ObservedObject var viewModel: ShareViewModel
    let onClose: () -> Void

    private let cta = Color(red: 0xC4 / 255, green: 0x62 / 255, blue: 0x2D / 255)  // 앱 WGColor.cta(#C4622D)

    var body: some View {
        NavigationView {
            content
                .navigationTitle("어디에 보낼까요")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("취소") { onClose() }
                    }
                }
        }
        .navigationViewStyle(.stack)
        .task { await viewModel.load() }
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.state {
        case .loading:
            loadingView("그룹을 불러오는 중…")
        case .sending:
            loadingView("보내는 중…")
        case .loaded(let groups):
            groupList(groups)
        case .empty:
            message("아직 속한 그룹이 없어요\n앱에서 그룹을 먼저 만들어 주세요")
        case .loginRequired:
            message("앱에서 로그인 후 다시 시도해주세요")
        case .result(let success, let failed):
            resultView(success: success, failed: failed)
        case .error(let msg):
            message(msg)
        }
    }

    private func loadingView(_ text: String) -> some View {
        VStack(spacing: 12) {
            ProgressView().tint(cta)
            Text(text).font(.subheadline).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func groupList(_ groups: [ShareGroup]) -> some View {
        VStack(spacing: 0) {
            List {
                Section {
                    ForEach(groups) { group in
                        Button {
                            viewModel.toggle(group.groupId)
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: viewModel.selected.contains(group.groupId) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(viewModel.selected.contains(group.groupId) ? cta : Color.secondary)
                                Text(group.groupName)
                                    .foregroundStyle(.primary)
                                Spacer(minLength: 0)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    Text("저장할 그룹을 골라주세요")
                }
            }

            Button {
                Task { await viewModel.send() }
            } label: {
                Text(viewModel.selected.isEmpty ? "보낼 그룹을 선택하세요" : "\(viewModel.selected.count)개 그룹에 보내기")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(viewModel.canSend ? cta : Color.gray.opacity(0.4))
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            .disabled(!viewModel.canSend)
            .padding(16)
        }
    }

    private func resultView(success: Int, failed: [String]) -> some View {
        VStack(spacing: 14) {
            Image(systemName: failed.isEmpty ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                .font(.system(size: 44))
                .foregroundStyle(failed.isEmpty ? cta : Color.orange)
            Text("\(success)개 그룹에 보냈어요")
                .font(.headline)
            if !failed.isEmpty {
                Text("전송 실패: \(failed.joined(separator: ", "))")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            Button("닫기") { onClose() }
                .padding(.top, 8)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func message(_ text: String) -> some View {
        VStack(spacing: 16) {
            Text(text)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button("닫기") { onClose() }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
