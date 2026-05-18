---
phase: implement
status: completed
vcs-type: git
branch: feat/phase-2-10
base: develop
dev-dir: .dev/feat-phase-2-10
project-type: java-spring + node
project-root: ./
args: "phase 2.10 기능 개발 시작해줘"
flags: ""
mode: normal
intent-source: user-selection
started: 2026-05-18T15:50:00
current-step: "코드맵/state 초기화"
phases:
  setup: completed
  requirements: completed
  design: completed
  implement: completed
  review: pending
  complete: pending
scope:
  - pin 좌표 수정 (지도 picker 재사용) + 삭제 핀 복원 UI
  - chatbot 카카오 i 오픈빌더 PLACE_SELECTION (action="message" + extra.placeId) 동작 검증
  - map Pretendard 폰트 self-host 전환 + Mapbox 토큰 회전 SOP 운영자 가이드
findings:
  - "frontend/public/fonts/README.md에는 이미 self-host 완료라고 기재됨 (map/status.md '예정' 표기와 불일치) — PRD 단계에서 사실 정합성 확인 필요"
  - "PlaceSelectionHandler는 clientExtra.placeId 우선 + params 폴백 코드는 이미 구현됨 (Phase 2.10 작업은 외부 카카오 빌더 측 시나리오 설정 + 동작 검증)"
execution-log:
  - phase: setup
    step: "VCS/베이스 브랜치 확인"
    result: "git OK, base=develop (사용자 선택)"
  - phase: setup
    step: "Phase 2.10 범위 확정"
    result: "사용자가 3개 항목 전부 선택"
  - phase: setup
    step: "브랜치 생성 + DEV_DIR"
    result: "feat/phase-2-10 생성, .dev/feat-phase-2-10/ 준비"
  - phase: setup
    step: "코드 맵 작성"
    result: "codemap.md 저장 (핵심 16 + 참조 5 + 설정 3 = 24항목)"
