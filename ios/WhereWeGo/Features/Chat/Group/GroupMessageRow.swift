import SwiftUI

// 그룹 채팅 메시지 1건 렌더(GC-2 FR-GC2-2/3). 발신자 구분(내/남 정렬·닉네임) + REEL_LINK 3상태 버블.
//  - TEXT      : 내(senderUserId==currentUserId) 우측 cta, 타인 좌측 panel + 닉네임 라벨.
//  - REEL_LINK : 릴스 썸네일(정사각+radius, 만료/부재 시 회색 폴백, GC-3 FR-GC3-2) + 링크 카드(도메인+「Instagram 릴스」) + 하단 3상태 버튼.
//      ① 내+미등록 = 「장소 등록하기」(활성)  ② 타인+미등록 = 「장소 등록전이에요」(비활성)
//      ③ 등록됨(전원) = 「장소가 등록되었어요. 구경하실래요?」(활성). 상태는 서버 registered 만 신뢰.
//  - SYSTEM    : 중앙 캡션. 봇 kind(PLACE_CARDS/PROCESSING/MEMO_PROMPT)는 그룹 방 미사용 — 렌더 생략.
// 순수 프레젠테이션 + 콜백. ViewModel 비참조.
struct GroupMessageRow: View {
    let frame: GroupChatFrame
    /// 현재 사용자 id(발신자 구분). nil 이면 전부 타인 취급.
    let currentUserId: Int?
    /// 「장소 등록하기」(내 미등록 REEL_LINK) → 추출 팝업 트리거.
    var onRegister: ((_ messageId: Int, _ url: String) -> Void)?
    /// 「구경하실래요?」(등록됨 REEL_LINK) → 지도 딥링크.
    var onOpenReel: ((_ url: String) -> Void)?

    private var isOutgoing: Bool {
        guard let me = currentUserId, let sender = frame.senderUserId else { return false }
        return me == sender
    }
    private var senderName: String { frame.senderNickname ?? "(알 수 없음)" }

    var body: some View {
        switch frame.kind {
        case .TEXT:
            textBubble
        case .REEL_LINK:
            reelBubble
        case .SYSTEM:
            systemCaption
        case .MEMO_PROMPT, .PLACE_CARDS, .PROCESSING:
            EmptyView()   // 그룹 방 미사용 봇 kind — 방어적 생략.
        }
    }

    // MARK: - TEXT(FR-GC2-2)

    private var textBubble: some View {
        HStack {
            if isOutgoing { Spacer(minLength: 48) }
            VStack(alignment: isOutgoing ? .trailing : .leading, spacing: 2) {
                if !isOutgoing {
                    Text(senderName)
                        .font(WGFont.sans(11))
                        .foregroundStyle(WGColor.inkSoft)
                        .padding(.leading, 4)
                }
                Text(frame.text ?? "")
                    .font(WGFont.sans(15))
                    .foregroundStyle(isOutgoing ? WGColor.panel : WGColor.ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(isOutgoing ? WGColor.cta : WGColor.panel)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .overlay(
                        RoundedRectangle(cornerRadius: 16)
                            .stroke(isOutgoing ? Color.clear : WGColor.hairline, lineWidth: 1)
                    )
            }
            if !isOutgoing { Spacer(minLength: 48) }
        }
    }

    // MARK: - REEL_LINK(FR-GC2-3)

    private var reelBubble: some View {
        HStack {
            if isOutgoing { Spacer(minLength: 32) }
            VStack(alignment: isOutgoing ? .trailing : .leading, spacing: 2) {
                if !isOutgoing {
                    Text(senderName)
                        .font(WGFont.sans(11))
                        .foregroundStyle(WGColor.inkSoft)
                        .padding(.leading, 4)
                }
                VStack(alignment: .leading, spacing: 10) {
                    reelThumbnail
                    HStack(spacing: 8) {
                        Image(systemName: "play.rectangle.fill")
                            .font(.system(size: 18))
                            .foregroundStyle(WGColor.cta)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Instagram 릴스")
                                .font(WGFont.sans(13))
                                .fontWeight(.semibold)
                                .foregroundStyle(WGColor.ink)
                            Text(reelHost)
                                .font(WGFont.mono(11))
                                .foregroundStyle(WGColor.inkSoft)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }
                    reelButton
                }
                .padding(12)
                .frame(maxWidth: 300, alignment: .leading)
                .background(WGColor.panel)
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(WGColor.hairline, lineWidth: 1))
            }
            if !isOutgoing { Spacer(minLength: 32) }
        }
    }

    // MARK: - REEL_LINK 썸네일(FR-GC3-2)

    /// 릴스 커버 썸네일 — 카드 폭 정사각 + radius(둥글둥글). thumbnailUrl 없거나 만료(로드 실패) 시 기본 회색 타일 폴백.
    private var reelThumbnail: some View {
        let shape = RoundedRectangle(cornerRadius: 12)
        return Color.clear
            .aspectRatio(1, contentMode: .fit)          // 정사각 사이저(카드 폭에 1:1)
            .overlay {
                if let raw = frame.thumbnailUrl, let url = URL(string: raw) {
                    AsyncImage(url: url) { phase in
                        switch phase {
                        case .success(let image):
                            image.resizable().scaledToFill()
                        case .failure:
                            thumbnailPlaceholder        // 만료(403)/차단 → 회색
                        default:
                            thumbnailPlaceholder        // 로딩 중에도 회색(깜빡임 최소)
                        }
                    }
                } else {
                    thumbnailPlaceholder                // 아직 스크래핑 전/flag off → 회색
                }
            }
            .clipShape(shape)
            .overlay(shape.stroke(WGColor.hairline, lineWidth: 1))
    }

    /// 썸네일 부재·실패 폴백: 기본 회색 타일 + photo 글리프.
    private var thumbnailPlaceholder: some View {
        ZStack {
            WGColor.hairline
            Image(systemName: "photo")
                .font(.system(size: 24))
                .foregroundStyle(WGColor.inkFaint)
        }
    }

    @ViewBuilder
    private var reelButton: some View {
        let url = frame.reelUrl ?? ""
        if frame.registered == true {
            Button { onOpenReel?(url) } label: {
                buttonLabel("장소가 등록되었어요. 구경하실래요?", active: true)
            }
            .buttonStyle(.plain)
        } else if isOutgoing {
            Button { onRegister?(frame.messageId, url) } label: {
                buttonLabel("장소 등록하기", active: true)
            }
            .buttonStyle(.plain)
        } else {
            buttonLabel("장소 등록전이에요", active: false)
        }
    }

    private func buttonLabel(_ text: String, active: Bool) -> some View {
        Text(text)
            .font(WGFont.sans(13))
            .fontWeight(.semibold)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 9)
            .background(active ? WGColor.cta : WGColor.bg)
            .foregroundStyle(active ? WGColor.panel : WGColor.inkFaint)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(active ? Color.clear : WGColor.hairline, lineWidth: 1)
            )
    }

    // MARK: - SYSTEM

    private var systemCaption: some View {
        Text(frame.text ?? "")
            .font(WGFont.sans(12))
            .foregroundStyle(WGColor.inkSoft)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, alignment: .center)
    }

    private var reelHost: String {
        guard let url = frame.reelUrl, let host = URLComponents(string: url)?.host else { return "instagram.com" }
        return host
    }
}
