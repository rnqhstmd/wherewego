# PRD: GM-1 그룹 다중지원 — 백엔드 (GM 3단계 중 1단계)

## 배경

**GM 위치:** GM(그룹 다중지원) 전체는 백엔드(GM-1) → iOS UI(GM-2) → 검증·제출(GM-3) 3단계로 구성된다. 본 PRD는 **GM-1 백엔드** 단계다.

**현재 제품 상태:**
- 스키마는 이미 N:M(`group_members(group_id, user_id, joined_at, left_at)`)이나, 비즈니스 제약 FR-GRP-4가 "1인 1활성 그룹"을 강제한다.
- 3단 방어: DB partial unique `uq_group_members_active_user ON group_members(user_id) WHERE left_at IS NULL` + 서비스 사전검사 `existsActiveByUserId` + `DataIntegrityViolationException` → `GROUP_ALREADY_ACTIVE` 변환.
- 그룹당 정원: `MAX_GROUP_MEMBERS = 2`(2인 커플 고정) → **이 PRD에서 10으로 상향**.
- 내 활성 그룹 조회는 단수: `GET /api/v1/groups/me` → 단건 or null.
- `findLatestActiveGroupIdByUserId`를 챗봇 핸들러 5곳·온보딩·계정삭제 등 전반에서 단수 전제로 호출 중.
- 계정삭제(`UserDeletionService`)는 "1인1활성그룹: 0~1개"를 명시적으로 전제하고 단건 처리한다.

**웹은 앱 출시 시점에 중단 예정**(같은 백엔드 공유, 전환기에만 병행). 웹 호환은 기존 `GET /groups/me` 응답이 안 깨지는 수준만 유지한다.

**변경 이유:** iOS 다중 그룹 지원(GM-2) 착수 전, 백엔드가 1인 N그룹 활성 멤버십을 허용해야 한다. **스키마 재작업 없이 제약 해제 + 단수 가정 제거**가 핵심이다.

## 목표
- 1인이 동시에 N개 활성 그룹에 멤버로 속할 수 있도록 백엔드 제약을 해제한다.
- 기존 웹 클라이언트가 다중 그룹 사용자 환경에서도 깨지지 않도록 최소 하위 호환을 유지한다.
- 내 그룹 목록 조회 API(`GET /api/v1/groups`)를 신규 추가하여 iOS GM-2 착수 조건을 충족한다.
- 코드 전반의 단수 전제를 색출하여 다중 대응하거나 의도적 단수 유지를 판정·기록한다.

**성공 지표:**
- 기존 2인 커플 그룹이 마이그레이션 후 데이터 훼손 없이 동일하게 동작한다.
- 웹 클라이언트가 다중 그룹 사용자에 대해 `myActiveGroup`(최신 1개)을 정상 반환한다.
- 1인 N그룹 가입 흐름(그룹 생성, 초대 수락)이 에러 없이 완료된다.

## 결정 로그 (requirements Q&A 수렴, 2026-06-05)
| 항목 | 결정 |
|------|------|
| 그룹당 정원 | 2 → **10명** (`MAX_GROUP_MEMBERS=10`, 정원검사 유지·한계만 10) |
| 1인당 가입 그룹 수 | **무제한** (상한검사 없음, FR-10 제외) |
| 챗봇 저장 대상 그룹 | **GM-2로 이관**. GM-1은 현행 유지(최신1개)·단수전제 색출+주석만 |
| 웹 호환 | **최소** (groups/me 안 깨지게만). 앱 출시 시 웹 중단 예정 |
| GET /groups 응답 필드 | `groupId`·`name`·`createdAt`·`memberCount` |

## 요구사항

### 기능 요구사항
- [Must] FR-1: V018 Flyway 마이그레이션으로 `uq_group_members_active_user` partial unique index를 제거한다. 기존 `group_members` 데이터는 additive-only로 변경하며 행을 수정·삭제하지 않는다.
- [Must] FR-2: `GroupMemberService.createGroup`에서 `existsActiveByUserId` 사전검사를 제거하고, `DataIntegrityViolationException` 변환 로직에서 `GROUP_ALREADY_ACTIVE` 분기를 제거한다.
- [Must] FR-3: `GroupMemberService.acceptInviteLink`에서 `existsActiveByUserId` 사전검사를 제거하고, `DataIntegrityViolationException` 변환에서 `GROUP_ALREADY_ACTIVE` 분기를 제거한다. `uq_group_members_pair` 충돌(재가입 차단) → `GROUP_REJOIN_FORBIDDEN` 변환은 유지한다.
- [Must] FR-4: `GroupMemberRepository`에 `listActiveByUserId(Long userId): List<...>` 포트를 추가하고, JPA 구현체에 다중 활성 그룹 목록 쿼리를 구현한다.
- [Must] FR-5: `GET /api/v1/groups` 엔드포인트를 신규 추가한다. 인증 필수. 응답은 활성 그룹 목록 배열(`groupId`, `name`, `createdAt`, `memberCount`)이며, 그룹이 0개이면 빈 배열(`[]`)을 반환한다.
- [Must] FR-6: 기존 `GET /api/v1/groups/me` 엔드포인트를 유지한다. 응답 구조·필드명(`groupId`, `name`, `createdAt`, `memberCount`)을 변경하지 않는다. 다중 그룹 사용자에게는 `joined_at` 기준 가장 최근 활성 그룹 1개를 반환하고, 활성 그룹이 없으면 `null`을 반환한다. (웹 하위 호환 전략)
- [Must] FR-7: `UserDeletionService.deleteAccount`의 그룹 탈퇴 로직을 다중 그룹 대응으로 변경한다. 단건 조회 후 단건 탈퇴 구조를 전체 활성 그룹 목록 순회 탈퇴로 교체한다.
- [Should] FR-8: 챗봇 핸들러 5곳(`PlaceSelectionHandler`, `ReelMemoWaitingHandler`, `InstagramLinkHandler`, `ChatbotWebhookService`, `ReelSelectionAutoSaveScheduler`)의 `findLatestActiveGroupIdByUserId` 단수 전제는 GM-1에서 **코드 동작·시그니처 변경 없이 현행 유지**한다. 전환기 임시 동작(최신 활성 1개에 저장)임을 **GM-2 인계용 주석/TODO**로 각 호출부에 기록하는 것으로 완료한다. 봇 메시지 groupId 명시화(그룹 선택 UX 포함)는 GM-2로 이관한다.
- [Should] FR-9: `UserOnboardingService.getStatus`의 단수 전제를 다중 그룹 대응으로 교체한다. `hasActiveGroup = (activeGroupCount > 0)`. `activeGroupMemberCount`는 가장 최근 활성 그룹의 멤버 수로 유지한다. (웹 온보딩이 소비 — 기존 필드명·의미 유지 필수)
- ~~FR-10~~: **삭제**. 1인당 동시 활성 그룹 수 상한 검사 및 `GROUP_MEMBER_LIMIT_EXCEEDED`를 추가하지 않는다(무제한). → 제외 범위.

### 비즈니스 규칙
- [Must] BR-1: `uq_group_members_pair UNIQUE(group_id, user_id)` 제약은 유지한다. 동일 그룹 재가입은 여전히 `GROUP_REJOIN_FORBIDDEN`으로 거부한다.
- [Must] BR-2: 그룹당 정원은 `MAX_GROUP_MEMBERS = 10`이다. 10명이 활성인 그룹에 대한 추가 초대 수락은 `GROUP_CAPACITY_EXCEEDED`로 거부한다. 이 제한은 "1인당 가입 가능 그룹 수"와 무관하며 그룹 단위로 적용된다.
- [Must] BR-3: V018 마이그레이션은 데이터를 additive-only로 처리한다. 기존 `group_members` 행의 수정·삭제·컬럼 변경은 일절 허용하지 않는다.
- [Must] BR-4: `GET /api/v1/groups/me`의 응답 구조(필드명, 타입, null 반환 규칙)는 변경하지 않는다.
- [Must] BR-5: 챗봇의 핀 저장 대상 그룹은 GM-1에서 변경하지 않는다. `findLatestActiveGroupIdByUserId`(최신 활성 1개)를 그대로 사용하되, 이 동작이 의도적 단수 유지(GM-2 이관 예정)임을 코드에 명시한다.
- [Should] BR-6: `UserOnboardingService` 응답의 `hasActiveGroup`, `activeGroupMemberCount` 필드는 다중 그룹 환경에서도 웹이 해석 가능한 값을 반환한다. `hasActiveGroup`은 활성 그룹 1개 이상이면 `true`.

### 품질 기대
- [Should] QE-1: 기존 커플 흐름(그룹 생성 → 초대 → 수락 → 탈퇴)의 회귀 테스트가 변경 후에도 통과한다.
- [Should] QE-2: 1인 N그룹 가입 흐름(그룹 A 가입 상태에서 그룹 B 생성 또는 수락)의 통합 테스트가 추가된다.
- [Should] QE-3: 계정삭제 시 N개 그룹을 모두 탈퇴하는 흐름의 통합 테스트가 추가된다.

## 사용자 시나리오

**정상 흐름 1 — 기존 커플 (변경 없음)**: 그룹 생성 → 초대 → 수락 → `GET /groups/me` 단일 그룹 반환 → 탈퇴.

**정상 흐름 2 — 다중 그룹 가입 (신규)**:
1. 사용자 A가 그룹 X 활성 상태에서 그룹 Y 생성 → 성공(201)
2. `GET /api/v1/groups` → `[{groupX}, {groupY}]`
3. `GET /api/v1/groups/me` → `joined_at` 최신 그룹 Y (웹 호환)
4. 그룹 X만 탈퇴 → 그룹 Y 활성 유지

**예외 흐름 1 — 재가입 시도**: 탈퇴한 그룹 X 초대 수락 → `GROUP_REJOIN_FORBIDDEN` (BR-1).

**예외 흐름 2 — 정원 초과**: 그룹 X에 10명 활성 상태에서 11번째 수락 → `GROUP_CAPACITY_EXCEEDED` (BR-2).

**엣지 1 — 활성 0개**: `GET /groups` → `[]`, `GET /groups/me` → `null`.

**엣지 2 — 계정삭제 + N개 그룹**: 모든 활성 그룹 순회 탈퇴(각 그룹 마지막 멤버면 그룹 soft delete). 순회 중 race `GROUP_NOT_MEMBER` 발생 시 해당 그룹 건너뛰고 계속.

**엣지 3 — TOCTOU**: `uq_group_members_active_user` 제거 후 동일 그룹 동시 이중가입 → `uq_group_members_pair` 충돌 → `GROUP_REJOIN_FORBIDDEN`. 서로 다른 그룹 동시 가입은 허용. 정원 race는 `SELECT FOR UPDATE` 락 유지.

## 영향 범위
- `GroupMemberService`: `createGroup`, `acceptInviteLink` — 제약 검사 로직 변경. `MAX_GROUP_MEMBERS` 10.
- `UserDeletionService`: 그룹 탈퇴 순회 로직 변경.
- `UserOnboardingService`: 단수 전제 조회 수정.
- `GET /api/v1/groups/me`: 동작 의미 변경 없음, 구현 내부만 "최신 1개" 명시.
- **테스트 대거 영향**: `GroupMemberServiceTest`/`IT`(제약·정원·동시성), `UserOnboardingServiceTest`, `GroupV1ControllerIntegrationTest`.
- 기존 2인 커플 데이터: 마이그레이션 후 동일 동작, 훼손 없음.
- `GROUP_ALREADY_ACTIVE`는 이 PRD 이후 발생하지 않음(dead path).

## 수용 기준
- AC-1: V018 실행 후 `uq_group_members_active_user` 인덱스가 DB에 없다. 기존 `group_members` 행 수가 전후 동일하다. → [FR-1, BR-3]
- AC-2: 활성 그룹 X 보유 사용자 A가 `POST /api/v1/groups`로 그룹 Y 생성 시 HTTP 201. `GROUP_ALREADY_ACTIVE` 미발생. → [FR-2]
- AC-3: 활성 그룹 X 보유 사용자 A가 유효한 그룹 Y 초대 수락 시 HTTP 200. `GROUP_ALREADY_ACTIVE` 미발생. → [FR-3]
- AC-4: 탈퇴한 그룹 Z 초대 수락 시도 → `GROUP_REJOIN_FORBIDDEN`. → [FR-3, BR-1]
- AC-5: `GET /api/v1/groups`(인증) 호출 시 활성 그룹 N개에 대해 길이 N 배열. 각 항목에 `groupId`,`name`,`createdAt`,`memberCount` 포함. → [FR-4, FR-5]
- AC-6: 활성 0개 사용자가 `GET /api/v1/groups` → `[]`. → [FR-5]
- AC-7: `GET /api/v1/groups/me` 응답의 `groupId`,`name`,`createdAt`,`memberCount` 필드명·타입이 변경 전과 동일. 활성 0개면 `null`. → [FR-6, BR-4]
- AC-8: 다중 그룹(X,Y) 보유 사용자가 `GET /api/v1/groups/me` → `joined_at` 최신 그룹 1개만 반환. → [FR-6]
- AC-9: N개 활성 그룹 사용자의 계정삭제 성공 시 해당 사용자의 모든 `group_members` 행에 `left_at` 기록. → [FR-7]
- AC-10: `UserOnboardingService.getStatus`에서 활성 그룹 1개 이상이면 `hasActiveGroup=true`, 0개면 `false`. → [FR-9, BR-6]
- AC-11: 특정 그룹에 10명 활성 멤버가 있을 때 11번째 초대 수락 → `GROUP_CAPACITY_EXCEEDED`. → [BR-2]

## 비기능 요구사항
**웹 하위 호환(최소):** `GET /api/v1/groups/me` 응답 스키마 불변. `GROUP_ALREADY_ACTIVE` dead path는 웹 중단 예정이라 별도 정리 불필요.

**데이터 안전:** V018은 `DROP INDEX`만 수행. `ALTER TABLE`로 컬럼 추가·수정·삭제하거나 기존 행을 UPDATE·DELETE하지 않는다. 롤백 시: 다중 그룹 행 생성 후 인덱스 재생성은 제약 위반으로 실패 가능 → 다중 그룹 행 정리 후 재생성 필요.

**동시성:** 정원 경쟁은 `SELECT FOR UPDATE` 유지(정원 10 기준). TOCTOU 동일 그룹 이중가입은 `uq_group_members_pair` 충돌→`GROUP_REJOIN_FORBIDDEN`. 서로 다른 그룹 동시 가입은 허용. 계정삭제+동시 탈퇴 race는 `GROUP_NOT_MEMBER` 흡수 후 다음 그룹 계속.

## 리스크
| # | 리스크 | 영향 | 대응 |
|---|--------|------|------|
| R-1 | 챗봇 핀 저장 대상 그룹 모호성 | 다중 그룹 사용자가 챗봇에 보내면 "최신 활성 1개"에 저장되어 의도와 다를 수 있음 | GM-1에서 단수 유지 정책을 주석 명시. GM-2에서 그룹 선택 UX와 함께 해소 |
| R-2 | 웹 `GROUP_ALREADY_ACTIVE` 의존 | dead path 발생 | 웹 중단 예정이라 영향 미미. 별도 조치 불필요 |
| R-3 | 온보딩 `activeGroupMemberCount` 의미 변화 | 복수 그룹 시 "어느 그룹 멤버 수?" 모호 | 최신 활성 그룹 기준 유지. 웹 중단 예정이라 실질 영향 없음 |
| R-4 | V018 롤백 | 다중 그룹 행 생성 후 인덱스 재생성 불가 | 마이그레이션 전 스냅샷, 롤백 절차 사전 정의 |
| R-5 | 정원 2→10 후 정원 검사 테스트 공백 | 기존 테스트가 MAX=2 기준 | AC-11 기준 정원 경계 테스트(10명+11번째 거부) 신규 작성 |

## 제외 범위
- iOS 클라이언트(GM-2): 그룹 목록 UI, 그룹 전환 UX, 채팅·알림 다중 그룹 분기.
- 웹 프론트엔드: 다중 그룹 표시, `GROUP_ALREADY_ACTIVE` dead path 제거 등 웹 전용 정리(웹 중단 예정).
- 챗봇 그룹 선택(봇 메시지 groupId 명시, 그룹 선택 UX): GM-2에서 백엔드+iOS 함께.
- 1인당 가입 그룹 수 상한: 두지 않음(무제한).
- 그룹당 정원 추가 변경: 이 PRD에서 10으로 확정. 이후 변경은 별도 PRD.
- 재가입 허용(`uq_group_members_pair` 변경): 별도 PRD.
