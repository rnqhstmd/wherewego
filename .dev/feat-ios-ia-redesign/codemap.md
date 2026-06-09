## 코드 맵: iOS IA 재설계 — DM (그룹별 봇방 목록, #105 소비)

> 같은 브랜치 누적(C 완료 후 develop 동기 deb546f). 이 맵은 DM 변경 대상 중심. 백엔드 #105는 develop 머지 완료.

### 핵심 파일 (DM 변경/신규 대상)
- ios/WhereWeGo/Features/Chat/ChatAPI.swift:22-31,42-57 → ChatAPIProtocol + ChatAPI. **DM: botRooms() 추가 + botMessages/sendBotMessage 를 groupId 인자화**(`/chat/bot/{groupId}/messages`). 비그룹 `/chat/bot/messages`(deprecated) 소비 제거. couple 메서드는 유지.
- ios/WhereWeGo/Features/Chat/ChatMessageModels.swift:114-124 → MessagesResponse/SendMessageResponse 등. **DM: BotRoomSummary 모델 신규**(roomId:Int?·groupId:Int·groupName·lastPreview:String?·lastSenderType:SenderType?·unread:Bool·lastAt:String?).
- ios/WhereWeGo/Features/Chat/Bot/BotChatViewModel.swift:65-81,102-158,199-260,276-291 → 봇 채팅 VM. **DM: groupId 주입**(init) → botMessages/sendBotMessage/reconcileLatest 에 사용. savePlaceCards 가 myActiveGroup() 대신 **주입 groupId** 사용(릴스 저장=그 방 그룹). 미사용된 groupAPI 의존 제거 검토.
- ios/WhereWeGo/Features/Chat/Bot/BotChatView.swift:11-12,46 → 봇 채팅 화면(@ObservedObject VM). navigationTitle="어디가지 봇". **DM: 그룹명 타이틀 반영**(방별 컨텍스트). 본문 로직 변경 적음.
- ios/WhereWeGo/App/MainTabView.swift:71-79,105-111,220-236 → DM 탭이 단일 BotChatView(botViewModel). **DM: DM 탭 = DMListView(목록)**, 방 진입 시 BotChatView(groupId) navigationDestination. botViewModel @StateObject 제거 → dmListViewModel 소유 + 방별 VM 팩토리.
- ios/WhereWeGo/Features/Chat/ (신규) → **DMListView.swift / DMListViewModel.swift**. botRooms() 로드·인스타식 읽음(unread 굵게)·방 진입·appear/복귀 refresh(백엔드가 GET 시 읽음처리 → 복귀 시 재조회).

### 참조 파일
- ios/WhereWeGo/App/AppDependencies.swift:25,80 → chatAPI=ChatAPI(client). DMListViewModel·방별 BotChatViewModel 조립 의존 출처(chatAPI/pinAPI/currentUser/deepLinkRouter).
- ios/WhereWeGo/App/FloatingTabBar.swift:53-61,72-101 → 4탭 바. DM 탭(.chat) 라벨/아이콘 "DM"·말풍선. showUnread 는 현재 notification 만 — **DM 미읽음 점 추가 시 파라미터 1개 확장**(선택 범위).
- ios/WhereWeGo/Features/Group/GroupAPI.swift:17-25 → GroupSummary(groupId·name·memberCount). 봇방 목록은 백엔드가 groupName 직접 제공 → GroupSummary 의존 불필요.
- ios/WhereWeGoTests/BotChatViewModelTests.swift:222-236,341-364 → StubChatAPI(ChatAPIProtocol 목)·makeViewModel·makeFrame. **시그니처 변경 시 동반 수정**(botRooms/group-scoped). 신규 DMListViewModelTests 추가.
- ios/WhereWeGoTests/PlaceCardSaveTests.swift:125-145 → savePlaceCards 테스트(makeViewModel + StubBotGroupAPI). **groupId 주입 방식으로 수정**.

### 백엔드 계약(이미 머지 — 읽기 전용 참조)
- backend/.../interfaces/api/chat/ChatV1Controller.java:40-71 → GET /chat/bot/rooms, POST·GET /chat/bot/{groupId}/messages. 비그룹은 @Deprecated.
- backend/.../interfaces/api/chat/ChatV1Dto.java:47-93 → MessagesResponse(groupId 포함), BotRoomSummaryResponse(7필드).
- backend/.../domain/chat/BotChatService.java:100-132,193-236 → getBotMessages @Transactional **읽음처리(markRoomRead)** / getBotRooms(활성그룹 전부, 가상항목 포함) / unread 판정(마지막 BOT & lastRead<latest).

### 비고
- iOS = **Windows 빌드 불가** → 커밋만 Windows, 빌드/시뮬/단위테스트는 Mac(DoD-B). 시그니처/enum/로직 정합은 직접 검토로 보장.
- 백엔드가 방 입장(GET) 시 읽음처리 → DMListView 는 **appear/포그라운드 복귀 시 refresh** 해야 인스타식 읽음 반영.
- references/ 없음. context/ 는 백엔드 도메인 — DM(iOS UI)과 직접 관련 적음.
