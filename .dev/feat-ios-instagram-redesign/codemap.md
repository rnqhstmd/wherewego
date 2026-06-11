# 코드 맵: IG-1 인스타 리디자인 — 셸 + 목록

## 핵심 파일
- ios/WhereWeGo/App/MainTabView.swift → 4탭 TabView + FloatingTabBar overlay + 지도탭 2레벨 분기(mapTabContent). GroupEntrySheet(.create/.invite) 시트 소유
- ios/WhereWeGo/App/FloatingTabBar.swift → 둥근 플로팅 필 바(MainTab enum, Metrics SSOT, InstaSendShape DM 글리프, glass/solid 분기)
- ios/WhereWeGo/Features/Group/GroupListView.swift → 지도탭 레벨0 그룹 목록(현행: emo 큰제목 + 카드 + addGroupChip 칩 행)
- ios/WhereWeGo/Features/Chat/DMListView.swift → 채팅 목록(현행: ScreenHeader + 카드 행 DMRoomRow + 빨간 카운트 캡슐)
- ios/WhereWeGo/Core/DesignSystem/ScreenHeader.swift → 고운바탕 큰 제목 헤더(IG-1에서 InstaNavBar 로 대체·삭제 대상)

## 참조 파일
- ios/WhereWeGo/Features/Notification/NotificationInboxView.swift:27 → ScreenHeader("알림") 사용처(헤더만 교체, 피드화는 IG-2)
- ios/WhereWeGo/Core/Session/CurrentUser.swift → @Published id/nickname/profileImageUrl (탭바 내정보 프사 소스)
- ios/WhereWeGo/Features/Common/AvatarView.swift → 원형 프사+이니셜 폴백(GP-1). AvatarView(imageUrl:name:size:)
- ios/WhereWeGo/Features/Common/GroupAvatarView.swift → 대표 이미지/멤버 콜라주(GP-1). GroupAvatarView(imageUrl:members:size:)
- ios/WhereWeGo/Features/Chat/DMListViewModel.swift → LoadState/formatTime(상대시각). hasUnread 배지 소스
- ios/WhereWeGo/Core/DesignSystem/Theme.swift → WGColor/WGFont 토큰(sansBold/sansSemiBold 실페이스)
- context/ig-redesign-plan.md → IG Phase 계획 SSOT(승인 스펙)
- context/app-redesign-instagram.html → 목업 v4(수치 원본: navbar 48/타이틀 Bold21/행 패딩 8×16/그룹 아바타 54/채팅 아바타 56/miniavs 18/-5/점 8pt)

## 설정
- ios/project.yml → xcodegen(파일 추가/삭제 자동 반영, CI에서 재생성)
