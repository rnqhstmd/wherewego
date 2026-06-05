## 코드 맵: GM-1 그룹 다중지원 백엔드 (1인 N그룹 제약 해제 + 내 그룹 목록 API + 단수전제 색출)

### 핵심 파일
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/group/GroupMemberService.java` → **단수전제 심장부**. `MAX_GROUP_MEMBERS=2`(:22), `existsActiveByUserId`(:53 createGroup, :128 acceptInviteLink), `findLatestActiveGroupIdByUserId`(:38), `findMyActiveGroup`(:218), `countActiveByGroupId>=MAX`(:131,:145), DataIntegrityViolation→GROUP_ALREADY_ACTIVE 변환
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/group/GroupMemberRepository.java` → 포트 인터페이스. existsActiveByUserId/findLatestActiveGroupIdByUserId/countActiveByGroupId/findActiveByGroupIdAndUserId 정의 (listMyGroups 추가 지점)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/group/GroupMemberJpaRepository.java` + `GroupMemberRepositoryImpl.java` → JPA 쿼리 구현. 다중 그룹 목록 쿼리 추가 위치
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/group/GroupV1Controller.java` + `GroupV1ApiSpec.java` + `GroupV1Dto.java` → 그룹 REST 진입점. **GET /groups(내 그룹 목록) 신규 추가 위치**
- `backend/apps/wherewego-api/src/main/resources/db/migration/` → 최신 **V017**(재가입용 user partial unique, group 제약 미변경). **V018 신규**: `uq_group_members_active_user` partial unique 제거 위치

### 참조 파일
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/group/ActiveGroupInfo.java` → 단수 활성그룹 DTO(record). 다중 대응(GroupSummary 등) 검토
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/me/MeV1Dto.java` → `activeGroup` 노출. 웹 호환(최신1개) 유지 + 목록 추가
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/bot/` (BotUserMappingService) → bot user→group 확정. 다중 시 어느 그룹? (단수전제)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/` (ChatbotWebhookService, PlaceSelectionHandler, ReelMemoWaitingHandler, InstagramLinkHandler, ReelSelectionAutoSaveScheduler) → findLatestActiveGroupId 의존 (단수전제 색출 대상)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/user/UserOnboardingService.java` + `OnboardingStatus.java` → 온보딩 종착(그룹 보유 판정). 다중 시 재검토
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/user/UserDeletionService.java` → 계정삭제 시 그룹 정리 (다중 그룹 대응)
- `backend/apps/wherewego-api/src/test/java/com/wherewego/domain/group/GroupMemberServiceIT.java` + `GroupMemberServiceTest.java` → 회귀(커플 흐름) 검증 기준

### 설정/문서
- `context/group/architecture.md` → 도메인 SSOT: uq_group_members_active_user 제약 정의, 탈퇴 정책, N:M 스키마
- `context/group/status.md` → FR-GRP-1~7 구현추적. "장기: 재가입 허용 정책(별도 PRD)" = GM-1 근거
- `backend/build.gradle.kts` → java-spring 빌드(./gradlew)

### 단수전제 색출 메모 (architect/researcher 전수 대상 — Grep 28파일)
키워드: `existsActiveByUserId` `findMyActiveGroup` `findLatestActiveGroupId` `MAX_GROUP_MEMBERS` `uq_group_members_active_user` `activeGroup`. 웹 호환 핵심: `myActiveGroup`(최신1개 의미 유지) → 웹 안 깨짐 + `listMyGroups` 추가.

### GM-1 변경 영향 추가 식별 (requirements Q&A 후 확정)
- **정책 결정**: 그룹당 정원 `MAX_GROUP_MEMBERS` 2→**10** / 1인당 그룹 수 **무제한**(상한검사 없음) / 챗봇 **GM-2 이관**(GM-1은 현행 최신1개 유지·색출만) / 웹 **최소호환**(groups/me 안 깨지게만, 앱 출시 시 웹 중단 예정)
- 에러/처리: `support/error/ErrorType.java`(GROUP_ALREADY_ACTIVE:45, GROUP_CAPACITY_EXCEEDED:47) / `interfaces/api/ApiControllerAdvice.java:159`(GROUP_ALREADY_ACTIVE 응답 매핑)
- 온보딩 멤버수: `domain/user/UserOnboardingService.java:37` `groupMemberRepository.countActiveByGroupId`
- **테스트 대거 수정 대상**(제약해제·정원10·동시성 의미변화): `test/.../domain/group/GroupMemberServiceTest.java`(:148 활성보유→ALREADY_ACTIVE = **뒤집힘**, :338 정원2→10), `GroupMemberServiceIT.java`(:401/:461 동일유저 동시 createGroup = **다중허용으로 의미변화**), `domain/user/UserOnboardingServiceTest.java`, `interfaces/api/group/GroupV1ControllerIntegrationTest.java`
- ⚠️ 혼동주의: `pinRepository.countActiveByGroupId`(핀 수, PinService:244) ≠ `groupMemberRepository.countActiveByGroupId`(멤버 수)
