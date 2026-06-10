## PRD: iOS 그룹 채팅 버그 3건 수정

### 배경

그룹 채팅은 WebSocket 없이 수신 4경로(전송 직후 제한 폴링 2초×10 / APNs willPresent / scenePhase 복귀 / 방 표시 중 8초 라이브 폴링)로 메시지를 수신하는 구조다. 코드 리뷰에서 이 구조 내에 3건의 버그가 확인되었다.

- **버그 ①** GroupChatView와 ChatScrollContainer 모두 `messages.count` 변화 시 무조건 맨 아래로 스크롤한다. `loadMore`로 과거 메시지를 prepend할 때도 count가 증가하므로, 사용자가 위로 스크롤해 과거 대화를 읽는 중 강제로 최하단으로 끌려 내려간다.
- **버그 ②** `applyLatestPage`의 병합(reconcile) 경로가 `nextCursor`/`hasMore`를 항상 덮어쓴다. 8초 라이브 폴링이 실행될 때마다 `loadMore`로 축적한 페이지네이션 커서가 1페이지 시점으로 리셋되어 이미 본 구간을 재순회한다.
- **버그 ③** `runSendPolling`에 조기 종료 조건이 없어 무조건 10회×2초를 소진한다. 전송 직후 상대방의 빠른 답장이 이미 수신됐어도 남은 횟수를 모두 소모하며 라이브 폴링(8초)과 이중 호출한다.

---

### 요구사항

#### 기능 요구사항

- [Must] FR-1: GroupChatView의 `onChange(of: viewModel.messages.count)` 트리거를 교체한다. 마지막 메시지 ID가 새로운 값으로 변경될 때(신규 메시지 append), 그리고 해당 ID가 직전 count 변화 전 배열에 없던 경우에만 `scrollToBottom`을 호출한다. `loadMore`에 의한 count 증가(맨 앞 prepend)는 스크롤을 유발하지 않는다.
- [Must] FR-2: ChatScrollContainer의 `onChange(of: messages.count)` 트리거를 FR-1과 동일한 정책으로 교체한다(BotChatView 소비 경로 포함).
- [Must] FR-3: `applyLatestPage`의 병합(replaceAll: false) 경로에서 `nextCursor`/`hasMore` 갱신을 제거한다. `nextCursor`와 `hasMore`는 `replaceAll: true`(load 전체 교체)와 `loadMore`(추가 페이지 로드) 호출 시에만 갱신된다.
- [Must] FR-4: `runSendPolling`에 조기 종료 조건을 추가한다. 각 회차 `reconcileLatest` 완료 후, 직전 `messages` 스냅샷 대비 다른 멤버의 새 메시지가 1건 이상 append되었으면 루프를 즉시 종료한다. 10회 소진 또는 취소 시 기존대로 종료한다.

#### 비즈니스 규칙

- [Must] BR-1: 수신 4경로 설계(전송 직후 제한 폴링 / APNs / scenePhase / 8초 라이브 폴링) 자체는 변경하지 않는다. 각 경로의 동작 범위와 책임만 명확히 한다.
- [Must] BR-2: 스크롤 정책에서 "신규 메시지"는 배열 끝(append)에 추가된 메시지만 해당한다. 배열 앞(prepend, loadMore 경로)에 삽입된 메시지는 스크롤을 유발하지 않는다.
- [Must] BR-3: 커서 상태(`nextCursor`, `hasMore`)의 소유권은 load(전체 교체)와 loadMore(페이지 추가)에만 있다. reconcile(폴링·scenePhase·willPresent 경로)은 커서를 읽지 않고 쓰지도 않는다.
- [Must] BR-4: `runSendPolling`의 목적은 "전송 직후 상대방의 빠른 답장을 8초보다 빨리 수신"이다. 이 목적이 달성되면(다른 멤버의 새 메시지 수신 확인) 즉시 종료하고 이후는 8초 라이브 폴링에 위임한다.
- [Should] BR-5: 조기 종료 판정에서 "다른 멤버의 새 메시지"는 `senderUserId != currentUser.id`인 프레임이 폴링 회차 전후로 새로 append된 것을 기준으로 한다. 낙관 append된 내 메시지는 조기 종료 조건에 포함하지 않는다.

---

### 수용 기준

- AC-1: `loadMore` 호출 후 `messages.count`가 증가해도 스크롤 위치가 유지된다. `messages` 배열에 신규 메시지가 append된 경우에만 `scrollToBottom`이 호출된다. → [FR-1, BR-2]
- AC-2: ChatScrollContainer(`BotChatView` 소비 경로)도 동일하게, `loadMore`에 의한 count 증가는 스크롤을 유발하지 않는다. → [FR-2, BR-2]
- AC-3: `reconcileLatest` 호출 후 `nextCursor`와 `hasMore` 값이 변경되지 않는다. `loadMore` 직후 커서 값이 reconcile 폴링 10회 이후에도 `loadMore` 시점 값을 유지한다. → [FR-3, BR-3]
- AC-4: `replaceAll: true`(load) 및 `loadMore` 호출 후에는 `nextCursor`/`hasMore`가 응답값으로 정상 갱신된다. → [FR-3]
- AC-5: `runSendPolling` 실행 중 다른 멤버의 새 메시지가 수신되면 그 회차 이후 루프가 종료되고 `sendPollingTask`가 nil이 된다. 잔여 sleep 횟수가 남아 있어도 추가 `reconcileLatest`를 호출하지 않는다. → [FR-4, BR-4]
- AC-6: `runSendPolling` 실행 중 내 메시지만 수신되거나 새 메시지가 없으면 조기 종료 없이 최대 10회까지 계속 실행된다. → [FR-4, BR-5]
- AC-7: `runSendPolling` 실행 중 뷰가 이탈(disappear → cancelSendPolling)하면 기존대로 즉시 종료된다. → [FR-4]
- AC-8 (엣지케이스 — 빈 방): 메시지가 0건인 상태에서 첫 메시지 send 후 `appendOptimistic`으로 count가 1이 되면 `scrollToBottom`이 호출된다. → [FR-1, BR-2]
- AC-9 (엣지케이스 — 동시 loadMore + reconcile): `loadMore` 진행 중 reconcile이 실행되어도 `nextCursor`/`hasMore`는 `loadMore` 응답으로만 확정된다. → [FR-3, BR-3]

---

### 탐색 추가 항목
- ios/WhereWeGo/Features/Chat/Group/GroupChatViewModel.swift → applyLatestPage(replaceAll:), runSendPolling, loadMore 구현
- ios/WhereWeGo/Features/Chat/Group/GroupChatView.swift → count 기반 scrollToBottom 트리거
- ios/WhereWeGo/Features/Chat/ChatScrollContainer.swift → 공용 스크롤 컨테이너 동일 패턴
- ios/WhereWeGoTests/GroupChatViewModelTests.swift → MockChatAPI/sleeper 주입 기반 단위 테스트
- ios/WhereWeGo/Features/Chat/Bot/BotChatViewModel.swift → runPollingLoop 조기 종료 참조 패턴

---

추가 확인 사항 없음. PRD가 확정되었습니다.