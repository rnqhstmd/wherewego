# IG-1 자기점검 (오케스트레이터 직접 수행 — qa-manager 보고 미반환 환경)

## Critical (수정 완료)
- [Critical/해소] ios/WhereWeGoTests/MainTabTests.swift:70 — FloatingTabBar init에 currentUser 필수 파라미터 추가로 구 시그니처 생성부 컴파일 깨짐 → `currentUser: makeCurrentUser()`(BotChatViewModelTests의 공용 헬퍼) 추가 + 주석 보정.

## Warning (수정 완료)
- [Warning/해소] DMListViewModel — currentUserId 프로퍼티 제거 후에도 헤더/fetch 주석이 "미리보기 '나:' 판정" 용도를 가리킴 → 워밍업(방 isMine 판정·탭바 프사) 용도로 주석 갱신. currentUser.load() 자체는 유지(GroupChatViewModel isMine·탭바 프사 소스).

## 검증 결과
- ScreenHeader 코드 참조 0건(InstaNavBar 주석 내 언급 2건만), 파일 삭제됨.
- 하드코딩 색(#hex / Color(red:)) 0건 — 전부 WGColor/WGFont 토큰.
- currentUserId 잔존 참조는 GroupChatViewModel/GroupMessageRow 계열(별개 기능)뿐 — DMList 계열 정리 완료.
- 테스트 타깃에서 DMListViewModel.currentUserId 참조 없음(grep 0건).
- Windows 로컬 빌드 불가 → 최종 컴파일/테스트 검증은 push 후 GitHub Actions CI.

## QUESTION (없음)
