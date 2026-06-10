## 코드 맵: GC-2 iOS 그룹 채팅 UI + 장소 등록 플로우

> 핵심 갭: GC-1 백엔드는 GROUP 방으로 전환 완료(REEL_LINK·registered·GroupChatMessageFrame·GROUP_MESSAGE 푸시)했으나,
> iOS는 아직 As-Is 봇 채팅(`/chat/bot·/chat/couple`, ChatFrame senderType USER/BOT/SYSTEM, REEL_LINK 없음)을 소비 중.
> GC-2 = iOS를 GC-1 계약으로 재배선 + REEL_LINK 3상태 버블 + 추출 팝업 + 딥링크 groupId 확장 + ShareExtension 전환 + 수신 배선.

### 핵심 파일 (iOS — 변경 대상)
- ios/WhereWeGo/Features/Chat/ChatAPI.swift:24 → ChatAPIProtocol/ChatAPI. 현재 `/chat/bot`·`/chat/couple` 소비 → GC-2: `/chat/groups/{groupId}/messages`(GET/POST) 재배선 + 온디맨드 extract `POST .../messages/{messageId}/extract` 추가
- ios/WhereWeGo/Features/Chat/ChatMessageModels.swift:59 → ChatFrame/MessageKind/PlaceCardsPayload/BotRoomSummary. GC-2: `REEL_LINK` kind·`senderUserId`/`senderNickname`·`registered` 플래그·GroupRoomSummary 추가(GroupChatMessageFrame 정합)
- ios/WhereWeGo/Features/Chat/DMListView.swift + DMListViewModel.swift:11 → DM탭 그룹별 방 목록(FR-GC2-1). 현재 botRooms → 그룹 채팅 목록 API. 멤버별 unread(인스타식 읽음) 유지
- ios/WhereWeGo/Features/Chat/ChatScrollContainer.swift + ChatMessageRow.swift → 채팅방 스크롤·메시지 렌더(FR-GC2-2/3). 발신자 구분(내/남+닉네임) + REEL_LINK 버블+3상태 버튼 추가
- ios/WhereWeGo/Features/Chat/ReelSaveWizard.swift → 위시 체크→메모 위저드(FR-GC2-4). 앞에 "추출 중" 스텝 + extract API 연동, savePlaceCards 재사용(409 흡수)

### 참조 파일
- ios/ShareExtension/Logic/ShareViewModel.swift + ShareAPIClient.swift → 인스타 공유 진입(FR-GC2-7). 그룹 멀티선택 UI 유지, 전송 대상 봇방→그룹챗 REEL_LINK 교체
- ios/WhereWeGo/App/DeepLinkRouter.swift → 딥링크 라우팅(FR-GC2-5). `.reelFocus(groupId:instagramUrl:)` 확장
- ios/WhereWeGo/Features/Map/MapViewModel.swift → focusReel(instagram_url 필터+fitBounds)(FR-GC2-5). groupId 그룹 전환 추가
- ios/WhereWeGo/App/MainTabView.swift → 탭 구성·딥링크 진입점. DM탭↔지도탭 전환
- ios/WhereWeGo/Features/Group/GroupContext.swift → 활성 그룹 컨텍스트(FR-GC2-5 그룹 전환 대상)
- ios/WhereWeGo/App/AppNotificationDelegate.swift → APNs willPresent(FR-GC2-6 포그라운드 현재 방이면 재조회 트리거 신규 배선)
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/chat/ChatV1Dto.java + ChatV1ApiSpec.java → 백엔드 GC-1 계약(엔드포인트·DTO 형상) — 의존 계약 SSOT

### 설정/명세
- ios/WhereWeGo/App/AppDelegate.swift + AppDependencies.swift → 푸시 등록·DI 와이어링(ChatAPI 주입부)
- backend/.../domain/chat/GroupChatMessageFrame.java + GroupRoomSummary.java + MessageKind.java → registered·REEL_LINK·프레임 필드 원본(iOS 모델 정합 기준)
- context/chat/status.md + context/chat/architecture.md → GC-2 FR-GC2-1~8 / §GC-2 의존 계약 SSOT

### 제약 메모
- iOS 빌드/테스트는 **Windows 로컬 불가** — GitHub Actions CI(빌드+단위테스트)가 검증. 최종 시각/실기기 검증은 Mac(DoD-B).
- push 전 `gh auth switch` → rnqhstmd 계정 필수(메모리 feedback_git_push_account).
