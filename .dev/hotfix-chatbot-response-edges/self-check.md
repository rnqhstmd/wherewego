## 자기점검 결과 (--hotfix 모드)

### CERTAIN (Critical)
- Critical 0건

### Warning / Info
- [Info] InstagramPendingMemoHandler.java:74 — FR-4 가드의 `GROUP_LINK_QUICKREPLY_TRIGGER` 값이 `UnknownHandler`의 "그룹 연동하기" QuickReply 전송값과 일치해야 함. 현재 일치하므로 실 버그 아님. 향후 변경 시 두 곳 동시 갱신 필요 (주석 추가 권고).

### QUESTION (사용자 확인 필요)
- AC-7 충족 방식: PRD AC-7 문언은 "최근 자동저장된 장소명 포함". 현재 구현은 `RecentlyAutoSavedSession`이 URL 키 기반이라 botUserKey만으로 peek 불가하여 일반 안내로 일반화. PRD 본문 단서("URL 키 제약으로 일반화") + 변경 가이드 명시로 의도된 결정. → phase-complete 인수 검증에서 product-owner 재확인.

### AC 충족 매트릭스
| AC | 충족 | 근거 |
|----|------|------|
| AC-1 | ✓ | UnknownHandler.java:30 — 연동 사용자는 미연동 분기 진입 안 함 |
| AC-2 | ✓ | UnknownHandler.java:30-36 — resolveUserId.isEmpty() 시 "🔗 그룹 연동하기" QuickReply |
| AC-3 | ✓ | UnknownHandler.java:39-45 — pending 존재 시 "❌ 메모 없이 저장" QuickReply |
| AC-4 | ✓ | InstagramLinkHandler.java:337-341 — !pushed 시 fallback 강제 적재 |
| AC-5 | ✓ | InstagramLinkHandler.java:337 — pushed=true 시 미진입 |
| AC-6 | ✓ | InstagramPendingMemoHandler.java:63-68 — echo back (null-safe) |
| AC-7 | △ | UnknownHandler.java:49-52 — 일반화 안내. PRD 단서/변경 가이드와 일치하나 PRD 문언과는 갭 (Question 항목) |
| AC-8 | ✓ | InstagramPendingMemoHandler.java:72-79 — cancel 이전 가드 |
| AC-9 | ✓ | InstagramPendingMemoHandler.java:85 — 기존 저장 로직 유지 |

**최종**: Critical 0건. AC-7 외 8개 AC 모두 충족.
