# 코드 맵: iOS 그룹 채팅 폴링/스크롤 버그 수정

## 핵심 파일
- ios/WhereWeGo/Features/Chat/Group/GroupChatViewModel.swift:167-194 → applyLatestPage — reconcile(merge) 경로가 nextCursor/hasMore를 무조건 덮어씀 (버그 ②)
- ios/WhereWeGo/Features/Chat/Group/GroupChatViewModel.swift:251-260 → runSendPolling — 조기 종료 없이 고정 10회×2초 (버그 ③)
- ios/WhereWeGo/Features/Chat/Group/GroupChatView.swift:87-89 → onChange(messages.count) 기반 scrollToBottom — loadMore prepend 시 하단 튕김 (버그 ①)
- ios/WhereWeGo/Features/Chat/ChatScrollContainer.swift:81-83 → 봇챗 공용 스크롤 컨테이너 — 동일 count 기반 스크롤 (버그 ① 동일 패턴)
- ios/WhereWeGoTests/GroupChatViewModelTests.swift → GroupChatViewModel 단위 테스트(MockChatAPI/sleeper 주입) — 회귀 테스트 추가 대상

## 참조 파일
- ios/WhereWeGo/Features/Chat/Group/GroupChatModels.swift → GroupChatFrame(messageId)/GroupMessagesResponse(nextCursor, hasMore)
- ios/WhereWeGo/Features/Chat/Bot/BotChatViewModel.swift:173-184 → 봇 폴링 루프 — pendingProcessingIds 비면 조기 종료(참조 패턴)
- ios/WhereWeGo/Features/Chat/Bot/BotChatView.swift → ChatScrollContainer 소비자(시그니처 변경 시 영향)
- ios/WhereWeGo/Features/Chat/ChatAPI.swift → groupMessages(cursor/limit)/sendGroupMessage
- context/chat/architecture.md → 핵심 설계 결정 2: 수신 4경로(전송 직후 2초×10 폴링은 "빠른 왕복 대화 보완" 목적의 문서화된 패턴)

## 설정
- ios/project.yml → XcodeGen 프로젝트 정의(파일 추가 시 재생성 필요 — 본 작업은 기존 파일 수정만이라 영향 없을 예정)
