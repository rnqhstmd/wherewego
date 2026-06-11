phase: implement
status: in_progress
mode: implement
intent-source: natural-language
vcs-type: git
branch: feat/ios-instagram-redesign
base: feat/group-profile-images
dev-dir: .dev/feat-ios-instagram-redesign
project-type: ios-swiftui (+ java-spring 백엔드, IG-1은 iOS만)
project-root: ./
args: "IG-1 구현 — 셸+목록(경량 상단바·탭바 내정보 프사·그룹 목록 플랫화+＋메뉴·채팅 목록 개편). context/ig-redesign-plan.md=승인 스펙"
flags: (없음 — 경량 구현 모드)
started: 2026-06-11T12:10:00+09:00
current-step: "complete (commit/PR)"
phases:
  setup: completed
  implement: completed
  complete: in_progress
notes: |
  - PR #123(GP-1) 미머지 → stacked 유지(base=feat/group-profile-images). 머지 시 develop 리타겟.
  - 탭바 5→4탭은 IA 재설계(#106)에서 이미 완료 — IG-1 잔여는 내정보 탭 프사 원형만.
  - Windows 로컬 iOS 빌드 불가 → 검증=push 후 GitHub Actions CI.
execution-log:
  - phase: setup
    result: "코드 맵 작성(직접 탐색 — 서브에이전트 보고 미반환 환경). 4탭 이미 적용 확인"
  - phase: implement
    agent: coder
    result: "completed — 신규 InstaNavBar + ScreenHeader 삭제 + GroupListView/DMListView 플랫화 + FloatingTabBar 내정보 프사 + NotificationInboxView 헤더 교체 (보고 미반환, git/grep 직접 검증)"
  - phase: implement
    agent: 오케스트레이터 (자기점검 직접)
    result: "Critical 1건(MainTabTests FloatingTabBar 시그니처) 수정, Warning 1건(DMListViewModel 낡은 주석) 수정. 토큰 가드·ScreenHeader 잔존 0건 확인"
