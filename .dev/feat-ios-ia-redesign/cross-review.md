# Cross-Review 결과 — D (그룹관리 / 알림 그룹명 / 내정보 축소)

- advisor: claude (오케스트레이터 직접 — oh-my-gx qa-manager/security-auditor 산출물 미반환 환경)
- 브랜치: feat/ios-group-manage (base: feat/ios-ia-redesign, stacked)
- DEV_DIR: .dev/feat-ios-ia-redesign
- 대상: D (커밋 4a83cb5, PR #109)

## AC 충족 매트릭스

| AC | 충족 | 근거 |
|----|------|------|
| AC-1 알림 그룹명 | O | `NotificationRow`에 `item.groupName` 칩(cta 캡슐, nil 생략) + 상세 헤더 `detail.groupName`. 백엔드 `NotificationV1Dto`/`NotificationService` groupName(GroupRepository 주입) |
| AC-2 내정보 축소 | O | `MyInfoView` 그룹섹션 제거, `MyInfoViewModel` activeGroup/leaveGroup/groupAPI 제거, `MainTabView` 호출 정합(grep) |
| AC-3 ⋯→GroupManageView | O | `MapView` groupManageSheet→`GroupManageHost`(@StateObject 소유) |
| AC-4 방장 뱃지 | O | `GroupManageView.memberRow` isOwner "방장" 캡슐, 백엔드 `listMembers` 첫 항목(i==0) 마킹 |
| AC-5 이름수정(모든멤버)+상단반영 | O | `renameGroup` requireActiveMembership+30자 PATCH, `onRenamed→groupContext.refresh()`→`currentGroupName`(groups[currentGroupId].name) 갱신 — **설계 R5 해소 확인** |
| AC-6 삭제(방장만)+403 | O | `deleteGroup` 방장!=요청자→`GROUP_OWNER_REQUIRED`, iOS `isOwner`만 버튼 노출 + 백엔드 이중검증 |
| AC-7 방장 자동승계 | O | joined_at 최소 조회시점 계산(컬럼 없음), `GroupMemberServiceTest` 통과 |
| AC-8 일반멤버 탈퇴만 | O | `dangerSection` 탈퇴 항상 노출, 삭제는 isOwner 분기 |

[Must] **8/8 충족**. iOS CI(GitHub Actions) green(3m17s) + 백엔드 단위/통합(PostgreSQL) 통과로 런타임 검증됨.

## 설계 범위 이탈

- `ios/.../Core/Auth/AuthServiceProtocols.swift`: `GroupAPIProtocol` 정의 위치. 설계 §3.3 "GroupAPIProtocol + GroupAPI 양쪽"으로 암시됨 → **실질 이탈 아님**.
- **iOS 테스트 11개 동반 수정**(+3씩: AddPlaceViewModelTests/GroupContextTests/InlineAddPlaceModeTests/MainTabTests/MapCacheAndPollingTests/MapViewModelTests/NotificationInboxViewModelTests/PinDetailViewModelTests/RouletteViewModelTests/RouteGuardTests/VisitOrchestrationTests): `GroupAPIProtocol` mock(stub)이 여러 테스트에 분산되어, 신규 3메서드 추가로 컴파일 정합 위해 동반 수정됨. 설계 §3.7은 `GroupManageViewModelTests` 신규 + `MyInfoViewModelTests` 수정만 명시 → **설계 미명시 범위 확대**.
  - 정당성: 프로토콜 확장의 불가피한 파급(위험 아님, self-check "전 stub 구현"으로 일부 인지).
  - 교훈: 설계 시 "프로토콜 변경 = N개 stub 동반 수정"을 변경 범위에 포함할 것.

## 신규 위험 (trust-ledger/self-check 미보고분)

### Info
- [GAP] `NotificationInboxView.swift:267` `NotificationRow.message` — 알림 **목록 행** 문구가 작성자 닉네임(`item.registeredByNickname`) 대신 종류별 고정 문구("파트너가 새 장소를 등록했어요" 등)를 표시한다. PRD AC-1의 "누가"는 **상세**(`detail.registeredByNickname`)에서만 충족되고 목록 행엔 미표시. 1인 N그룹에서 "파트너" 표현은 커플 잔재.
  - 위치: `ios/WhereWeGo/Features/Notification/NotificationInboxView.swift:267-276`
  - 근거: D 범위는 `groupName` 추가(충족). 작성자 표시는 **기존 코드** — D가 만든 결손 아님.
  - 권고: D 스코프 밖. 후속 알림 카피 개선 시 목록 행에도 `registeredByNickname` 반영 검토(선택).

## 총평
- 강점: AC 8/8 충족, 방장 판정·자동승계 백엔드 IT 검증, 권한 이중검증(iOS `isOwner` + 백엔드 `GROUP_OWNER_REQUIRED`), iOS CI 첫 push green(DM의 Hashable 같은 컴파일 결손 0).
- 합산: **Critical 0, Warning 0, Info 1**(D 범위 밖). 설계 범위 이탈 1건(테스트 stub 정합, 정당).
- 권고: 클린 통과. Info 1(목록 행 작성자 표시)은 D 스코프 밖 → 후속 개선. #108 머지 후 PR #109 base develop 리타겟.

## 처리 결과
- 사용자 선택: **전부 건너뛰기**. Critical/Warning 0이라 수정 불요.
- Info 1(NotificationRow 작성자 표시)은 D 스코프 밖(기존 코드 한계) → 후속 알림 카피 개선 시 반영하도록 기록 보존.
