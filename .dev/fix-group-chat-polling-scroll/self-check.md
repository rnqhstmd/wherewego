# 자기점검 결과 (hotfix — qa-manager 1회 패스)

## 판정: Critical 0건 → 통과

## SELF_CHECK_FINDINGS
- [Warning] ios/WhereWeGoTests/GroupChatViewModelTests.swift:203-207 — AC-5 테스트가 waitUntil 내부 Task.sleep의 양보에 의존(우연히 결정적). sleeper를 `{ _ in await Task.yield() }`로 교체하면 결정성 향상. 현재 경쟁 조건 없이 작동.
- [Warning] ios/WhereWeGoTests/GroupChatViewModelTests.swift:226 — AC-6 정확 등치 검증이 라이브 폴링 타이밍에 의존하는 플레이키 잠재성. appear() 직후 disappear() 격리로 실질 위험 낮음.
- [Info] GroupChatView.swift:90 — 빈 방 첫 메시지는 onChange가 아닌 ScrollViewReader 재마운트 시 onAppear가 스크롤 담당 (Q1 해소 근거).
- [Info] GroupChatViewModel.swift — runSendPolling 종료 후 startSendPolling 재호출 시 새 루프 생성 가능(의도된 동작, 주석 미명시).
- [Info] BotChatViewModelTests.swift:394 — StubChatAPI.groupMessagesCallCount가 봇/그룹 테스트 공용(향후 혼합 시나리오 시 분리 고려).

## SELF_CHECK_QUESTIONS (hotfix — 오케스트레이터가 해소)
- Q1 (AC-8 빈 방 첫 스크롤): **(a) 채택** — emptyState→ScrollViewReader 재마운트 시 onAppear가 첫 스크롤 담당. 코드 검증으로 해소, 조치 불필요.
- Q2 (reconcile 완료 후 취소 체크): **(b) 채택** — runSendPolling에 reconcile 후 `if Task.isCancelled { return }` 1줄 추가 적용 완료 (AC-7 엄밀 보장).

## AC 체크리스트 (qa-manager)
AC-1 ✓ / AC-2 ✓ / AC-3 ✓ / AC-4 ✓ / AC-5 ✓ / AC-6 ✓ / AC-7 ✓(Q2 보강 후) / AC-8 ✓(Q1 — onAppear 경로) / AC-9 ✓
