phase: complete
status: in_progress
mode: hotfix
intent-source: natural-language
vcs-type: git
branch: feat/ios-nav-redesign
base: develop
dev-dir: .dev/feat-ios-nav-redesign
project-type: java-spring, ios-swift
project-root: ./
args: "봇 채팅 WebSocket(STOMP)→이벤트(APNs+폴링) 전환 (긴급)"
flags: --hotfix
started: 2026-06-04
current-step: "인수 검증"
note: "P7 산출물 보존 위해 chat-event-* 접두사 산출물 사용. PRD=chat-event-prd.md, 설계=chat-event-migration.md, 코드맵=chat-event-codemap.md"
decisions:
  - "폴링 상한: 2초 간격 × 10회(20초). 봇 SLA sync-deadline-ms=4500 기준"
  - "포그라운드 APNs 즉시 reconcile: 생략(폴링+scenePhase 커버)"
  - "spring-boot-starter-websocket 제거 확정(사용처 STOMP 전용)"
  - "배포 순서: iOS 먼저 → 백엔드"
phases:
  setup: completed
  requirements: completed
  implement: pending
  complete: pending
execution-log:
  - phase: setup
    result: "브랜치 feat/ios-nav-redesign 유지, base develop, 코드맵 작성, DOMAIN_CONTEXT(notification 옵션B 선례·chatbot 카카오톡 기반) 확보"
  - phase: requirements
    agent: product-owner
    result: "경량 PRD 작성. 확인 2건 사용자 확정(폴링 20초/10회, 푸시 reconcile 생략). syncDeadlineMs=4500·websocket STOMP전용 코드 확인"
