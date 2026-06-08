import SwiftUI

// 봇 채팅 화면(설계 §5·§7, FR-2/3/5/6/7/13/16, BR-3, AC-2/3/4/19/20).
//  - ChatScrollContainer(공용): messages 오름차순, 빈 상태 "릴스 링크를 입력해보세요"(AC-19),
//    상단 loadMore(FR-2), 연결 배너 + 수동 재시도(QE-2/BR-8), PLACE_CARDS 저장 위임(onSavePlaceCards → savePlaceCards).
//  - 하단 입력바: TextField(draft 바인딩) + 전송 버튼 + 2000자 카운터(BR-3/AC-4). safeArea 하단 배치(키보드 회피 FR-16).
//  - saveInfoMessage: 카드 저장/409 흡수 안내 배너(AC-3, 에러 아님). 일정 시간 후/탭 시 해제.
//  - 라이프사이클: .task → appear(로드+구독), onDisappear → disappear(구독 해제).
//
// 사용자는 릴스 URL 텍스트만 전송한다(FR-6/BR-6/FR-27/AC-20). 미디어는 단말에 저장하지 않는다.
struct BotChatView: View {
    @ObservedObject var viewModel: BotChatViewModel

    /// 입력바 포커스(전송 후 유지/해제 제어).
    @FocusState private var inputFocused: Bool

    /// 포그라운드 복귀 감지(FR-4 — 백그라운드 수신/폴링 상한 초과 결과 보완 재조회).
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(spacing: 0) {
            ChatScrollContainer(
                messages: viewModel.messages,
                emptyText: "관심 있는 릴스 링크를 붙여넣어 보세요 ✨",
                onLoadMore: { Task { await viewModel.loadMore() } },
                onSavePlaceCards: { cards, wishIDs, memo, sourceInstagramUrl in
                    Task {
                        await viewModel.savePlaceCards(
                            cards: cards,
                            wishIDs: wishIDs,
                            memo: memo,
                            sourceInstagramUrl: sourceInstagramUrl
                        )
                    }
                }
            )

            saveResultCard

            saveInfoBanner

            inputBar
        }
        .background(WGColor.bg)
        .animation(.easeOut(duration: 0.2), value: viewModel.saveResult)
        .navigationTitle("어디가지 봇")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await viewModel.appear()
        }
        .onDisappear {
            Task { await viewModel.disappear() }
        }
        // 포그라운드 복귀 시 결과 재조회(FR-4 — 폴링 상한 초과/백그라운드 수신 보완).
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await viewModel.reconcileLatest() }
            }
        }
    }

    // MARK: - 저장 결과 카드(FR-I8)

    @ViewBuilder
    private var saveResultCard: some View {
        if let result = viewModel.saveResult {
            let savedCount = result.wishNames.count + result.reelNames.count
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top, spacing: 8) {
                    Text("✨ 위시 \(result.wishNames.count)곳 · 📍 발견 \(result.reelNames.count)곳 저장했어요")
                        .font(WGFont.sans(14))
                        .fontWeight(.semibold)
                        .foregroundStyle(WGColor.ink)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    Button {
                        viewModel.dismissSaveResult()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(WGColor.inkSoft)
                    }
                }

                // 신규 저장 성공 장소 목록(409 중복 제외 — N+M 과 건수 일치, AC-9).
                let savedNames = result.wishNames + result.reelNames
                if !savedNames.isEmpty {
                    VStack(alignment: .leading, spacing: 3) {
                        ForEach(savedNames, id: \.self) { name in
                            Text("· \(name)")
                                .font(WGFont.sans(12.5))
                                .foregroundStyle(WGColor.inkSoft)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }

                if let memo = result.memo, !memo.isEmpty {
                    Text("메모: \(memo)")
                        .font(WGFont.sans(12))
                        .foregroundStyle(WGColor.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }

                // [지도에서 보기 →]: 출처 URL 있고 저장 성공 핀 1개 이상일 때만(BR-7).
                if let url = result.sourceInstagramUrl, savedCount > 0 {
                    Button {
                        viewModel.showOnMap(instagramUrl: url)
                    } label: {
                        Text("지도에서 보기 →")
                            .font(WGFont.sans(13))
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(WGColor.cta)
                            .foregroundStyle(WGColor.panel)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(WGColor.cta.opacity(0.08))
            .overlay(alignment: .top) {
                Rectangle().fill(WGColor.hairline).frame(height: 1)
            }
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    // MARK: - 카드 저장 안내 배너(AC-3)

    @ViewBuilder
    private var saveInfoBanner: some View {
        if let message = viewModel.saveInfoMessage {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 14))
                    .foregroundStyle(WGColor.cta)
                Text(message)
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.ink)
                Spacer(minLength: 0)
                Button {
                    viewModel.saveInfoMessage = nil
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(WGColor.inkSoft)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .background(WGColor.cta.opacity(0.08))
            .overlay(alignment: .top) {
                Rectangle().fill(WGColor.hairline).frame(height: 1)
            }
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    // MARK: - 입력바(FR-3/BR-3/AC-4)

    private var inputBar: some View {
        // trimmed 단일 기준으로 통일(AC-4) — ViewModel.send() 의 trimmed 가드와 정합.
        let trimmed = viewModel.draft.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedCount = trimmed.count
        let isOverLimit = trimmedCount > BotChatViewModel.messageMaxLength
        let canSend = trimmedCount > 0 && !isOverLimit

        return VStack(spacing: 4) {
            HStack(alignment: .bottom, spacing: 10) {
                TextField("릴스 링크를 붙여넣어 보세요", text: $viewModel.draft, axis: .vertical)
                    .font(WGFont.sans(15))
                    .foregroundStyle(WGColor.ink)
                    .lineLimit(1...4)
                    .focused($inputFocused)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(WGColor.panel)
                    .clipShape(RoundedRectangle(cornerRadius: 18))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18)
                            .stroke(isOverLimit ? WGColor.pinNew : WGColor.hairline, lineWidth: 1)
                    )

                Button {
                    Task { await viewModel.send() }
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(canSend ? WGColor.cta : WGColor.inkFaint)
                }
                .disabled(!canSend)
            }

            // 2000자 카운터(BR-3/AC-4). trimmed 기준으로 통일. 초과 임박 시 표시·초과 시 강조.
            if trimmedCount > BotChatViewModel.messageMaxLength - 200 {
                HStack {
                    Spacer()
                    Text("\(trimmedCount)/\(BotChatViewModel.messageMaxLength)")
                        .font(WGFont.mono(11))
                        .foregroundStyle(isOverLimit ? WGColor.pinNew : WGColor.inkSoft)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background(WGColor.bg)
        .overlay(alignment: .top) {
            Rectangle().fill(WGColor.hairline).frame(height: 1)
        }
    }
}
