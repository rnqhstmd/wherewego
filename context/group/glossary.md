# group 용어 사전

| 용어 | 설명 |
|------|------|
| Group | 핀/메모/태그를 공유하는 사용자 묶음. 컬럼: `id`, `name`, `created_at` |
| GroupMember | Group과 User의 N:M 매핑. 컬럼: `id`, `group_id`, `user_id`, `joined_at`, `left_at` (soft delete) |
| Couple | `group.size == 2`인 특수 케이스 (초기 MVP 기본 형태. GM-1에서 정원 10으로 확장 → GP-1에서 8로 축소) |
| InviteLink | 그룹 초대 코드. UUID `token` + base56 8자 `slug` 단축 + TTL 7d. **IC-1 이후 재사용**(1코드 정원까지 N명 가입). `accepted_at` 제거(V019) |
| 재사용 초대 코드 (IC-1) | 초대 코드 1개로 TTL 내 + 정원(8, GP-1 축소) 빈자리가 있는 동안 복수 사용자가 가입. 가입 여부는 `group_members`로 판정(이력 무추적). 이전 1회용(`accepted_at` 소진) 대체 |
| Option A (정원 차단) | 정원 도달 시 코드를 **만료시키지 않고** `countActiveByGroupId >= MAX(8)` 검사로 가입만 차단. 만료시키면 by-slug가 NOT_FOUND(404)를 먼저 반환해 정원초과 구분이 불가능해지는 모순을 피하기 위한 결정(design-critic 발견) |
| slug | base56 8자(혼동문자 0/O/I/l/1/o 제외) 단축 코드. 공유 URL `/invite/{slug}`. partial unique(`slug IS NOT NULL AND deleted_at IS NULL`) |
| GROUP_ALREADY_MEMBER | 이미 활성 멤버가 같은 코드를 재수락할 때 반환(409). 사전가드로 멤버 수 불변 보장. 탈퇴 후 재가입(`GROUP_REJOIN_FORBIDDEN`)과 구분 |
| GROUP_CAPACITY_EXCEEDED | 정원(8, GP-1 축소) 초과 시 반환(409). accept(가입 차단) + by-slug 미리보기(정원초과 구분 응답) 양쪽에서 사용 |
| expirePendingByGroupId | 그룹의 미만료 활성 코드를 즉시 만료(`expires_at = now`). **재발급(BR-3)·탈퇴(BR-5)에서만** 호출. IC-1 Option A로 정원 도달 시에는 호출하지 않음 |
| 1인 N그룹 (GM-1 이후) | 1인이 여러 그룹에 동시 활성 가능. 이전 MVP의 "1인 1활성 제약"은 V018에서 해제. DB 스키마는 N:M, 그룹당 정원 8(GP-1 축소) |
| 연결 해제 (탈퇴) | GroupMember를 soft delete(`left_at` 갱신). 핀은 그룹에 잔류. 탈퇴 시 활성 초대 코드도 만료(BR-5) |
| 활성 GroupMember | `left_at IS NULL`인 GroupMember 행 |
| 방장 (owner) | 그룹 활성 멤버 중 가장 먼저 가입한 사람(`joined_at` 최소, 동률 시 `id` 최소). 별도 owner 컬럼·승계 트랜잭션 없이 **조회 시점 계산** → 방장 탈퇴 시 다음 최선임이 자동 승계. 그룹 삭제 권한 보유 (GM-2) |
| 그룹원 목록 조회 (GM-2) | `GET /api/v1/groups/{id}/members`. 활성 멤버만 접근 가능. 가입 순(joined_at ASC, id ASC) + 첫 항목 `isOwner=true` 마킹 |
| 그룹명 수정 (GM-2) | `PATCH /api/v1/groups/{id}` `{name}`. 활성 멤버 **누구나**(방장 제한 없음). trim 후 1~30자 검증(createGroup 동일). `findByIdForUpdate` 락으로 삭제/탈퇴와 직렬화 |
| 그룹 삭제 (GM-2) | `DELETE /api/v1/groups/{id}`. **방장만**(비방장 `GROUP_OWNER_REQUIRED` 403). 전원 `markLeft` + group soft delete + 미수락 토큰 만료 + 잔여 활성 0인 멤버 봇 unlink (leaveGroup 패턴 확장) |
| GROUP_OWNER_REQUIRED | 비방장이 그룹 삭제 시도 시 반환(403). 방장=활성멤버 joined_at 최소 (GM-2) |
| 그룹 대표 이미지 (GP-1) | `groups.image_key`/`image_thumb_key`(V022, S3 키). `POST/DELETE /groups/{id}/image` — **활성 멤버 누구나** 변경/제거(그룹명 수정과 동일 권한·락). 미지정 시 클라가 멤버 프사 콜라주 렌더 |
| 멤버 프사 콜라주 (GP-1) | 그룹 대표 이미지 미지정 시 기본 표현. 가입순 최대 4명의 프사를 카톡식 배치(1=단일/2=대각/3=삼각/4=2×2)로 **클라이언트 합성**(iOS `GroupAvatarView`). 프사 없는 멤버는 이니셜 셀 |
| 정원 축소 (GP-1 FR-8) | `MAX_GROUP_MEMBERS` 10→8. `>=` 검사라 기존 9~10명 그룹은 데이터 보정 없이 신규 가입만 차단(강제 퇴장 없음) |
| GroupMemberPreview (GP-1) | 그룹 목록 응답 동봉용 활성 멤버 프리뷰(userId, nickname, 유효 프사 URL). `listActiveMembersByGroupIds` IN 쿼리 1회 + 가입순(joined_at, id ASC). iOS 목록 아바타 일렬·콜라주·채팅탭 썸네일의 단일 소스 |
