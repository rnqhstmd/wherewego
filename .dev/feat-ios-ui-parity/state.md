```yaml
phase: complete
status: completed
branch: feat/ios-ui-parity
base: feat/ios-ui-parity
project-type: ios-swift-xcodegen
project-root: ./
args: "내비 셀 재구성 긴급 수정"
mode: hotfix
intent-source: natural-language
flags: --hotfix(자연어 '긴급' 감지)
started: 2026-06-06
last-known-head: 018ba1bd9065eb98a4f3f3816a151876e2fd89dd
current-step: "implement 완료(구현+자기점검+긴급감사+핵심수정, 빌드 그린) — complete 진입"
notes: >
  현재 브랜치(feat/ios-ui-parity)가 main보다 244커밋 앞섬. 작업트리에 세션 UI수정 +
  사전 WIP + 시크릿(Debug.xcconfig)이 섞여 있어 별도 브랜치/스태시 없이 현재 브랜치에서 진행.
  complete 단계에서 내비 관련 파일만 선별 커밋(Debug.xcconfig 등 시크릿/무관 WIP 제외).
phases:
  setup: completed
  requirements: completed
  implement: completed
  complete: completed
last-known-head: ea9a926
  complete: pending
execution-log:
  - phase: setup
    result: "base=feat/ios-ui-parity(사용자 확정), 현재 브랜치 진행, DEV_DIR 생성"
```
