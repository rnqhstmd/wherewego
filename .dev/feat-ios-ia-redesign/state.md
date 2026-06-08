phase: review
status: in_progress
vcs-type: git
branch: feat/ios-ia-redesign
base: develop
dev-dir: .dev/feat-ios-ia-redesign
project-type: java-spring, node (ios swift)
project-root: ./
args: "C (맵/필터 정리) 구현 시작 — 필터/범례 상단 이동 + 맵 로딩 척 최적화"
flags: (none — NORMAL)
mode: normal
intent-source: user-selection
started: 2026-06-08
current-step: "setup 완료 — Map/MainTabView/GroupContext 정독, C 코드맵 작성. 다음 requirements(PRD)"
sub-task: "C — 맵/필터 정리 (IA 재설계 GM-2 묶음, 골격 A=PR #106 위 누적)"
parent-context: "IA 재설계 묶음 브랜치 feat/ios-ia-redesign. A 내비 골격 ✅PR #106(커밋 9b70fa1, fix 2715daa). 후속: C(이번)/DM #105/D/IC-2 — 같은 브랜치 누적, Mac DoD-B 후 묶음 한번 머지. roadmap.md = 마스터 이어개발 문서."
key-decision: "C-2 맵 1회 로딩 = B안(연출만, 구조 변경 없음) 확정. 그룹 전환 줌아웃→줌인 연출만, 목록→선택 재로딩 수용. 진짜 1회 로딩(상시 마운트)은 후속 분리."
phases:
  setup: completed
  requirements: completed
  design: completed
  implement: completed
  review: completed
  complete: in_progress
execution-log:
  - phase: setup
    result: "git/develop 동기(드리프트 없음). Map 디렉토리·MapView·MapViewModel·MainTabView·GroupContext·MapContainerView 정독. C 코드맵 작성. references 없음."
