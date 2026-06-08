status: completed
advisor: claude
base: origin/develop (merge-base deb546f)
scope: DM (ios 11파일) + PR #108 봇 리뷰 통합
findings:
  ac_total: 9
  ac_met: 9   # 설계상 충족(빌드 차단 수정 후 런타임/테스트 검증 가능)
  range_violation: 0
  critical: 1   # navigationDestination(item:) Hashable 미충족 → CI 빌드 실패
  warning: 1    # navigationDestination .id(groupId) 미부여(Gemini #1)
  info: 2       # Gemini #2 concurrency false-positive, formatTime 중복(기보고)
  references_violation: 0
processed:
  fixed: 2     # Hashable 추가 + .id(room.groupId) 추가
  skipped: 0
note: "cross-review가 Windows 빌드 불가로 놓친 Critical(BotRoomSummary Hashable)을 포착. PR #108 Gemini 봇 리뷰 2건 중 #1(.id) 반영, #2(concurrency)는 NotificationInbox 선례로 false-positive. 수정 push로 CI 재실행."
