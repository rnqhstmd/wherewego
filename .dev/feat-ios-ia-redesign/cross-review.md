# Cross-Review 결과 — DM (그룹별 봇방 목록)

- advisor: claude (오케스트레이터 직접 — oh-my-gx qa-manager/security-auditor 산출물 미반환 환경) + PR #108 봇 리뷰 통합
- 브랜치: feat/ios-ia-redesign (base: origin/develop)
- DEV_DIR: .dev/feat-ios-ia-redesign
- 대상: DM 그룹별 봇방 목록 (커밋 216b876, PR #108)

## AC 충족 매트릭스

| AC | 충족 | 근거 |
|----|------|------|
| AC-1 목록·가상항목·빈상태 | O(설계상) / ⚠️빌드차단 | DMListView 분기 + botRooms []정규화 — 단, 빌드 실패로 런타임 검증 불가(수정 전) |
| AC-2 unread 굵게+강조점 | O | DMRoomRow semibold+pinNew+배경 |
| AC-3 그룹별 송수신 | O | groupId 엔드포인트, 구 호출 0(grep) |
| AC-4 방 복귀 읽음 갱신 | O | onChange(openedRoom==nil)→refresh |
| AC-5 릴스 저장=방 그룹 | O | savePlaceCards self.groupId, createGroupIds==[7,7] |
| AC-6 로딩/에러/빈+재시도 | O | LoadState 분기 + 테스트 |
| AC-7 기존 봇채팅 회귀없음 | O | BotChatViewModel 로직 불변 |
| AC-8 테스트 갱신/신규 통과 | ⚠️→O | 빌드 차단으로 테스트 미실행(수정 전) → Hashable 수정 후 해소 |
| AC-9 DM 탭 배지 | O | hasUnread→hasChatUnread |

[Must] 전체 7건(FR-1~6,10) 설계상 충족. **빌드 차단(아래 Critical)으로 런타임/테스트 검증이 막혀 있었음 → 수정 완료.**

## 설계 범위 이탈

이탈 없음. 변경 파일이 설계서 §11 변경 범위(신규 3·수정 6·테스트 2)와 정확히 일치.

## 신규 위험 (trust-ledger 미보고분)

### Critical
- [RISK] DMListView.swift:29 — `navigationDestination(item:)`가 `BotRoomSummary`에 **Hashable**을 요구하나 모델이 Decodable/Identifiable/Equatable만 보유 → **iOS CI 빌드 실패(exit 65)**, 전 DM 기능 차단.
  - 근거: CI 로그 `instance method 'navigationDestination(item:destination:)' requires that 'BotRoomSummary' conform to 'Hashable'`. (Windows 빌드 불가로 self-check/phase-review에서 미검출 — Identifiable로 충분하다고 오판.)
  - 권고/조치: ✅ `BotRoomSummary`에 `Hashable` 추가(전 멤버 Hashable이라 합성). 수정 완료.

### Warning
- [RISK] DMListView.swift:29 — `navigationDestination(item:)` 목적지가 `nil` 경유 없이 다른 room으로 갱신될 때(딥링크/빠른 갱신) `BotChatRoomView`의 StateObject가 잔존해 잘못된 방 데이터를 표시할 수 있음. **(PR #108 Gemini Code Assist 봇 리뷰 #1, medium)**
  - 권고/조치: ✅ 목적지에 `.id(room.groupId)` 부여 → groupId 변경 시 뷰 정체성 갱신·StateObject 재구성. 수정 완료.

### Info
- [ASSUMPTION] DMListViewModel static `DateFormatter`/`ISO8601DateFormatter` Swift 6 동시성 — **(Gemini 봇 리뷰 #2, medium)**. 검증 결과 **false-positive**: `DMListViewModel`이 `@MainActor` 클래스라 static 멤버가 MainActor 격리되어 안전하며, 동일 패턴의 `NotificationInboxViewModel`이 이미 CI 통과 중. 미조치(기존 선례와 정합 유지). 빌드 실패 원인은 이 항목이 아니라 위 Hashable.
- [INFO] formatTime이 NotificationInboxViewModel.formatTime과 중복(trust-ledger 기보고). 후속 공용 유틸 통합 여지(범위 외).

## 총평
- 강점: 설계 범위 정확 준수, AC 설계상 전부 충족, 인스타식 읽음/배지/릴스 귀속 구현 충실.
- 합산: Critical 1(빌드 차단), Warning 1, Info 2. **Critical/Warning 2건 모두 즉시 수정 완료.**
- 권고: 수정 push 후 CI green 확인 필수. cross-review가 Windows 빌드 불가로 놓친 Critical(Hashable)을 포착 — 본 단계의 가치 입증.
