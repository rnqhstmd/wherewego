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
    /// 묶음 첫 메시지 여부(카톡식 그루핑) — 닉네임/아바타는 묶음 첫 메시지에만.
    var showsSender: Bool = true
    /// 묶음 마지막 메시지 여부 — 같은 분 연속 메시지는 마지막에만 시간 표시.
    var showsTime: Bool = true
    /// 릴스 썸네일 탭 → 인스타그램 원본 열기.
    @Environment(\.openURL) private var openURL
    /// 「장소 등록하기」(내 미등록 REEL_LINK) → 추출 팝업 트리거.
    var onRegister: ((_ messageId: Int, _ url: String) -> Void)?
    /// 「구경하실래요?」(등록됨 REEL_LINK) → 지도 딥링크.
    var onOpenReel: ((_ url: String) -> Void)?
    /// PIN_REPLY 핀 카드 탭(삭제 핀 아님) → 지도 딥링크(그 핀 포커스).
    var onOpenPin: ((_ pinId: Int) -> Void)?

    /// PIN_REPLY 핀 사진 썸네일 제자리 펼침 상태(메모 사진 펼침과 동일 문법, PinDetailContent 선례).
    @State private var isPinPhotoExpanded = false
    @Namespace private var pinPhotoNS
    /// PIN_VISIT/PIN_MEMORY 방문 카드 핀 사진 제자리 펼침 상태(pinReplyBubble 변형). pinPhotoNS 와 별도 네임스페이스로
    ///  matchedGeometry id("visitCardPhoto") 충돌을 피한다(동일 행 안에서 두 카드가 동시에 안 그려지지만 방어).
    @State private var isVisitPhotoExpanded = false
    @Namespace private var visitPhotoNS

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
        case .PIN_REPLY:
            pinReplyBubble
        case .PIN_VISIT, .PIN_MEMORY:
            visitCardBubble
        case .SYSTEM:
            systemCaption
        case .MEMO_PROMPT, .PLACE_CARDS, .PROCESSING:
            EmptyView()   // 그룹 방 미사용 봇 kind — 방어적 생략.
        }
    }

    // MARK: - TEXT(FR-GC2-2)

    private var textBubble: some View {
        // 인스타 DM식: 타인 = 아바타 + 닉네임(위) + 버블 + 오른쪽 하단 시간 / 내 메시지 = 왼쪽 하단 시간 + 버블.
        // 카톡식 그루핑: 닉네임은 묶음 첫 메시지(showsSender), 아바타·시간은 묶음 마지막(showsTime)에만.
        HStack(alignment: .bottom, spacing: 6) {
            if isOutgoing {
                Spacer(minLength: 48)
                if showsTime { timeLabel }
            } else {
                if showsTime { senderAvatar } else { Color.clear.frame(width: 28, height: 1) }
            }
            VStack(alignment: isOutgoing ? .trailing : .leading, spacing: 2) {
                if !isOutgoing, showsSender {
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
                    .clipShape(textBubbleShape)
                    .overlay(textBubbleShape.stroke(isOutgoing ? Color.clear : WGColor.hairline, lineWidth: 1))
            }
            if !isOutgoing {
                if showsTime { timeLabel }
                Spacer(minLength: 48)
            }
        }
    }

    /// TEXT 버블 모양: 발신=라운드 20 균일, 수신=좌하단 꼬리(6r) 나머지 20r.
    private var textBubbleShape: AnyShape {
        if isOutgoing {
            return AnyShape(RoundedRectangle(cornerRadius: 20))
        }
        return AnyShape(UnevenRoundedRectangle(
            topLeadingRadius: 20, bottomLeadingRadius: 6,
            bottomTrailingRadius: 20, topTrailingRadius: 20))
    }

    // MARK: - REEL_LINK(FR-GC2-3)

    private var reelBubble: some View {
        // 인스타 게시물 공유 카드: 썸네일 풀블리드(상단 모서리 맞물림) + 라벨/버튼 영역만 패딩 12.
        let cardShape = RoundedRectangle(cornerRadius: 20)
        return HStack(alignment: .bottom, spacing: 6) {
            if isOutgoing {
                Spacer(minLength: 32)
                if showsTime { timeLabel }
            } else {
                if showsTime { senderAvatar } else { Color.clear.frame(width: 28, height: 1) }
            }
            VStack(alignment: isOutgoing ? .trailing : .leading, spacing: 2) {
                if !isOutgoing, showsSender {
                    Text(senderName)
                        .font(WGFont.sans(11))
                        .foregroundStyle(WGColor.inkSoft)
                        .padding(.leading, 4)
                }
                VStack(alignment: .leading, spacing: 0) {
                    reelThumbnail
                    VStack(alignment: .leading, spacing: 10) {
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
                }
                .frame(maxWidth: 300, alignment: .leading)
                .background(WGColor.panel)
                .clipShape(cardShape)
                .overlay(cardShape.stroke(WGColor.hairline, lineWidth: 1))
            }
            if !isOutgoing {
                if showsTime { timeLabel }
                Spacer(minLength: 32)
            }
        }
    }

    // MARK: - PIN_REPLY(핀 답장)

    /// 핀 답장 버블 — 정렬/닉네임/아바타/시각은 TEXT 와 동일 규칙. 내용은 핀 카드 + 답장 텍스트 버블(VStack spacing 8, maxWidth 240).
    private var pinReplyBubble: some View {
        HStack(alignment: .bottom, spacing: 6) {
            if isOutgoing {
                Spacer(minLength: 48)
                if showsTime { timeLabel }
            } else {
                if showsTime { senderAvatar } else { Color.clear.frame(width: 28, height: 1) }
            }
            VStack(alignment: isOutgoing ? .trailing : .leading, spacing: 2) {
                if !isOutgoing, showsSender {
                    Text(senderName)
                        .font(WGFont.sans(11))
                        .foregroundStyle(WGColor.inkSoft)
                        .padding(.leading, 4)
                }
                VStack(alignment: isOutgoing ? .trailing : .leading, spacing: 8) {
                    pinCard
                    pinReplyText
                }
                .frame(maxWidth: 240, alignment: isOutgoing ? .trailing : .leading)
            }
            if !isOutgoing {
                if showsTime { timeLabel }
                Spacer(minLength: 48)
            }
        }
    }

    /// 답장 대상 핀 카드 — hairline 스트로크 + panel 배경 + radius 14. 미니 글리프 + 장소명/메모 + (사진 썸네일).
    /// 삭제 핀(deleted/placeName nil)은 mappin.slash + 안내 문구(사진/탭 비활성). 정상 핀 탭 → onOpenPin.
    @ViewBuilder
    private var pinCard: some View {
        let shape = RoundedRectangle(cornerRadius: 14)
        let snapshot = frame.pinSnapshot
        let isDeleted = (snapshot?.deleted ?? true) || (snapshot?.placeName == nil)
        VStack(alignment: .leading, spacing: 0) {
            // 사진 펼침은 카드 콘텐츠 "위"로(아래가 아니라) — 이름·메모 행은 아래 고정, 사진이 위로 펼쳐진다.
            //  썸네일과 상호 배타로 렌더해 동일 matchedGeometry id 가 동시에 두 인스턴스에 붙지 않게 한다.
            if isPinPhotoExpanded, !isDeleted,
               let thumb = snapshot?.photoThumbnailUrl, let full = snapshot?.photoUrl,
               let thumbURL = URL(string: thumb), let fullURL = URL(string: full) {
                ExpandedPinPhoto(thumbnailURL: thumbURL, photoURL: fullURL)
                    .matchedGeometryEffect(id: "pinReplyPhoto", in: pinPhotoNS)
                    .padding(.horizontal, 10)
                    .padding(.top, 10)
                    .onTapGesture {
                        withAnimation(.easeOut(duration: 0.3)) { isPinPhotoExpanded = false }
                    }
            }
            HStack(alignment: .center, spacing: 8) {
                pinCardGlyph(snapshot: snapshot, isDeleted: isDeleted)
                VStack(alignment: .leading, spacing: 2) {
                    Text(pinCardTitle(snapshot: snapshot, isDeleted: isDeleted))
                        .font(WGFont.sansSemiBold(13))
                        .foregroundStyle(isDeleted ? WGColor.inkFaint : WGColor.ink)
                        .lineLimit(1)
                    if !isDeleted, let memo = snapshot?.memo, !memo.isEmpty {
                        Text(memo)
                            .font(WGFont.sans(11.5))
                            .foregroundStyle(WGColor.inkSoft)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                // 펼침 중에는 우측 미니 썸네일을 숨긴다(matchedGeometry id 단일 인스턴스 유지 — 중복 경고/전환 깨짐 방지).
                if !isDeleted, !isPinPhotoExpanded,
                   let thumb = snapshot?.photoThumbnailUrl, let url = URL(string: thumb) {
                    pinCardThumbnail(thumbURL: url)
                }
            }
            .padding(10)
        }
        .background(WGColor.panel)
        .clipShape(shape)
        .overlay(shape.stroke(WGColor.hairline, lineWidth: 1))
        .contentShape(Rectangle())
        .onTapGesture {
            // 카드 탭 → 지도 핀 포커스(삭제 핀은 비활성). 썸네일 탭은 자체 onTapGesture 가 가로채 펼침으로 분기.
            guard !isDeleted, let pinId = snapshot?.pinId else { return }
            onOpenPin?(pinId)
        }
    }

    /// 핀 카드 좌측 글리프 — 정상: 태그 미니 글리프(REEL/WISH 12pt·MEMORY 16pt). 삭제: mappin.slash.
    @ViewBuilder
    private func pinCardGlyph(snapshot: PinChatSnapshot?, isDeleted: Bool) -> some View {
        if isDeleted {
            Image(systemName: "mappin.slash")
                .font(.system(size: 14))
                .foregroundStyle(WGColor.inkFaint)
                .frame(width: 16, height: 16)
        } else if let tag = snapshot?.tag.flatMap({ PinTag(rawValue: $0) }) {
            Image(uiImage: PinMarkerGlyphs.image(for: tag))
                .resizable()
                .scaledToFit()
                .frame(
                    width: tag == .MEMORY ? 16 : 12,
                    height: tag == .MEMORY ? 16 : 12
                )
        } else {
            // 미지 태그(서버 신규값) — 회색 점 폴백(글리프 미해석 안전).
            Circle()
                .fill(WGColor.inkFaint)
                .frame(width: 12, height: 12)
        }
    }

    /// 핀 카드 제목 — 정상: 장소명. 삭제: "삭제된 장소"(이름 있으면 ": 이름" 병기, deleted=true+placeName 유지 케이스).
    private func pinCardTitle(snapshot: PinChatSnapshot?, isDeleted: Bool) -> String {
        guard isDeleted else { return snapshot?.placeName ?? "" }
        if let name = snapshot?.placeName, !name.isEmpty {
            return "삭제된 장소: \(name)"
        }
        return "삭제된 장소"
    }

    /// 핀 카드 우측 썸네일(36pt, radius 9) — 탭 시 카드 아래 제자리 1:1 펼침(카드 탭과 별도 onTapGesture 로 우선).
    private func pinCardThumbnail(thumbURL: URL) -> some View {
        AsyncImage(url: thumbURL) { phase in
            if case let .success(image) = phase {
                image.resizable().scaledToFill()
            } else {
                WGColor.mapBlock
            }
        }
        .frame(width: 36, height: 36)
        .clipShape(RoundedRectangle(cornerRadius: 9))
        .matchedGeometryEffect(id: "pinReplyPhoto", in: pinPhotoNS)
        .contentShape(Rectangle())
        .onTapGesture {
            withAnimation(.easeOut(duration: 0.3)) { isPinPhotoExpanded = true }
        }
    }

    /// 답장 텍스트 버블 — 기존 TEXT 버블 스타일 재사용(수신=panel+꼬리/발신=cta20). 텍스트는 payload(frame.text).
    private var pinReplyText: some View {
        Text(frame.text ?? "")
            .font(WGFont.sans(15))
            .foregroundStyle(isOutgoing ? WGColor.panel : WGColor.ink)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(isOutgoing ? WGColor.cta : WGColor.panel)
            .clipShape(textBubbleShape)
            .overlay(textBubbleShape.stroke(isOutgoing ? Color.clear : WGColor.hairline, lineWidth: 1))
    }

    // MARK: - PIN_VISIT / PIN_MEMORY(정책 v2 방문 카드)

    /// 방문 카드 버블(pinReplyBubble 변형) — 정렬/닉네임/아바타/시각은 TEXT 와 동일 규칙.
    /// 내용: 안내 문구("다녀갔어요 📍"/"함께 다녀왔어요 🎉") + 핀 카드(visitPinCard) + (PIN_MEMORY) 동행 아바타 스택.
    private var visitCardBubble: some View {
        HStack(alignment: .bottom, spacing: 6) {
            if isOutgoing {
                Spacer(minLength: 48)
                if showsTime { timeLabel }
            } else {
                if showsTime { senderAvatar } else { Color.clear.frame(width: 28, height: 1) }
            }
            VStack(alignment: isOutgoing ? .trailing : .leading, spacing: 2) {
                if !isOutgoing, showsSender {
                    Text(senderName)
                        .font(WGFont.sans(11))
                        .foregroundStyle(WGColor.inkSoft)
                        .padding(.leading, 4)
                }
                VStack(alignment: isOutgoing ? .trailing : .leading, spacing: 6) {
                    Text(visitCaption)
                        .font(WGFont.sans(13))
                        .foregroundStyle(WGColor.inkSoft)
                    visitPinCard
                    // PIN_MEMORY 만 — 동행 참여자 아바타 스택(18pt·-5, Q4: 아바타만·텍스트 명단 없음).
                    if frame.kind == .PIN_MEMORY, let participants = frame.visitParticipants, !participants.isEmpty {
                        visitParticipantsStack(participants)
                    }
                }
                .frame(maxWidth: 240, alignment: isOutgoing ? .trailing : .leading)
            }
            if !isOutgoing {
                if showsTime { timeLabel }
                Spacer(minLength: 48)
            }
        }
    }

    /// 방문 카드 안내 문구 — kind 로 결정(payload/text 무관). PIN_VISIT="다녀갔어요 📍"·PIN_MEMORY="함께 다녀왔어요 🎉".
    private var visitCaption: String {
        frame.kind == .PIN_MEMORY ? "함께 다녀왔어요 🎉" : "다녀갔어요 📍"
    }

    /// 방문 대상 핀 카드 — pinCard(PIN_REPLY) 변형. hairline 스트로크 + panel + radius 14. 글리프 + 장소명/메모 + 36pt 썸네일.
    /// 카드 탭 → onOpenPin(.pinFocus). 썸네일 탭 → 카드 아래 제자리 1:1 펼침(visitPhotoNS — pinPhotoNS 와 별도 id).
    @ViewBuilder
    private var visitPinCard: some View {
        let shape = RoundedRectangle(cornerRadius: 14)
        let snapshot = frame.pinSnapshot
        let isDeleted = (snapshot?.deleted ?? true) || (snapshot?.placeName == nil)
        VStack(alignment: .leading, spacing: 0) {
            // 사진 펼침은 카드 콘텐츠 "위"로 — 이름·메모 행은 아래 고정, 사진이 위로 펼쳐진다. 썸네일과 상호 배타 렌더.
            if isVisitPhotoExpanded, !isDeleted,
               let thumb = snapshot?.photoThumbnailUrl, let full = snapshot?.photoUrl,
               let thumbURL = URL(string: thumb), let fullURL = URL(string: full) {
                ExpandedPinPhoto(thumbnailURL: thumbURL, photoURL: fullURL)
                    .matchedGeometryEffect(id: "visitCardPhoto", in: visitPhotoNS)
                    .padding(.horizontal, 10)
                    .padding(.top, 10)
                    .onTapGesture {
                        withAnimation(.easeOut(duration: 0.3)) { isVisitPhotoExpanded = false }
                    }
            }
            HStack(alignment: .center, spacing: 8) {
                pinCardGlyph(snapshot: snapshot, isDeleted: isDeleted)
                VStack(alignment: .leading, spacing: 2) {
                    Text(pinCardTitle(snapshot: snapshot, isDeleted: isDeleted))
                        .font(WGFont.sansSemiBold(13))
                        .foregroundStyle(isDeleted ? WGColor.inkFaint : WGColor.ink)
                        .lineLimit(1)
                    if !isDeleted, let memo = snapshot?.memo, !memo.isEmpty {
                        Text(memo)
                            .font(WGFont.sans(11.5))
                            .foregroundStyle(WGColor.inkSoft)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                // 펼침 중에는 우측 미니 썸네일을 숨긴다(matchedGeometry id 단일 인스턴스 유지).
                if !isDeleted, !isVisitPhotoExpanded,
                   let thumb = snapshot?.photoThumbnailUrl, let url = URL(string: thumb) {
                    visitCardThumbnail(thumbURL: url)
                }
            }
            .padding(10)
        }
        .background(WGColor.panel)
        .clipShape(shape)
        .overlay(shape.stroke(WGColor.hairline, lineWidth: 1))
        .contentShape(Rectangle())
        .onTapGesture {
            // 카드 탭 → 지도 핀 포커스(삭제 핀은 비활성). 썸네일 탭은 자체 onTapGesture 가 가로채 펼침으로 분기.
            guard !isDeleted, let pinId = snapshot?.pinId else { return }
            onOpenPin?(pinId)
        }
    }

    /// 방문 카드 우측 썸네일(36pt, radius 9) — 탭 시 카드 아래 제자리 1:1 펼침(visitPhotoNS).
    private func visitCardThumbnail(thumbURL: URL) -> some View {
        AsyncImage(url: thumbURL) { phase in
            if case let .success(image) = phase {
                image.resizable().scaledToFill()
            } else {
                WGColor.mapBlock
            }
        }
        .frame(width: 36, height: 36)
        .clipShape(RoundedRectangle(cornerRadius: 9))
        .matchedGeometryEffect(id: "visitCardPhoto", in: visitPhotoNS)
        .contentShape(Rectangle())
        .onTapGesture {
            withAnimation(.easeOut(duration: 0.3)) { isVisitPhotoExpanded = true }
        }
    }

    /// PIN_MEMORY 동행 아바타 스택(18pt·-5 오버랩, PinDetailContent.visitorsRow 동치). 텍스트 명단 없음(Q4).
    private func visitParticipantsStack(_ participants: [ChatVisitParticipant]) -> some View {
        let shown = Array(participants.prefix(5))
        let overflow = participants.count - shown.count
        return HStack(spacing: -5) {
            ForEach(shown) { participant in
                AvatarView(
                    imageUrl: participant.profileImageUrl,
                    name: participant.nickname ?? "?",
                    size: 18
                )
                .overlay(Circle().stroke(WGColor.panel, lineWidth: 1.5))
            }
            if overflow > 0 {
                Text("+\(overflow)")
                    .font(WGFont.sansSemiBold(9))
                    .foregroundStyle(WGColor.inkSoft)
                    .frame(width: 18, height: 18)
                    .background(WGColor.bg)
                    .clipShape(Circle())
                    .overlay(Circle().stroke(WGColor.panel, lineWidth: 1.5))
            }
        }
        .padding(.leading, 2)
    }

    // MARK: - REEL_LINK 썸네일(FR-GC3-2)

    /// 릴스 커버 썸네일 — 카드 상단 풀블리드 정사각(카드 clipShape 가 모서리 처리). thumbnailUrl 없거나 만료(로드 실패) 시 기본 회색 타일 폴백.
    private var reelThumbnail: some View {
        Color.clear
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
            .clipped()
            .contentShape(Rectangle())
            // 썸네일 탭 → 해당 릴스 원본으로 이동(인스타 앱/브라우저).
            .onTapGesture {
                if let raw = frame.reelUrl, let url = URL(string: raw) {
                    openURL(url)
                }
            }
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

    // MARK: - 아바타(인스타 DM식)

    /// 타인 메시지 좌측 아바타 — 발신자 프사(GP-1 FR-6) 원형, 없으면 닉네임 이니셜 틴트 원 폴백(AvatarView 일반화).
    /// 발신자 NULL(탈퇴) 이면 senderName="(알 수 없음)" → "(" 이니셜. 인스타 문법: 묶음 마지막 버블 옆 하단 정렬(28pt).
    private var senderAvatar: some View {
        AvatarView(imageUrl: frame.senderProfileImageUrl, name: senderName, size: 28)
    }

    // MARK: - 시각 라벨(카톡식)

    /// 버블 옆 하단 시각. 낙관 프레임(createdAt "")·파싱 실패는 미표시 — reconcile 이 서버 값으로 교체하면 나타난다.
    @ViewBuilder
    private var timeLabel: some View {
        let time = Self.displayTime(frame.createdAt)
        if !time.isEmpty {
            Text(time)
                .font(WGFont.mono(10))
                .foregroundStyle(WGColor.inkFaint)
                .padding(.bottom, 2)
        }
    }

    /// ISO-8601 createdAt → "오후 3:42". DMListViewModel.formatTime(상대시각)과 달리 절대시각.
    /// 백엔드 소수초 자릿수가 가변이라(ISO_OFFSET_DATE_TIME) 두 포맷터로 폴백 파싱한다.
    static func displayTime(_ iso: String) -> String {
        guard !iso.isEmpty,
              let date = isoFraction.date(from: iso) ?? isoPlain.date(from: iso) else { return "" }
        return hourMinute.string(from: date)
    }

    private static let isoFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let isoPlain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()
    private static let hourMinute: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ko_KR")
        f.dateFormat = "a h:mm"
        return f
    }()
}
