# 설계서: GM-1 그룹 다중지원 백엔드 (확정)

## 설계 규모
**대형** — 1 마이그레이션 + 서비스/포트/컨트롤러/DTO + 신규 API + 5 챗봇 주석 + 4~5 테스트 재설계. 동시성 의미 변화·예외 변환 비대칭이 핵심.

## 변경 범위

### 신규 생성 (2)
| 파일 | 역할 |
|------|------|
| `db/migration/V018__drop_active_user_unique_for_multi_group.sql` | `uq_group_members_active_user` DROP INDEX (additive-only) |
| `domain/group/GroupSummary.java` | 내 그룹 목록 항목 record (`groupId·name·createdAt·memberCount`) |

### 수정 코드 (15)
| 파일 | 변경 |
|------|------|
| `domain/group/GroupMemberService.java` | MAX 2→10; createGroup catch 완전 제거; acceptInviteLink pair 매칭 유지+else rethrow; existsActiveByUserId 호출 제거; `listMyGroups` 신규 |
| `domain/group/GroupMemberRepository.java` | `listActiveGroupSummariesByUserId` + `listActiveGroupIdsByUserId` 포트 추가; `existsActiveByUserId` **제거** |
| `infrastructure/group/GroupMemberJpaRepository.java` | 목록 쿼리(joinedAt ASC) + UserDeletion용 쿼리(group_id ASC) 추가; `existsActiveByUserId` 제거 |
| `infrastructure/group/GroupMemberRepositoryImpl.java` | 신규 2포트 위임; existsActiveByUserId 제거 |
| `interfaces/api/group/GroupV1Controller.java` | `GET /api/v1/groups` 핸들러 |
| `interfaces/api/group/GroupV1ApiSpec.java` | listMyGroups Operation |
| `interfaces/api/group/GroupV1Dto.java` | `GroupSummaryResponse` record |
| `domain/user/UserDeletionService.java` | 단건→전체 활성그룹 순회 탈퇴(group_id 순서, 데드락 방지) |
| `domain/user/UserOnboardingService.java` | 주석/javadoc 보강(동작 무변경) |
| `domain/user/OnboardingStatus.java` | javadoc 갱신 |
| `interfaces/api/ApiControllerAdvice.java` | :158 active_user 분기 dead path 주석 1줄 |
| `domain/chatbot/ChatbotWebhookService.java:152` 외 4 (PlaceSelectionHandler:64, ReelMemoWaitingHandler:106, InstagramLinkHandler:90, ReelSelectionAutoSaveScheduler:119) | TODO 주석만 |

### 수정 테스트 (4~5)
`GroupMemberServiceTest`(제약 반전·정원10·rethrow·listMyGroups), `GroupMemberServiceIT`(동시성 의미변화·AC-11 서로다른10명·QE-2), `UserOnboardingServiceTest`(stub 제거), `GroupV1ControllerIntegrationTest`(GET /groups·다중 me), (QE-3) UserDeletion 다중 IT.

## 적용 컨벤션 (구현자 참조)
- 헥사고날 3레이어: domain(포트+@Service) / infrastructure(*RepositoryImpl→*JpaRepository 위임) / interfaces(Controller implements ApiSpec + Dto). 도메인 결과 record → 컨트롤러 `*Response.from()`.
- 에러: `ErrorType` enum + `CoreException`. DB 위반 `DataIntegrityViolationException` + 메시지 매칭. 전역 fallback `ApiControllerAdvice.handleDataIntegrityViolation`(:146).
- JPQL `@Query`+`@Param`. 비관락 `findByIdForUpdate`(@Lock PESSIMISTIC_WRITE). 활성=`gm.leftAt IS NULL`.
- 마이그레이션 Flyway `V0NN__snake_case.sql`, 박스 주석(정책·라이브안전·롤백·컨벤션).
- **시간 타입**: `Group.createdAt`=`ZonedDateTime`(BaseEntity), `GroupMember.joinedAt`=`Instant`(자체 컬럼). 목록 createdAt은 ZonedDateTime.
- **JPQL 엔티티명**: `Group.java:17 @Entity(name="GroupAggregate")` → JPQL은 `GroupAggregate`. GroupMember는 `GroupMember`.
- **`id`는 BIGSERIAL 단조** → id DESC = joined_at 최신과 동치(동시 INSERT 외 불일치 없음).
- **group_members 제약(V001:66-72)**: FK `group_id→groups` + FK `user_id→users` + `uq_group_members_pair UNIQUE(group_id,user_id)` + (제거 대상) `uq_group_members_active_user` partial INDEX.

## 상세 설계

### 과제1 — V018 마이그레이션 (FR-1, BR-3, R-4)
현재 V001:76 `CREATE UNIQUE INDEX uq_group_members_active_user ON group_members(user_id) WHERE left_at IS NULL` (CONSTRAINT 아닌 INDEX) → **`DROP INDEX IF EXISTS`** 정확. `uq_group_members_pair`(CONSTRAINT) 미변경.
```sql
-- ============================================================
-- V018__drop_active_user_unique_for_multi_group.sql
-- GM-1: 1인 다중 활성 그룹 지원. uq_group_members_active_user 해제.
-- 제약 형태: V001:76 CREATE UNIQUE INDEX (CONSTRAINT 아님) → DROP INDEX 정확.
-- 라이브: DROP INDEX 짧은 ACCESS EXCLUSIVE 락, 데이터 무변형 → additive.
--         대규모면 DROP INDEX CONCURRENTLY(트랜잭션 밖) 검토.
-- 롤백(R-4): 다중 활성 행 생성 후 인덱스 재생성은 UNIQUE 위반 실패.
--   절차: (1) 여분 멤버십 left_at 마킹 → (2) CREATE UNIQUE INDEX 재생성. 적용 전 스냅샷.
-- 유지: uq_group_members_pair(동일 그룹 재가입 차단) + FK 2개는 건드리지 않음.
-- ============================================================
DROP INDEX IF EXISTS uq_group_members_active_user;
```

### 과제2 — GroupMemberService 제약 해제 + 예외 비대칭 재설계 (FR-2, FR-3, BR-2)
**2-1. MAX_GROUP_MEMBERS 2→10** (:22). 정원검사(:131,:145) 유지.

**2-2. createGroup (:48~64) — catch 완전 제거** (새 그룹이라 pair/active_user/FK 위반 구조적 불가):
```java
@Transactional
public GroupCreatedResult createGroup(Long userId, String rawName) {
    String name = rawName == null ? "" : rawName.trim();
    if (name.isEmpty() || name.length() > 30) {
        throw new CoreException(ErrorType.GROUP_NAME_INVALID);
    }
    // GM-1: 1인 1활성 그룹 제약 해제 — existsActiveByUserId 사전검사 제거.
    Group saved = groupRepository.save(Group.create(name));
    // 새 그룹이라 group_members 제약 위반(pair/active_user/FK)이 구조적으로 발생 불가 → try-catch 불요.
    // 예외적 위반은 전역 ApiControllerAdvice 가 INTERNAL_ERROR 로 처리.
    groupMemberRepository.save(GroupMember.createActive(saved.getId(), userId, Instant.now()));
    return new GroupCreatedResult(saved.getId(), saved.getName(), saved.getCreatedAt());
}
```

**2-3. acceptInviteLink (:109~149) — catch 비대칭 유지 (createGroup과 다름)**:
- (a) existsActiveByUserId 사전검사(:128~130) 삭제. (b) 정원검사(:131) 유지(MAX=10). (c) try-catch:
```java
link.markAccepted(now);
try {
    groupMemberRepository.save(GroupMember.createActive(group.getId(), userId, now));
} catch (DataIntegrityViolationException e) {
    // GM-1: acceptInviteLink 는 기존 그룹에 INSERT 하므로 createGroup 과 달리 catch 필요.
    //   - uq_group_members_pair(동일 그룹 재가입) → GROUP_REJOIN_FORBIDDEN (BR-1).
    //   - 그 외(FK group_id→groups / user_id→users 위반: 동시 그룹 soft-delete 중 INSERT 등)는
    //     rethrow → 전역 ApiControllerAdvice 가 INTERNAL_ERROR 처리(REJOIN 오분류 방지).
    //   uq_group_members_active_user 는 DROP 됐으므로 이 경로에서 발생하지 않음.
    String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
    if (msg.contains("uq_group_members_pair")) {
        throw new CoreException(ErrorType.GROUP_REJOIN_FORBIDDEN);
    }
    throw e;
}
```
- **비대칭 근거(critic)**: group_members에 FK 2개+pair가 잔존. 무조건 REJOIN 변환은 FK 위반을 409로 오분류 → pair 매칭 + else rethrow.
- TOCTOU: 다른 그룹 동시가입 둘다 성공; 동일 그룹 이중가입 pair→REJOIN; 정원경쟁 findByIdForUpdate 락.

**2-4. existsActiveByUserId 제거**: 호출 제거 후 포트/JPA/Impl에서 메서드 삭제(Q3).

### 과제3 — listActiveByUserId 포트 + GET /groups (FR-4, FR-5)
- `GroupSummary(Long groupId, String name, ZonedDateTime createdAt, long memberCount)` record (Q4 신규).
- 포트 `listActiveGroupSummariesByUserId(Long): List<GroupSummary>`.
- JPQL (엔티티명 GroupAggregate, memberCount 상관 서브쿼리, 목록은 joinedAt ASC — 신규 쿼리라 scope 무관):
```java
@Query("SELECT new com.wherewego.domain.group.GroupSummary(g.id, g.name, g.createdAt, "
 + "(SELECT COUNT(m2) FROM GroupMember m2 WHERE m2.groupId = g.id AND m2.leftAt IS NULL)) "
 + "FROM GroupMember gm JOIN GroupAggregate g ON g.id = gm.groupId "
 + "WHERE gm.userId = :userId AND gm.leftAt IS NULL AND g.deletedAt IS NULL "
 + "ORDER BY gm.joinedAt ASC, gm.id ASC")
List<GroupSummary> findActiveGroupSummariesByUserId(@Param("userId") Long userId);
```
- Service `listMyGroups(userId)` @Transactional(readOnly). Dto `GroupSummaryResponse.from()`. Controller `@GetMapping`(=`GET /api/v1/groups`, /me와 충돌X) 0개→[]. ApiSpec Operation 추가.

### 과제4 — groups/me id DESC 유지 (FR-6, BR-4, 정렬 보정 삭제)
`findMyActiveGroup`(:218) **완전 무변경**. 공유 쿼리 `findActiveGroupIdsByUserId`(JPA:14 `ORDER BY gm.id DESC`) **그대로 유지**(scope 0 — me·onboarding·UserDeletion·챗봇5 공유, 보정 시 제외 영역 동작 변경). PRD AC-8 "joined_at 최신"은 id BIGSERIAL 단조성으로 동치 → 주석 "id DESC는 BIGSERIAL 단조성으로 joined_at 최신과 동치(FR-6/AC-8 충족)" 명시. 응답 불변, 0개→null. **원안 정렬 보정 항목 삭제.**

### 과제5 — UserDeletionService 전체 순회 탈퇴 (FR-7, 엣지2, 데드락 방지)
단건(:74~97) → 활성 그룹 전체 순회(**group_id 오름차순** 결정론적 락 순서):
```java
// 2) 활성 그룹 전체를 group_id 오름차순(결정론적 락 순서)으로 순회 탈퇴.
//    다중 그룹 leaveGroup 의 findByIdForUpdate 비관락 순서를 모든 TX 에서 동일 고정 → 데드락 방지.
List<Long> activeGroupIds = groupMemberRepository.listActiveGroupIdsByUserId(userId);
boolean unlinkedViaLeaveGroup = false;
for (Long groupId : activeGroupIds) {
    boolean wasLastMember = groupMemberRepository.findOtherActiveMemberIds(groupId, userId).isEmpty();
    try {
        groupMemberService.leaveGroup(userId, groupId);   // 내부 unlink 멱등
        unlinkedViaLeaveGroup = true;
        if (wasLastMember) chatRoomRepository.softDeleteByGroup(groupId);
    } catch (CoreException e) {
        if (e.getErrorType() == ErrorType.GROUP_NOT_MEMBER) {
            log.warn("계정 삭제 중 그룹 탈퇴 race — 이미 비활성 (userId={}, groupId={})", userId, groupId);
        } else { throw e; }
    }
}
```
- botUserMapping unlink는 user 단위 1개라 그룹마다 호출돼도 멱등(`BotUserMappingService:60-61` deleteByUserId 확인). 0개일 때 백업 unlink(:101~103) 유지. 단일 @Transactional. `java.util.List` import 추가, 미사용 `Optional` 정리.

### 과제6 — listActiveGroupIdsByUserId 포트 + Onboarding 주석 (FR-9, BR-6, Q6)
**6-1. UserDeletion용 가벼운 포트** (신규, group_id ASC):
```java
// GroupMemberRepository
/** 활성 그룹 ID 목록. group_id 오름차순(다중 비관락 데드락 방지 결정론적 순서). */
List<Long> listActiveGroupIdsByUserId(Long userId);
// GroupMemberJpaRepository (기존 findActiveGroupIdsByUserId(Pageable)와 별개)
@Query("SELECT gm.groupId FROM GroupMember gm WHERE gm.userId = :userId AND gm.leftAt IS NULL ORDER BY gm.groupId ASC")
List<Long> findActiveGroupIdsByUserIdOrderByGroupId(@Param("userId") Long userId);
// Impl 위임
```
**6-2. UserOnboardingService 동작 무변경** — 현재 `findLatestActiveGroupIdByUserId` 기반이 이미 PRD 동치(present⟺hasActiveGroup, countActiveByGroupId(최신)=memberCount). 주석만 "GM-1: 다중 환경 hasActiveGroup=존재여부, memberCount=최신(id DESC) 활성그룹 멤버수, 웹 호환 유지(BR-6)" 보강. `OnboardingStatus.java` javadoc "혼자=1,짝꿍=2"→"최신 활성 그룹 멤버 수(다중 시 최근 가입 그룹 기준), 없으면 0". 정렬 보정 의존 삭제.

### 과제7 — 챗봇 5곳 TODO (FR-8, BR-5)
`findLatestActiveGroupIdByUserId` 위에 `// TODO(GM-2): 그룹 선택 이관 예정. GM-1 전환기=최신 활성 1개 저장(단수 유지, BR-5).` 코드 무변경. 대상: ChatbotWebhookService:152, PlaceSelectionHandler:64, ReelMemoWaitingHandler:106, InstagramLinkHandler:90, ReelSelectionAutoSaveScheduler:119.

### 과제8 — 테스트 재설계 (QE-1/2/3, AC-11)
**GroupMemberServiceTest**: :148/:305 제약 케이스 반전(다중 허용 성공), :161 삭제(createGroup catch 제거), :338 정원 2→10, 신규 `pairConflict→REJOIN`·`otherIntegrityViolation→rethrow(DataIntegrityViolation 전파)`·`listMyGroups`. existsActiveByUserId stub 전부 제거(포트 삭제).
**GroupMemberServiceIT**: :125 반전(secondGroup 성공), :401 단언 반전(==1→==5, DisplayName+주석 "사양변경 회귀아님"), :434 허용집합서 GROUP_ALREADY_ACTIVE 제거, 신규 AC-11(**서로 다른 10명**+11번째 거부)·QE-2(타그룹 활성중 수락)·동일그룹 동시가입 방어. runConcurrently 재사용·단언 교체.
**UserOnboardingServiceTest**: existsActiveByUserId stub 제거(포트 삭제로 컴파일 불가). 다중 그룹 케이스 1건 권장.
**GroupV1ControllerIntegrationTest**: 신규 AC-5(목록배열)·AC-6([])·AC-8(다중 me id DESC 최신)·인증401. 기존 me 케이스 유지(BR-4 가드).
**(QE-3) UserDeletion 다중 IT**: `deleteAccount_multipleGroups_leavesAll` — 3그룹 전부 left_at, 마지막멤버 그룹 soft delete, 파트너 잔존 그룹 유지.

## 구현 순서
```
1. V018 마이그레이션 (의존 없음)
2. GroupSummary record (의존 없음)
3. GroupMemberRepository 포트: listActiveGroupSummariesByUserId + listActiveGroupIdsByUserId 추가, existsActiveByUserId 제거 (←2)
4. GroupMemberJpaRepository: 목록쿼리(joinedAt ASC) + UserDeletion쿼리(group_id ASC) 추가, existsActiveByUserId 제거 (←2)
5. GroupMemberRepositoryImpl: 신규 2포트 위임, existsActiveByUserId 제거 (←3,4)
6. GroupMemberService: MAX 10, createGroup/acceptInviteLink 재설계, listMyGroups (←3)
7. GroupV1Dto.GroupSummaryResponse (←2)
8. GroupV1ApiSpec listMyGroups (←7)
9. GroupV1Controller GET /groups (←6,7,8)
10. UserDeletionService 순회 탈퇴(group_id 순서) (←3,4,6)
11. UserOnboardingService + OnboardingStatus 주석 (의존 없음, 동작무변경)
12. 챗봇 5곳 TODO (의존 없음)
13. ApiControllerAdvice dead path 주석 (의존 없음)
14. GroupMemberServiceTest (←6)
15. GroupMemberServiceIT (←1,6,9)
16. UserOnboardingServiceTest (←11)
17. GroupV1ControllerIntegrationTest (←9)
18. (QE-3) UserDeletion 다중 IT (←10)
```
1·2·11·12·13 병렬 가능(독립). 14~18 각 구현 후 병렬. 각 단계 대상 파일이 배타적(충돌 없음).

## 확인사항 해소 (6건)
- Q1: createGroup catch 완전 제거 / acceptInviteLink pair 매칭 유지+else rethrow (FK 오분류 방지, 비대칭).
- Q2: ApiControllerAdvice :158 그대로+dead path 주석.
- Q3: existsActiveByUserId 포트/JPA/Impl 제거+stub 정리.
- Q4: 신규 GroupSummary record.
- Q5: 공유 쿼리 id DESC 유지(scope 0), 신규 목록=joinedAt ASC, UserDeletion=group_id ASC.
- Q6: 가벼운 listActiveGroupIdsByUserId(group_id ASC) 포트 신규.
