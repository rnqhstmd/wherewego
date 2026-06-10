phase: complete
status: in_progress
vcs-type: git
branch: feat/ios-group-chat
base: develop
dev-dir: .dev/feat-ios-group-chat
project-type: ios-swift
project-root: ./
args: "GC-2 (iOS) — context/chat/status.md FR-GC2-1~8 + architecture.md §GC-2 의존 계약 기준으로 구현시작"
flags: (none)
mode: normal
intent-source: user-selection
started: 2026-06-10
current-step: "coder 구현"
phases:
  setup: completed
  requirements: completed
  design: completed
  implement: in_progress
steps:
  implement:
    - coder 구현: in_progress
    - 자기점검: pending
decisions:
  - 전환: 신규 GroupChatView/GroupChatViewModel 신설, BotChatView dead code(GC-3 제거)
  - URL감지: 메시지 전체 trim이 인스타 URL 1개일 때만 REEL_LINK
  - 수신: 전송직후 폴링 + willPresent/scenePhase 재조회 + 방 표시 중 8초 폴링
  - 셸정리: 탭명 'DM'→'채팅' + 봇 전제 문구 교체 + 봇 흔적 제거 포함
  - 탭명: 채팅
backend-contract:
  - GET /api/v1/chat/groups -> [GroupRoomSummaryResponse{roomId?,groupId,groupName,lastPreview?,lastSenderUserId?,hasUnread,lastAt?}]
  - GET /api/v1/chat/groups/{groupId}/messages?cursor=&limit= -> GroupMessagesResponse{groupId,messages:[GroupChatMessageFrame],hasMore,nextCursor}
  - POST /api/v1/chat/groups/{groupId}/messages {kind,text?,url?} -> SendMessageResponse{messageId,kind}
  - POST /api/v1/chat/groups/{groupId}/messages/{messageId}/extract -> PlaceCardsPayload{cards,sourceInstagramUrl}
  - GroupChatMessageFrame{messageId,roomId,senderUserId?,senderNickname?,kind,payload,registered?,createdAt}; REEL_LINK payload {url,thumbnailKey}
  - push GROUP_MESSAGE {type,roomId}
execution-log:
  - phase: setup
    result: "git/develop base, 브랜치 feat/ios-group-chat, 코드맵 작성"
  - phase: requirements
    result: "PRD 직접 작성, Q&A 5문항 확정, 승인"
  - phase: design
    result: "설계 직접 작성(architect 미사용), 자가 design-critic 보완(빈방 roomId), 승인. 신규6+수정12+테스트, 9단계"
