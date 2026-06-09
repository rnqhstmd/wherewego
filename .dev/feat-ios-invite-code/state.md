phase: review
status: in_progress
vcs-type: git
branch: feat/ios-invite-code
base: develop
dev-dir: .dev/feat-ios-invite-code
project-type: [java-spring, node, ios-swift]
project-root: ./
args: "IC-2 iOS 초대 코드(코드 입력 가입 + 코드 발급/공유)"
flags: (none)
mode: normal
intent-source: user-selection
started: 2026-06-09
current-step: "완료(커밋/PR)"
phases:
  setup: completed
  requirements: completed
  design: completed
  implement: completed
  review: completed
  complete: in_progress
execution-log:
  - phase: setup
    result: "브랜치 feat/ios-invite-code 생성(base develop), DEV_DIR + codemap 작성"
  - phase: setup
    note: "InviteCodeView/VM 기존 존재하나 token 직접 accept로 깨짐(slug 미대응). 발급/공유는 WelcomeWizard에만 존재, GroupManageView엔 없음"
