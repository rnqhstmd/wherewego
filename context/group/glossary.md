# group 용어 사전

| 용어 | 설명 |
|------|------|
| Group | 핀/메모/태그를 공유하는 사용자 묶음. 컬럼: `id`, `name`, `created_at` |
| GroupMember | Group과 User의 N:M 매핑. 컬럼: `id`, `group_id`, `user_id`, `joined_at`, `left_at` (soft delete) |
| Couple | `group.size == 2`인 특수 케이스 (초기 MVP 기본 형태. GM-1 이후 정원 10까지 확장) |
| InviteLink | 그룹 초대 링크. UUID + TTL 24h |
| 1인 N그룹 (GM-1 이후) | 1인이 여러 그룹에 동시 활성 가능. 이전 MVP의 "1인 1활성 제약"은 V018에서 해제. DB 스키마는 N:M, 그룹당 정원 10 |
| 연결 해제 (탈퇴) | GroupMember를 soft delete(`left_at` 갱신). 핀은 그룹에 잔류 |
| 활성 GroupMember | `left_at IS NULL`인 GroupMember 행 |
