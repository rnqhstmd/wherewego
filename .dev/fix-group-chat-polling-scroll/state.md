```yaml
phase: complete
status: completed
branch: fix/group-chat-polling-scroll
base: develop
project-type: ios-xcodegen (멀티: java-spring backend, node frontend — 본 작업은 ios만)
project-root: ./
args: "해당 버그 수정 구현 — iOS 그룹 채팅 버그 3건(스크롤 튕김/커서 덮어쓰기/send-poll 조기종료 없음)"
flags: --hotfix
mode: hotfix
intent-source: user-selection
started: 2026-06-10T20:20:00+09:00
auto-stashed: false
last-known-head: 4ab9c88d75fb83bef660fbd67664e55e252e16de
current-step: "완료 — PR #122"
phases:
  setup: completed
  requirements: completed
  implement: completed
  complete: completed
steps:
  implement:
    - coder 구현: completed
    - 자기점검: completed
    - QUESTION 해소(Q2 취소체크 1줄): completed
    - hotfix 긴급 감사: completed
    - 감사 HIGH 자동수정(2건): completed
    - 테스트 재검증(27/27): completed
    - 감사 재호출: in_progress
execution-log:
  - phase: setup
    result: "브랜치 fix/group-chat-polling-scroll 생성(base develop), auto-stash 복원, chat 도메인 컨텍스트 로드"
  - phase: requirements
    agent: product-owner (경량 PRD)
    result: "PRD 확정(FR 4, BR 5, AC 9) — 사용자 승인"
  - phase: implement
    agent: coder
    result: "5파일 수정(소스 3 + 테스트 2), 회귀 테스트 4개 추가, GroupChatViewModelTests 16개 통과. 환경 이슈(SPM 캐시 손상·stale xcodeproj) 우회/복구"
    files: ["GroupChatViewModel.swift", "GroupChatView.swift", "ChatScrollContainer.swift", "GroupChatViewModelTests.swift", "BotChatViewModelTests.swift"]
  - phase: implement
    agent: qa-manager (자기점검)
    result: "Critical 0건, Warning 2건, QUESTION 2건(Q1 코드검증으로 해소, Q2 1줄 보강 적용)"
  - phase: implement
    agent: security-auditor (hotfix-audit)
    result: "CRITICAL 0, HIGH 3 — #2(nil 오판 가드)·#3(테스트 assert) 자동 수정, #1(라이브 폴링 생존) 위험 수용"
  - phase: implement
    step: 테스트 재검증
    result: "Bot 11 + Group 16 = 27개 전부 통과(수정 반영 후 2회 검증)"
```

## 비고
- 커밋/PR 시 사용자 로컬 변경 제외 필수: ios/Config/Debug.xcconfig(로컬 키), ios/project.yml, ios/WhereWeGo/WhereWeGo.entitlements, ios/ShareExtension/ShareExtension.entitlements, ios/ShareExtension/Info.plist(untracked) — 스테이징은 채팅 5개 파일만.
