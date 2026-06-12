# 코드 맵: IG-2 인스타 리디자인 — 채팅방·프로필·알림 (iOS + backend 소규모)

## 핵심 파일
- ios/WhereWeGo/Features/Chat/Group/GroupChatView.swift · GroupChatViewModel.swift → 그룹 채팅방 화면/상태. 버블·입력바·헤더 인스타 DM화 + 진입 단순화(앵커 제거→항상 최신) 대상
- ios/WhereWeGo/Features/Chat/Group/GroupMessageRow.swift → 메시지 행(수신/발신 버블·REEL_LINK 3상태 카드) 스타일 교체 대상
- ios/WhereWeGo/Features/MyInfo/MyInfoView.swift · MyInfoViewModel.swift → 내정보 화면. 인스타 프로필화(아바타 84+카메라 배지+통계 2종+버튼 2+설정 플랫 리스트) 대상
- ios/WhereWeGo/Features/Notification/NotificationInboxView.swift · NotificationInboxViewModel.swift → 알림 탭. 피드화(행위자 프사+인라인 Bold+썸네일) + 핀 딥링크 대상
- ios/WhereWeGo/App/DeepLinkRouter.swift → 딥링크 라우터. `.reelFocus` 선례 — 핀 포커스 딥링크 신설 대상

## 참조 파일
- ios/WhereWeGo/Features/Map/MapViewModel.swift → focusReel(릴스 핀 필터+fitBounds). 핀 포커스 경로(그룹 전환→카메라→말풍선) 신설 지점
- ios/WhereWeGo/App/MainTabView.swift → 탭 전환·탭바(IG-1 4탭). 알림 탭→지도탭 전환 배선
- ios/WhereWeGo/Core/DesignSystem/InstaNavBar.swift → IG-1 경량 상단바(Pretendard Bold 21, 48pt) 재사용
- ios/WhereWeGo/Features/Chat/ChatScrollContainer.swift → 채팅 스크롤 컨테이너(앵커/scrollToBottom 로직)
- ios/WhereWeGo/Features/Common/AvatarView.swift · GroupAvatarView.swift → GP-1 아바타 컴포넌트(전 화면 프사 기반)
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/user/UserV1Controller.java → /users/me — 등록 핀 수 통계 추가 대상
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/notification/NotificationV1Controller.java → 알림 목록 — 행위자 프사 URL 추가 대상

## 설정
- ios/WhereWeGo/Core/DesignSystem/ → WGColor/WGFont 토큰(100% 유지, cta `#C4622D`)
- context/ig-redesign-plan.md → IG 리디자인 SSOT(확정 스펙·Phase 분할)
