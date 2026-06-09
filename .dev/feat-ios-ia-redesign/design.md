# 설계: D단계 — 알림 상세 / 내정보 축소 / 그룹관리 (IA 재설계 GM-2)

## 설계 규모
**대형 — 풀스택.** 백엔드: 신규 API 3(그룹원조회·이름수정·삭제) + 알림 DTO `groupName` + ErrorType + Repository 쿼리 + `Group.rename`. iOS: 신규 `GroupManageView`/VM, `GroupAPI` 3메서드, 알림 그룹명, 내정보 축소, MapView 통합, GroupContext 확장. 테스트 백+iOS.

> design-critic은 oh-my-gx 읽기 에이전트 산출물 미반환 환경 → 오케스트레이터 자체 비판(§6)으로 갈음.

## §1 개요 — 3개 독립 묶음
- **A. 알림 그룹명**: 백엔드 알림 DTO에 `groupName` + iOS 표시.
- **B. 내정보 축소**: iOS only — `MyInfoView` "활성 그룹" 섹션 제거.
- **C. 그룹관리(풀스택, 방장 개념)**: 그룹원조회·이름수정·삭제·탈퇴 + 방장 판정.

---

## §2 백엔드 설계

### §2.1 방장(owner) 판정 — 공통 규칙
- 방장 = **활성 멤버(`left_at IS NULL`) 중 `joined_at` 최소, 동률 시 `id` 최소**.
- 별도 owner 컬럼·승계 트랜잭션 없음 — **조회 시점 계산**. 탈퇴(`markLeft`)로 활성 집합이 줄면 다음 최선임이 자동 방장(자동 승계).
- 기존 `listActiveGroupSummariesByUserId`가 이미 `(joined_at ASC, id ASC)` 정렬 → 동일 정렬을 멤버 목록에 적용. 첫 항목 = 방장.

### §2.2 그룹원 목록 조회 — `GET /api/v1/groups/{groupId}/members`
- **권한**: 활성 멤버만(`requireActiveMembership` → 비멤버 `GROUP_NOT_MEMBER`).
- **응답**: `List<MemberResponse>(userId, nickname, joinedAt, isOwner)`. 가입 순. 첫 항목 `isOwner=true`.
- **Repository 신규**: `GroupMemberRepository.listActiveMembersByGroupId(groupId) → List<GroupMemberInfo>(userId, nickname, joinedAt, memberId)`. User 닉네임 join, 정렬 `joined_at ASC, id ASC`. 구현은 infra(JPA/QueryDSL)에서 기존 `listActiveGroupSummariesByUserId` 패턴 따름.
- **Service**: `listMembers(userId, groupId)`: `requireActiveMembership` → repo 조회 → 첫 항목만 owner 마킹 → `List<GroupMemberResult>`.
- **DTO**: `GroupV1Dto.MemberResponse` record + `from`.

### §2.3 그룹명 수정 — `PATCH /api/v1/groups/{groupId}`
- **body**: `{ "name": ... }`. **권한**: 활성 멤버(모든 멤버, `requireActiveMembership`).
- **검증**: `createGroup`과 동일 — trim 후 `isEmpty || length>30 → GROUP_NAME_INVALID`(가드 일관).
- **엔티티**: `Group.rename(String name)` 추가.
- **Service**: `renameGroup(userId, groupId, rawName)`: `findByIdForUpdate`(락·soft-delete race 방지, 선례 일관) → deleted 체크(`GROUP_NOT_MEMBER`) → `requireActiveMembership` → 검증 → `group.rename` → save.
- **Controller**: `@PatchMapping("/{groupId}")`. **DTO**: `UpdateGroupNameRequest(name)` + 응답은 `GroupCreatedResponse`(groupId,name,createdAt) 재사용 또는 `GroupRenamedResponse(groupId,name)`.

### §2.4 그룹 삭제 — `DELETE /api/v1/groups/{groupId}`
- **권한**: 방장만. 비방장 → `GROUP_OWNER_REQUIRED`(403, 신규 ErrorType).
- **동작**(`@Transactional`, `leaveGroup` 패턴 확장):
  1. `findByIdForUpdate`(락) → deleted 체크(`GROUP_NOT_MEMBER`).
  2. 활성 멤버 목록 조회 → owner(첫) `userId != 요청자` → `GROUP_OWNER_REQUIRED`.
  3. 전원 `markLeft(now)` + save.
  4. `group.markDeleted()` + save.
  5. `inviteLinkRepository.expirePendingByGroupId(groupId, now)`.
  6. 각 멤버: `listActiveGroupIdsByUserId(userId).isEmpty()`면 `botUserMappingService.unlink(userId)`.
- **Controller**: `@DeleteMapping("/{groupId}")`(기존 `.../members/me` 탈퇴와 경로 구분).

### §2.5 알림 `groupName` 추가
- `Notification`은 `groupId` 보유(`getGroupId`). `NotificationService`에 **`GroupRepository` 주입 추가**.
- `listRecent`: notifications의 `groupId` 집합 → 그룹명 batch(`findById` 반복, `loadPinsByIds` 선례 — MVP 규모 N+1 허용). `NotificationItemResult`에 `groupName` 추가.
- `getDetail`: `n.getGroupId()` → `groupRepository.findById().getName()`. `NotificationDetailResult`에 `groupName`.
- soft-delete 그룹도 그룹명만 노출(`findById`는 deletedAt 무관). 미존재 시 `null`.
- **DTO**: `NotificationItem`/`NotificationDetailResponse`에 `groupName`(String, nullable).

### §2.6 ErrorType 추가
- `GROUP_OWNER_REQUIRED`(403, "방장만 삭제할 수 있어요"). 기존 group 에러 패턴 따름.

### §2.7 백엔드 변경 파일
- `domain/group/Group.java`(rename) · `GroupMemberRepository.java`(listActiveMembersByGroupId) + infra 구현 · `GroupMemberService.java`(listMembers/renameGroup/deleteGroup + Info/Result records)
- `interfaces/api/group/GroupV1Controller.java`·`GroupV1Dto.java`·`GroupV1ApiSpec.java`(Swagger 인터페이스)
- `domain/notification/NotificationService.java`(GroupRepository 주입·groupName) · `interfaces/api/notification/NotificationV1Dto.java`(groupName)
- `support/error/ErrorType.java`(GROUP_OWNER_REQUIRED)
- 테스트(Group/Notification service)

---

## §3 iOS 설계

### §3.1 알림 그룹명 — NotificationAPI / NotificationInboxView
- `NotificationItem`/`NotificationDetail`에 `groupName: String?` 추가(백엔드 1:1).
- 알림 행: 작성자/시간 영역에 그룹명 텍스트/칩("○○"). 상세 헤더에 그룹명. `nil`이면 생략(방어, Should-10).

### §3.2 내정보 축소 — MyInfoView / MyInfoViewModel
- `MyInfoView`: `groupSection`·`shouldShowGroupSection` 분기·`leaveDialog` 제거 → 사용자(닉네임수정)+계정만.
- `MyInfoViewModel`: `activeGroup`·`shouldShowGroupSection`·`leaveGroup`·`groupAPI` 의존 제거. `load()`에서 `myActiveGroup()` 호출 제거(닉네임만).
- `MainTabView` init: `MyInfoViewModel(groupAPI:)` 인자 제거.

### §3.3 GroupAPI 확장
```swift
struct GroupMemberItem: Decodable, Identifiable, Equatable {
    let userId: Int
    let nickname: String
    let joinedAt: String?
    let isOwner: Bool
    var id: Int { userId }
}
// GroupAPIProtocol + GroupAPI 양쪽에 추가(stub 정합)
func listMembers(groupId: Int) async throws -> [GroupMemberItem]   // GET /groups/{id}/members
func updateGroupName(groupId: Int, name: String) async throws       // PATCH /groups/{id} {name}
func deleteGroup(groupId: Int) async throws                         // DELETE /groups/{id} (200/204 정규화, leaveGroup 패턴)
// leaveGroup(groupId:) 기존 재사용
```

### §3.4 GroupManageView + GroupManageViewModel (신규)
- **VM**(`@MainActor`): `@Published members/groupNameDraft/loadState/isBusy/errorMessage`. 의존 `groupAPI·currentUser·groupId·초기 groupName`.
  - `isOwner`: `members.first(where: { $0.userId == currentUser.id })?.isOwner ?? false`.
  - `load()`=listMembers / `rename(newName)`=updateGroupName→onRenamed / `delete()`=deleteGroup→onExit / `leave()`=leaveGroup→onExit. busy 가드 + errorMessage(MyInfoVM 패턴).
- **View**: 섹션1 그룹 이름(편집 TextField/시트 + 저장) · 섹션2 멤버 목록(닉네임 + "방장" 뱃지) · 섹션3 위험(그룹 탈퇴 confirmationDialog / 그룹 삭제 — `isOwner`만 노출, confirmationDialog). `MyInfoView` 카드/섹션 스타일 재사용.

### §3.5 MapView 통합
- `groupManagePlaceholder` → `GroupManageView(groupId: currentGroupId!, groupName:, groupAPI:, currentUser:, onRenamed:, onExit:)`.
- 이름 수정 → `groupContext.refresh()`(상단 그룹명 갱신).
- 삭제/탈퇴 → 시트 닫기 + `groupContext.exitGroup(id)`.
- MapView에 `currentUser` 주입 추가(없으면). `groupContext`는 이미 주입.

### §3.6 GroupContext 확장
```swift
/// 그룹 삭제/탈퇴 후: 레벨0 복귀 + lastGroupId 정리 + 목록 재조회.
func exitGroup(_ id: Int) async {
    if lastGroupId == id { lastGroupId = nil }
    currentGroupId = nil
    await refresh()
}
```
- 이름 수정 후 상단 그룹명: MapView 상단이 `groups`에서 `currentGroupId`로 name을 조회하면 `refresh()`로 갱신(coder 확인 — 미조회 시 별도 바인딩, §6 R5).

### §3.7 iOS 변경 파일
- 신규: `Features/Group/GroupManageView.swift`·`GroupManageViewModel.swift` · `WhereWeGoTests/GroupManageViewModelTests.swift`
- 수정: `Notification/NotificationAPI.swift`·`NotificationInboxView.swift` · `MyInfo/MyInfoView.swift`·`MyInfoViewModel.swift` · `Group/GroupAPI.swift`·`GroupContext.swift` · `Map/MapView.swift` · `App/MainTabView.swift` · 테스트(`MyInfoViewModelTests` 등)

---

## §4 구현 순서
1. 백엔드: ErrorType + `Group.rename` + Repository(listActiveMembersByGroupId).
2. 백엔드: Service(listMembers/renameGroup/deleteGroup) + 알림 groupName(NotificationService).
3. 백엔드: Controller/Dto/ApiSpec(그룹 3 + 알림 DTO).
4. 백엔드 테스트 → `compileJava compileTestJava` 게이트.
5. iOS: GroupAPI + GroupMemberItem.
6. iOS: GroupManageView/VM.
7. iOS: MapView 통합 + GroupContext.exitGroup.
8. iOS: 알림 그룹명 + 내정보 축소.
9. iOS 테스트.
> iOS = Windows 빌드 불가 → 시그니처/로직 직접 검토, Mac DoD-B는 리뷰어.

## §5 테스트
- **백엔드**: listMembers(방장 마킹·정렬) / renameGroup(검증·비멤버 차단) / deleteGroup(방장만·전원탈퇴·soft delete·비방장 403) / **방장 자동 승계**(방장 탈퇴 후 다음 멤버 owner) / 알림 groupName.
- **iOS**: GroupManageViewModel(load·isOwner·rename·delete·leave) / MyInfoViewModel(그룹 제거 회귀).

## §6 리스크 / 자체 비판
- **R1** `NotificationService`에 `GroupRepository` 주입 → 생성자 변경, 기존 테스트 DI 수정 필요. 그룹명 batch N+1(MVP 규모 허용, loadPinsByIds 선례).
- **R2** 그룹 삭제 = 전원 markLeft + 봇 unlink 루프. `leaveGroup`과 로직 중복 → 공통 헬퍼 추출은 과설계 경계, 범위 내 최소 중복 허용.
- **R3** 방장 판정 조회 시점 계산 → 동시 탈퇴+삭제 race를 `findByIdForUpdate` 락으로 직렬화(삭제·탈퇴 동일 락). 정합.
- **R4** PATCH 그룹명 권한 "모든 멤버"(비멤버만 차단) — PRD 확정 정책.
- **R5** iOS 그룹명 수정 후 상단 반영: MapView 상단이 `groups[currentGroupId].name` 사용 여부 coder 확인. 미사용 시 별도 바인딩.
- **R6** `GroupV1ApiSpec`(Swagger 인터페이스) 존재 → 신규 엔드포인트 시그니처 추가 필수(컴파일 게이트).

## §7 확인이 필요한 사항
추가 확인 사항 없음. 설계 완료.
(주요 결정: 방장=joined_at 최소 조회시점 계산·자동승계 / 그룹삭제=전원 markLeft+group soft delete / 알림 groupName=NotificationService에 GroupRepository 주입 batch / 내정보=groupAPI 의존 제거 / GroupContext.exitGroup 추가.)

## 탐색 추가 항목 (코드맵 누적)
- `backend/.../domain/group/` infra 구현체(GroupMemberRepository Jpa/Impl) → listActiveMembersByGroupId 구현 위치(coder 확인).
- `backend/.../support/error/ErrorType.java` → GROUP_OWNER_REQUIRED 추가.
- `backend/.../interfaces/api/group/GroupV1ApiSpec.java` → 신규 엔드포인트 시그니처.
- `ios/WhereWeGo/Core/Session/CurrentUser.swift:10` → `id: Int?`(방장 판정 키).
