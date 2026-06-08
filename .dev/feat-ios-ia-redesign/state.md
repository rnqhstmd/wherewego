phase: complete
status: in_progress
vcs-type: git
branch: feat/ios-ia-redesign
base: develop
dev-dir: .dev/feat-ios-ia-redesign
project-type: java-spring, node (ios swift)
project-root: ./
args: "DM — 그룹별 봇방 목록 구현 (#105 소비, IA 재설계 GM-2)"
flags: (none — NORMAL)
mode: normal
intent-source: user-selection
started: 2026-06-08
current-step: "requirements — PRD 작성(오케스트레이터 직접, product-owner 미반환)"
parent-context: "IA 재설계 GM-2. A 골격 #106·C 맵/필터 #107 모두 develop 머지 완료(deb546f). feat 브랜치 develop 동기화 완료. DM = #105(봇 그룹별 API) 소비 단계. 단계별 PR 전략(같은 브랜치). Mac DoD-B·머지=리뷰어. push 전 gh switch rnqhstmd."
key-decision: "백엔드 #105 이미 머지(GET /chat/bot/rooms + POST·GET /chat/bot/{groupId}/messages). DM=iOS 전용 변경. 방 입장 GET 이 백엔드 읽음처리 → 목록 복귀 시 refresh."
phases:
  setup: completed
  requirements: in_progress
execution-log:
  - phase: setup
    result: "develop 동기화(feat→deb546f, C #107 머지 반영). DM 코드맵 작성. 백엔드 #105 계약 확인(ChatV1Controller/Dto/BotChatService/BotRoomSummary). iOS 소비측 정독(ChatAPI/Models/BotChatVM/BotChatView/MainTabView/AppDependencies/FloatingTabBar/GroupAPI). 테스트 목 패턴(StubChatAPI) 파악. references 없음."
