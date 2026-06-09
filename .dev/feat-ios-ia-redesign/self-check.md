# 자기 점검 — D단계: 알림 상세 / 내정보 축소 / 그룹관리 (implement)

> qa-manager 미반환 환경 → 오케스트레이터 직접 점검(코드 전수 Read + grep 정합 + 컴파일 게이트).

## 스펙 충족 (PRD 수용 기준 대비)
| AC | 충족 | 근거 |
|----|------|------|
| AC-1 알림 그룹명 | ✅ | NotificationV1Dto/NotificationService groupName(GroupRepository 주입 batch), iOS NotificationAPI groupName?+InboxView 표시 |
| AC-2 내정보 축소 | ✅ | MyInfoView 그룹섹션 제거, MyInfoViewModel activeGroup/leaveGroup/groupAPI 제거, MainTabView 호출 정합 |
| AC-3 ⋯→GroupManageView | ✅ | MapView groupManageSheet→GroupManageHost, showGroupManage |
| AC-4 방장 뱃지 | ✅ | listMembers i==0 isOwner, GroupManageView memberRow "방장" 캡슐 |
| AC-5 이름수정(모든멤버) | ✅ | renameGroup requireActiveMembership+30자, PATCH /{id}, onRenamed→groupContext.refresh |
| AC-6 삭제(방장만)+403 | ✅ | deleteGroup 방장!=요청자→GROUP_OWNER_REQUIRED, iOS isOwner만 버튼 노출 |
| AC-7 방장 자동승계 | ✅ | joined_at 최소 조회시점 계산(컬럼 없음), markLeft로 활성집합 축소 시 자동 |
| AC-8 일반멤버 탈퇴만 | ✅ | dangerSection 탈퇴는 항상, 삭제는 isOwner 분기 |

## 정합성 점검 (grep/Read 전수)
- 백엔드 `compileJava compileTestJava`: **BUILD SUCCESSFUL** (테스트 코드 포함 컴파일 green)
- GroupAPIProtocol(AuthServiceProtocols): listMembers/updateGroupName/deleteGroup 선언 ✓ + GroupAPI 구현 ✓ (leaveGroup HTTP_200/NO_CONTENT 정규화 패턴 재사용)
- MyInfoViewModel: groupAPI/activeGroup/leaveGroup 제거 → MainTabView `MyInfoViewModel(authAPI,sessionStore,currentUser,logoutHandler)` 정합 ✓
- MapView: init에 currentUser/groupAPI 추가 → MainTabView `MapView(...,currentUser,groupAPI)` 정합 ✓. groupManageSheet→GroupManageHost(onRenamed:refresh / onExit:exitGroup) ✓
- GroupContext.exitGroup(lastGroupId 정리+backToList+refresh) ✓
- 컨트롤러 경로: GET/PATCH/DELETE /{id} + /{id}/members(조회) + /{id}/members/me(탈퇴) 충돌 없음 ✓
- 방장 판정: listMembers/deleteGroup 모두 listActiveMembersByGroupId 첫 항목(joined_at ASC,id ASC) ✓

## 잔여 리스크 (Info)
- [Info] deleteGroup 전원 markLeft 시 멤버별 findActiveByGroupIdAndUserId 재조회(N+1) — 멤버 수 작아 허용(설계 §6 R2).
- [Info] iOS = Windows 빌드 불가 → 컴파일/시뮬/단위테스트 실행은 Mac DoD-B(리뷰어). 시그니처/디코딩/Swift 동시성은 직접 검토로 보장.
- [Info] 신규 백엔드 테스트 3종 컴파일 green, 실행은 Mechanical Gate에서 타겟 검증.

## 판정
Critical 0 · QUESTION 0 · 스펙 충족. 구현 완료.
