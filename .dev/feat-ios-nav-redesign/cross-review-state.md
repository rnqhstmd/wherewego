status: completed
advisor: claude
started: 2026-06-03
completed: 2026-06-03
findings:
  ac_total: 21
  ac_met: 21
  range_violation: 0
  critical: 0
  warning: 1
  info: 3
  references_violation: 0
  security_critical_high: 0
  design_decision_pending: 1  # 알림 종류 푸시 딥링크 미매핑(YAGNI 의도 vs 백엔드 확인)
pr_review_merged:
  source: PR #94 gemini-code-assist
  critical: 1   # currentUser.clear() await → false positive(clear는 동기 @MainActor), 미적용
  high: 1       # didReadAll 세션 고착 → 탭 재진입 읽음 누락 (반영)
  medium: 3     # onMapMoved isCreating 가드 / error.localizedDescription / ReverseGeocoder String(format:) (반영)
processed:
  # cross-review: 1번 Warning + 3번 Info 하드닝 (사용자 선택) + PR Gemini 리뷰 반영
  fixed: "cross-review #1(알림 중복호출/배지) + #3(mapViewModel weak 가드) + gemini HIGH(didReadAll 재진입) + gemini MEDIUM×3"
  skipped: "cross-review #2(알림 푸시 딥링크=백엔드 확인 보류) + gemini CRITICAL(await=false positive)"
