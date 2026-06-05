# group 용어 사전

| 용어 | 설명 |
|------|------|
| Group | 핀/메모/태그를 공유하는 사용자 묶음. 컬럼: `id`, `name`, `created_at` |
| GroupMember | Group과 User의 N:M 매핑. 컬럼: `id`, `group_id`, `user_id`, `joined_at`, `left_at` (soft delete) |
| Couple | `group.size == 2`인 특수 케이스 (초기 MVP 기본 형태. GM-1 이후 정원 10까지 확장) |
| InviteLink | 그룹 초대 코드. UUID `token` + base56 8자 `slug` 단축 + TTL 7d. **IC-1 이후 재사용**(1코드 정원까지 N명 가입). `accepted_at` 제거(V019) |
| 재사용 초대 코드 (IC-1) | 초대 코드 1개로 TTL 내 + 정원(10) 빈자리가 있는 동안 복수 사용자가 가입. 가입 여부는 `group_members`로 판정(이력 무추적). 이전 1회용(`accepted_at` 소진) 대체 |
| Option A (정원 차단) | 정원 도달 시 코드를 **만료시키지 않고** `countActiveByGroupId >= 10` 검사로 가입만 차단. 만료시키면 by-slug가 NOT_FOUND(404)를 먼저 반환해 정원초과 구분이 불가능해지는 모순을 피하기 위한 결정(design-critic 발견) |
| slug | base56 8자(혼동문자 0/O/I/l/1/o 제외) 단축 코드. 공유 URL `/invite/{slug}`. partial unique(`slug IS NOT NULL AND deleted_at IS NULL`) |
| GROUP_ALREADY_MEMBER | 이미 활성 멤버가 같은 코드를 재수락할 때 반환(409). 사전가드로 멤버 수 불변 보장. 탈퇴 후 재가입(`GROUP_REJOIN_FORBIDDEN`)과 구분 |
| GROUP_CAPACITY_EXCEEDED | 정원(10) 초과 시 반환(409). accept(가입 차단) + by-slug 미리보기(정원초과 구분 응답) 양쪽에서 사용 |
| expirePendingByGroupId | 그룹의 미만료 활성 코드를 즉시 만료(`expires_at = now`). **재발급(BR-3)·탈퇴(BR-5)에서만** 호출. IC-1 Option A로 정원 도달 시에는 호출하지 않음 |
| 1인 N그룹 (GM-1 이후) | 1인이 여러 그룹에 동시 활성 가능. 이전 MVP의 "1인 1활성 제약"은 V018에서 해제. DB 스키마는 N:M, 그룹당 정원 10 |
| 연결 해제 (탈퇴) | GroupMember를 soft delete(`left_at` 갱신). 핀은 그룹에 잔류. 탈퇴 시 활성 초대 코드도 만료(BR-5) |
| 활성 GroupMember | `left_at IS NULL`인 GroupMember 행 |
