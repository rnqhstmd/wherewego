# Trust Ledger

### Hotfix 긴급 감사 (security-auditor, CRITICAL/HIGH만)

- [ASSUMPTION/HIGH] 조기 종료 후 라이브 폴링 생존 여부 미검증
  - 근거: send-poll 조기 종료는 "이후 8초 라이브 폴링이 수신을 잇는다"를 전제하나, 라이브 폴링 생존을 보장하는 테스트 없음. scenePhase 복귀 핸들러는 reconcile 1회만 호출하고 폴링을 재시작하지 않음.
  - 판정: **위험 수용** — 라이브 폴링 라이프사이클(appear 시작/disappear 취소)은 본 수정 범위 밖의 기존 동작이며, 방 표시 중에는 Task가 유지됨(백그라운드 suspend 후 재개 포함). 수신 4경로의 나머지(APNs willPresent·scenePhase)가 보완. 후속 과제로 기록.
- [ASSUMPTION/HIGH] currentUserId nil 시 내 메시지를 타인으로 오판하는 조기 종료 경로 (BR-5 불일치)
  - 근거: currentUser.load() 실패 상태에서 send → reconcile이 낙관 프레임을 서버 진실(senderUserId non-nil)로 교체하면 `senderUserId != nil && != nil(currentUserId)` → true.
  - 조치: **자동 수정 완료** — 판정을 `if let myId = currentUserId, ...`로 교체. currentUserId 미확보 시 판정 자체를 skip(보수적 비-종료).
- [GAP/HIGH] AC-5 테스트의 waitUntil 타임아웃 false negative
  - 근거: waitUntil은 3초 타임아웃 시에도 그냥 반환 — 조건 미충족이 검증을 통과할 수 있음.
  - 조치: **자동 수정 완료** — waitUntil 직후 도달 여부 명시 XCTAssertTrue(타임아웃 메시지 포함) 추가.

#### 재감사 기록
- 자동 수정 2건(#2, #3)에 대한 security-auditor 재호출이 컨텍스트 한도 초과("Prompt is too long")로 실패. 재호출은 1회 제한이라 재시도하지 않음.
- 대체 검증(오케스트레이터): 두 수정 모두 원 감사 보고서의 권고문을 그대로 구현(#2 `if let myId = currentUserId` 판정 skip, #3 waitUntil 직후 명시 XCTAssertTrue)했고, 적용 후 테스트 27/27 통과로 확인. **해소로 판정.**

#### 교차 검증 (감사 정합 확인)
- FR-1/2/3/4, BR-2/3/4, AC-7 — 구현 정합 확인됨.
- AC-9 노트: reconcileLatest는 isLoading을 확인하지 않으나 @MainActor 직렬 실행으로 데이터 레이스 없음. 커서 소유권 분리로 유해 경로(커서 클로버링)는 제거됨. 메시지 순서 인터리빙 가능성은 기존 동작 — 후속 과제.

#### 자기점검 이월 항목 (qa-manager Warning/Info)
- [Warning] AC-5 테스트의 양보 의존(waitUntil 내부 sleep) — 결정성 개선 여지 (감사 #3 수정으로 부분 보강).
- [Warning] AC-6 정확 등치 검증의 플레이키 잠재성 — appear 직후 disappear 격리로 실질 위험 낮음.
- [Info] 빈 방 첫 스크롤은 ScrollViewReader 재마운트 onAppear가 담당(AC-8 충족 경로).
- [Info] StubChatAPI.groupMessagesCallCount가 봇/그룹 공용 — 혼합 시나리오 테스트 추가 시 분리 고려.
