## 코드 맵: D단계 — 알림 상세 / 내정보 축소 / 그룹관리 ⋯ (IA 재설계 GM-2)

### 핵심 파일 (iOS)
- `ios/WhereWeGo/Features/Notification/NotificationInboxView.swift` → 알림함 뷰(목록/상세 분기 렌더)
- `ios/WhereWeGo/Features/Notification/NotificationInboxViewModel.swift` → list/detail/readAll. NotificationItem 표시·시간포맷
- `ios/WhereWeGo/Features/Notification/NotificationAPI.swift` → DTO. **NotificationItem/Detail에 groupId 없음**. registeredByNickname·firstPlaceName 있음
- `ios/WhereWeGo/Features/MyInfo/MyInfoView.swift` → 내정보 3섹션(사용자/활성그룹/계정). **"활성 그룹" 섹션(133~155행) 제거 대상**
- `ios/WhereWeGo/Features/MyInfo/MyInfoViewModel.swift` → activeGroup·leaveGroup·shouldShowGroupSection
- `ios/WhereWeGo/Features/Map/MapView.swift:34,231,445,499` → ⋯ 버튼 + showGroupManage + groupManagePlaceholder(**D단계서 GroupManageView로 교체**)
- `ios/WhereWeGo/Features/Group/GroupAPI.swift` → leaveGroup ✅ / 이름수정·그룹원조회·삭제 ❌
- `ios/WhereWeGo/Features/Group/GroupContext.swift` → currentGroupId/lastGroupId/groups, enterGroup/switchTo
- `ios/WhereWeGo/App/MainTabView.swift` → 4탭(알림=NotificationInboxView, 내정보=MyInfoView)

### 참조 파일 (백엔드 — D 풀스택 시)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/notification/NotificationV1Dto.java` → 알림 DTO(**groupName 미노출**, DB group_id는 존재)
- `backend/.../domain/group/GroupMemberService.java` → 그룹원 관리(이름수정/목록조회 API 없음)
- `backend/.../interfaces/api/user/UserV1Dto.java` → GET/PUT /users/me(닉네임 수정 ✅)
- `backend/.../domain/group/` → Group 엔티티(이름수정/삭제 미구현, owner/role 개념 없음)

### 설정
- `ios/WhereWeGo/project.yml` → XcodeGen(신규 .swift 자동 포함, pbxproj 수동 불요)

### 백엔드 현황 요약 (선확인 결과)
| 기능 | 백엔드 | 비고 |
|------|--------|------|
| 알림: 작성자 닉네임 | ✅ | registeredByNickname |
| 알림: 핀 | ✅ | firstPlaceName + detail.pins |
| 알림: 그룹명 | ❌ | DB group_id 존재, DTO 추가 필요(소형) |
| 그룹 탈퇴 | ✅ | DELETE /groups/{id}/members/me |
| 내정보 수정 | ✅ | GET/PUT /users/me |
| 그룹 이름수정 | ❌ | API 신규 |
| 그룹원 목록조회 | ❌ | API 신규 |
| 그룹 삭제 | ❌ | owner/방장 개념 없음 → 정책 신규 필요 |
