phase: review
status: in_progress
vcs-type: git
branch: feat/group-multi-backend
base: develop
dev-dir: .dev/feat-group-multi-backend
project-type: java-spring
project-root: ./
build-cmd: "(cd backend && ./gradlew :apps:wherewego-api:test --tests ...) Docker 27.4 가용"
args: "GM-1 그룹 다중지원 백엔드: 1인 N그룹 제약 해제(V018) + GET /groups + 단수전제 색출 + 웹 최소호환"
flags: (none)
mode: normal
intent-source: user-selection
gm-scope: "GM 전체 순차의 1단계(GM-1 백엔드). 후속: GM-2 iOS, GM-3 검증·제출"
decisions: "정원 2→10 / 1인당 무제한 / 챗봇 GM-2이관 / 웹 최소호환 / Q1 pair+rethrow / Q5 id DESC / existsActiveByUserId 제거 / 초대코드 재사용=별도(project_invite_code_system) / 토큰 1회용 동시성=markAcceptedIfPending"
started: 2026-06-05
current-step: "review 수정 4건 완료, IT 재검증 중 → complete 예정"
phases:
  setup: completed
  requirements: completed
  design: completed
  implement: completed
  review: in_progress
  complete: pending
steps:
  review:
    - mechanical-gate (GM-1 테스트 32 통과): completed
    - qa-manager + security-auditor 병렬: completed
    - 수정 4건 (wasLastMember race·정원/롤백 주석·AC-11 토큰단언): completed
    - IT 재검증: in_progress
execution-log:
  - phase: setup~implement
    result: "PRD·설계·구현·자기점검 완료. GM-1 전체 테스트 32 통과(토큰 동시성 markAcceptedIfPending 포함)"
  - phase: review
    agent: qa-manager + security-auditor
    result: "CRITICAL 0·보안취약점 0·AC 11충족. HIGH 2(wasLastMember race·정원직렬화주석) + MEDIUM. Trust Ledger 저장"
  - phase: review
    agent: 오케스트레이터(수정)
    result: "wasLastMember→leaveGroup후 countActiveByGroupId 재조회(race해소) + 정원/롤백 주석 + AC-11 토큰미소진 단언. IT 재검증 중"
