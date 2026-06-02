status: completed
advisor: codex
started: 2026-05-20T14:00:00
completed: 2026-05-20T14:10:00
findings:
  ac_total: 16
  ac_in_scope: 8
  ac_met: 7
  ac_partial: 1
  range_violation: 9
  range_violation_justified: 9
  critical: 0
  warning: 1
  info: 1
  references_violation: 0
processed:
  fixed: 2
  skipped: 0
  scope_excluded: 9
fixes:
  - warning_ac16_gap: "prd.md AC-16 검증 방법에 PR-A 기반 구조 + PR-B 최종 검증 분리 명시. 연결 FR을 FR-OBS-12 (PR-B)로 변경"
  - info_prd_frob13: "prd.md FR-OBS-13 적용 파일 + AC-8 검증 위치를 logback.xml로 정정 (application.yml 문구 제거)"
