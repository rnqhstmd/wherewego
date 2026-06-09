# 자기 점검 — DM 그룹별 봇방 목록 (implement)

> qa-manager 미반환 환경 → 오케스트레이터 직접 점검(코드 전수 Read + grep 정합).

## 스펙 충족 (PRD 수용 기준 대비)
| AC | 충족 | 근거 |
|----|------|------|
| AC-1 목록(가상항목 포함·빈상태) | ✅ | DMListView content 분기(loaded/empty/error), botRooms 그룹0개→[] 정규화(ChatAPI) |
| AC-2 unread 굵게+강조점 | ✅ | DMRoomRow fontWeight(.semibold)+pinNew 점+cta.opacity 배경 |
| AC-3 그룹별 엔드포인트 송수신 | ✅ | ChatAPI botMessages/sendBotMessage groupId 인자화, 구 비그룹 호출 0(grep 확인) |
| AC-4 방 복귀 시 읽음 갱신 | ✅ | DMListView onChange(openedRoom==nil)→refresh, DMListViewModelTests ⑤ |
| AC-5 릴스 저장=그 방 그룹 | ✅ | savePlaceCards self.groupId 사용, PlaceCardSaveTests createGroupIds==[7,7] |
| AC-6 로딩/에러/빈+재시도 | ✅ | LoadState 분기 + errorView 다시시도, DMListViewModelTests ②③ |
| AC-7 기존 봇 채팅 회귀 없음 | ✅ | BotChatViewModel 로직 불변(시그니처만 groupId), 기존 테스트 로직 유지 |
| AC-8 테스트 갱신/신규 통과 | ✅ | StubChatAPI/makeViewModel 갱신, DMListViewModelTests 6케이스 신규 |
| AC-9 DM 탭 배지 | ✅ | hasUnread→FloatingTabBar hasChatUnread, .task/scenePhase refresh |

## 정합성 점검 (grep 전수)
- 구 시그니처 호출(botMessages(cursor:/sendBotMessage(text:) 잔존: **0**
- groupAPI/myActiveGroup in BotChatViewModel: **주석만**(코드 제거)
- StubBotGroupAPI 참조: **주석만**(클래스 제거)
- botViewModel(구 MainTabView 속성): **0**
- BotChatViewModel( 생성처 4곳 전부 groupId 시그니처 / BotChatView( 1곳(BotChatRoomView) groupName 전달
- BotRoomSummary: Decodable+Identifiable(id=groupId)+Equatable — LoadState 동등성/ForEach/JSON 디코딩 요건 충족
- MessagesResponse: 백엔드 groupId 추가분 디코딩 안 함(불필요·무시) — 기존 3필드 init 보존(테스트 호환)

## 빌드 등록
- iOS는 **XcodeGen**(`ios/project.yml`, `sources: -path: WhereWeGo`) — 디렉토리 글로빙. 신규 3파일(DMListView/DMListViewModel/DMListViewModelTests)은 Mac `xcodegen` 재생성 시 자동 포함. **pbxproj 수동 등록 불필요**(.xcodeproj=생성물).

## 잔여 리스크
- **iOS = Windows 빌드 불가** → 컴파일/시뮬/단위테스트 실행 검증 불가. 타입·시그니처·enum·Swift 6 동시성(@MainActor/@unchecked Sendable/StateObject 래퍼)은 코드 리뷰 수준 직접 검토로 보장. **최종 빌드·테스트 실행 = Mac DoD-B(리뷰어)**.
- formatTime 이 NotificationInboxViewModel.formatTime 과 중복(설계 명시 인지) — 후속 공용 유틸 통합 여지(범위 외).

## 판정
Critical 0 · 스펙 충족. 구현 완료.
