# 설계 초안: GM-1 그룹 다중지원 백엔드 (architect, 확정 전 초안)

## 설계 규모
**대형** — 1개 마이그레이션 + 4개 서비스/포트/컨트롤러 수정 + 신규 API + DTO + 5개 챗봇 주석 + 4개 테스트 재설계. 동시성 의미 변화·예외 변환 재설계가 핵심 트레이드오프.

## 변경 범위

### 신규 생성 (3)
| 파일 | 역할 |
|------|------|
| `db/migration/V018__drop_active_user_unique_for_multi_group.sql` | `uq_group_members_active_user` DROP INDEX (additive-only) |
| `domain/group/GroupSummary.java` | 내 그룹 목록 항목 record (`groupId·name·createdAt·memberCount`) |
| (테스트) GroupV1ControllerIntegrationTest GET /groups 케이스 | — |

### 수정 (코드 13)
| 파일 | 변경 |
|------|------|
| `domain/group/GroupMemberService.java` | `MAX_GROUP_MEMBERS` 2→10; createGroup·acceptInviteLink 제약 제거; `listMyGroups` 신규 |
| `domain/group/GroupMemberRepository.java` | `listActiveGroupSummariesByUserId` 포트 추가 |
| `infrastructure/group/GroupMemberRepositoryImpl.java` | 신규 포트 위임 |
| `infrastructure/group/GroupMemberJpaRepository.java` | 다중 활성 그룹 목록 쿼리(joined_at 순) 추가 |
| `interfaces/api/group/GroupV1Controller.java` | `GET /api/v1/groups` 핸들러 |
| `interfaces/api/group/GroupV1ApiSpec.java` | listMyGroups Operation + Swagger 갱신 |
| `interfaces/api/group/GroupV1Dto.java` | `GroupSummaryResponse` record |
| `domain/user/UserDeletionService.java` | 단건→전체 활성 그룹 순회 탈퇴 |
| `domain/user/UserOnboardingService.java` | 단수 전제 주석/javadoc 보강(동작 무변경) |
| `domain/chatbot/ChatbotWebhookService.java:152` 외 4 | TODO 주석만 |

### 선택적 정리 (확인 필요)
- `interfaces/api/ApiControllerAdvice.java:158` active_user→GROUP_ALREADY_ACTIVE 분기 = dead path (Q2)
- `existsActiveByUserId` 포트/JPA/Impl 제거 (Q3)

### 수정 테스트 (4)
- `GroupMemberServiceTest`(제약 반전·정원10·listMyGroups), `GroupMemberServiceIT`(동시성 의미변화·AC-11·QE-2), `UserOnboardingServiceTest`(stub 정리), `GroupV1ControllerIntegrationTest`(GET /groups·다중 me)

## 적용 컨벤션 (구현자 참조)
- 헥사고날 3레이어: domain(포트+@Service) / infrastructure(*RepositoryImpl→*JpaRepository 위임) / interfaces(Controller implements ApiSpec + Dto).
- 도메인 결과 record → 컨트롤러에서 `*Response.from()` 변환.
- 에러: `ErrorType` enum + `CoreException`. DB 위반 `DataIntegrityViolationException` catch + 메시지 매칭. 전역 fallback `ApiControllerAdvice`.
- JPQL `@Query` + `@Param`. 비관락 `findByIdForUpdate`. 활성 = `gm.leftAt IS NULL`.
- 마이그레이션 Flyway `V0NN__snake_case.sql`, 박스 주석(정책·라이브안전·롤백·컨벤션).
- **시간 타입**: `Group.createdAt`=`ZonedDateTime`(BaseEntity), `GroupMember.joinedAt`=`Instant`(자체 컬럼). 목록 createdAt은 ZonedDateTime 통일.
- JPQL 엔티티명: `Group.java:17 @Entity(name="GroupAggregate")` → JPQL은 `GroupAggregate` 사용. GroupMember는 `GroupMember`.

## 상세 설계

### 과제1 — V018 마이그레이션
현재 V001:76 `CREATE UNIQUE INDEX uq_group_members_active_user ON group_members(user_id) WHERE left_at IS NULL` (CONSTRAINT 아닌 INDEX) → **`DROP INDEX IF EXISTS`** 정확. `uq_group_members_pair`(CONSTRAINT) 미변경.
```sql
-- V018__drop_active_user_unique_for_multi_group.sql
-- GM-1: 1인 다중 활성 그룹 지원. uq_group_members_active_user 해제.
-- 제약 형태: V001:76 CREATE UNIQUE INDEX (CONSTRAINT 아님) → DROP INDEX 정확.
-- 라이브: DROP INDEX 짧은 ACCESS EXCLUSIVE 락, 데이터 무변형 → additive.
--         대규모면 DROP INDEX CONCURRENTLY(트랜잭션 밖) 검토.
-- 롤백(R-4): 다중 활성 행 생성 후 인덱스 재생성은 UNIQUE 위반 실패.
--   절차: (1) 여분 멤버십 left_at 마킹 → (2) CREATE UNIQUE INDEX 재생성. 적용 전 스냅샷.
DROP INDEX IF EXISTS uq_group_members_active_user;
```

### 과제2 — GroupMemberService 제약 해제 + 예외 재설계
- **MAX_GROUP_MEMBERS 2→10**. 정원검사(:131,:145) 유지.
- **createGroup(:48~64)**: existsActiveByUserId 사전검사(:53~55) 삭제. try-catch(:57~62) — 새 그룹이라 active_user/pair 위반 **구조적 발생 불가** → catch 완전 제거, save 직접. 예상못한 위반은 전역 advice가 INTERNAL_ERROR. (Q1)
- **acceptInviteLink(:109~149)**: existsActiveByUserId(:128~130) 삭제. 정원검사(:131) 유지(MAX=10). try-catch 재설계 — 제약 제거 후 남는 위반은 `uq_group_members_pair`(동일 그룹 재가입)뿐 → **단일 `GROUP_REJOIN_FORBIDDEN`** (문자열 매칭 삭제). (Q1)
- TOCTOU: 다른 그룹 동시가입 둘다 성공; 동일 그룹 이중가입 한쪽 pair위반→REJOIN; 정원경쟁 findByIdForUpdate 락.

### 과제3 — listActiveByUserId 포트 + GET /groups
- `GroupSummary(Long groupId, String name, ZonedDateTime createdAt, long memberCount)` record (Q4).
- 포트 `listActiveGroupSummariesByUserId(Long): List<GroupSummary>`.
- JPQL(엔티티명 GroupAggregate, memberCount 상관 서브쿼리, ORDER BY gm.joinedAt ASC, gm.id ASC):
```java
@Query("SELECT new com.wherewego.domain.group.GroupSummary(g.id, g.name, g.createdAt, "
 + "(SELECT COUNT(m2) FROM GroupMember m2 WHERE m2.groupId = g.id AND m2.leftAt IS NULL)) "
 + "FROM GroupMember gm JOIN GroupAggregate g ON g.id = gm.groupId "
 + "WHERE gm.userId = :userId AND gm.leftAt IS NULL AND g.deletedAt IS NULL "
 + "ORDER BY gm.joinedAt ASC, gm.id ASC")
```
- Service `listMyGroups(userId)` @Transactional(readOnly). Dto `GroupSummaryResponse.from()`. Controller `@GetMapping`(=`GET /api/v1/groups`, /me와 충돌X) 0개→[]. ApiSpec Operation 추가.

### 과제4 — groups/me joined_at 최신 1개 유지
`findMyActiveGroup`(:218)→`findLatestActiveGroupIdByUserId`→Impl `findActiveGroupIdsByUserId(PageRequest.of(0,1))` (JPA:14 **ORDER BY gm.id DESC**). PRD는 "joined_at 최신". 권장: 쿼리 정렬 `joinedAt DESC, id DESC` 보정(Q5). 응답 ActiveGroupResponse 불변, 0개→null.

### 과제5 — UserDeletionService 전체 순회 탈퇴
단건(:71~97) → 활성 그룹 ID 목록 순회. 각 groupId: wasLastMember 계산 → try{ leaveGroup; if(last) chatRoom softDelete } catch(GROUP_NOT_MEMBER){ log+continue }. botUserMapping unlink 멱등(그룹마다 호출돼도 무해), 0개일때 백업 unlink 유지. 단일 TX. 그룹ID 조회는 Q6.

### 과제6 — UserOnboardingService
현재 구현이 이미 PRD와 동치(findLatestActiveGroupId present⟺hasActiveGroup, countActiveByGroupId(최신)=memberCount). **동작 무변경**, 주석/javadoc만 다중 의미로 보강. 과제4 정렬 보정 시 "최신 활성 그룹"이 joined_at 기준으로 일관.

### 과제7 — 챗봇 5곳 TODO
`findLatestActiveGroupIdByUserId` 위에 `// TODO(GM-2): 그룹 선택 이관 예정. GM-1 전환기=최신 활성 1개 저장(단수 유지, BR-5).` 코드 무변경. 대상: ChatbotWebhookService:152, PlaceSelectionHandler:64, ReelMemoWaitingHandler:106, InstagramLinkHandler:90, ReelSelectionAutoSaveScheduler:119.

### 과제8 — 테스트 재설계
- **GroupMemberServiceTest**: :148/:305 제약 케이스 반전(다중 허용 성공), :161 DataIntegrity 케이스 삭제(createGroup catch 제거), :338 정원 2→10, 신규 pairConflict→REJOIN·listMyGroups.
- **GroupMemberServiceIT**: :125 반전(secondGroup 성공), :401 동시성 **의미변화**(1건성공→5건성공, DisplayName 갱신), :434 허용에러집합서 GROUP_ALREADY_ACTIVE 제거, 신규 AC-11(정원10+11번째 거부)·QE-2(타그룹 활성중 수락)·QE-3(계정삭제 N그룹). runConcurrently 헬퍼 재사용·단언 교체.
- **UserOnboardingServiceTest**: existsActiveByUserId 미사용 stub 제거.
- **GroupV1ControllerIntegrationTest**: 신규 AC-5(목록 배열)·AC-6([])·AC-8(다중 me 최신)·인증401. 기존 me 케이스 유지(BR-4 회귀가드).

**동시성 트레이드오프**: createGroup_concurrent_onlyOneSucceeds는 active_user 방어 검증이었음 → GM-1이 의도적 허용 → 단언 반전(==1→==5)은 **사양 변경 반영**(회귀 아님). 방어 가치는 pair(동일그룹 이중가입 차단)로 이전.

## 구현 순서
1. V018 마이그레이션 (의존 없음)
2. GroupSummary record (의존 없음)
3. Repository 포트 listActiveGroupSummariesByUserId (←2)
4. JpaRepository 목록 쿼리 + 정렬 보정 (←2)
5. RepositoryImpl 위임 (←3,4)
6. Service: MAX 10, 제약 제거+예외 재설계, listMyGroups (←3)
7. GroupV1Dto.GroupSummaryResponse (←2)
8. ApiSpec listMyGroups (←7)
9. Controller GET /groups (←6,7,8)
10. UserDeletionService 순회 탈퇴 (←3 또는 전용 List<Long> 포트, 6)
11. UserOnboardingService 주석 (←4)
12. 챗봇 5곳 TODO (의존 없음)
13. GroupMemberServiceTest (←6)
14. GroupMemberServiceIT (←1,6,9)
15. UserOnboardingServiceTest (←11)
16. GroupV1ControllerIntegrationTest (←9)
17. (QE-3) UserDeletion 다중 IT (←10)
1·2·12 병렬 가능. 13~17 각 구현 후 병렬.

## 탐색 추가 항목 (코드맵 보강)
- `modules/jpa/.../domain/BaseEntity.java` → createdAt/updatedAt/deletedAt=ZonedDateTime. (Group.createdAt 근원, GroupMember.joinedAt은 Instant — 타입 불일치 주의)
- `GroupMemberJpaRepository.java:14` findActiveGroupIdsByUserId → ORDER BY gm.id DESC (joined_at 아님). me/onboarding/UserDeletion/챗봇5 공유.
- `GroupMemberRepositoryImpl.java:20` findLatestActiveGroupIdByUserId → PageRequest.of(0,1).
- `Group.java:17` @Entity(name="GroupAggregate") → JPQL 엔티티명.
- `ApiControllerAdvice.java:146~168` handleDataIntegrityViolation → 전역 fallback, active_user(:158)/pair(:161) 분기.
- `ErrorType.java:45` GROUP_ALREADY_ACTIVE(dead 예정), :47 CAPACITY_EXCEEDED, :48 REJOIN_FORBIDDEN.
- `OnboardingStatus.java` + `me/MeV1Dto.java` → 온보딩 응답 필드.
- `GroupMemberServiceIT.java:355` runConcurrently 헬퍼.

## 확인이 필요한 사항 (6건)
1. DataIntegrityViolation catch 처리: (a)createGroup catch 제거+acceptInviteLink 단일 REJOIN_FORBIDDEN [권장] / (b)catch 유지+else INTERNAL_ERROR / (c)기타
2. ApiControllerAdvice active_user 분기(:158): (a)그대로 둠+주석 [권장] / (b)제거 / (c)기타
3. existsActiveByUserId 포트 제거: (a)제거+테스트 stub 정리 [권장] / (b)유지 / (c)기타
4. 목록 DTO: (a)신규 GroupSummary [권장] / (b)ActiveGroupInfo 재사용 / (c)기타
5. findActiveGroupIdsByUserId 정렬 id DESC→joinedAt DESC 보정: (a)보정 [권장] / (b)id DESC 유지 / (c)기타
6. UserDeletion 그룹ID 조회: (a)가벼운 listActiveGroupIdsByUserId List<Long> 신규 [권장] / (b)Summaries 재사용 / (c)기타
