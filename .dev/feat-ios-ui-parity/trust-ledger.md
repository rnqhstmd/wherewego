## Trust Ledger

### Hotfix 긴급 감사 (security-auditor, 내비 셸 재구성)
감사 범위: 멀티그룹 전환 — CRITICAL/HIGH 한정. 결과: **CRITICAL 0, HIGH 5**.

- **[RISK/HIGH] 그룹 전환 실패 시 GroupContext 롤백 미구현** (배포 전 권장)
  - 근거: `switchActiveGroup`이 `setActiveGroup(group)`(동기, 칩 즉시 갱신) 후 `Task { mapViewModel.switchTo }`. 전환 대상이 그새 탈퇴/삭제돼 서버가 403(GROUP_NOT_MEMBER) 반환 시, 지도는 .error 이나 `activeGroupId`는 실패 그룹으로 유지 → 칩(탈퇴 그룹명) vs 지도(에러) 불일치.
  - 권고: `switchTo` 실패 시 `activeGroupId/Name`을 이전 값으로 롤백.

- **[RISK/HIGH] 활성그룹 이중 resolve (GroupContext vs MapViewModel)** (배포 전 권장)
  - 근거: 진입 시 `GroupContext.bootstrap()`과 `MapViewModel.load()`가 각각 독립적으로 `myActiveGroup()` 호출. `bootstrap()` 실패(try?)면 칩 "그룹 없음" + 지도엔 핀 정상 표시 불일치.
  - 권고: 활성그룹 시드를 단일 소스(GroupContext)로 통합, 지도 로드는 resolved groupId 주입.

- **[GAP/HIGH] 봇 채팅 그룹 독립성 미정의** (정책 결정 필요)
  - 근거: BotChatViewModel은 userId 토픽 기반(그룹 무관). `switchGroup()`은 사실상 새로고침. FR-5 "채팅 방 전환"이 채팅 영역에선 의미상 성립 안 함(데이터 노출 위험은 없음 — 단일 방).
  - 권고: 봇 채팅이 전역 방임을 PRD에 명시하거나 그룹 전환 시 no-op 처리.

- **[ASSUMPTION/HIGH] listMyGroups 활성 필터 전제** (백엔드 확인)
  - 근거: `GroupMemberRepository.listActiveGroupSummariesByUserId` 쿼리에 `left_at IS NULL AND deleted_at IS NULL`이 있는지 diff로 미확인.
  - 권고: 백엔드 쿼리 확인. 누락 시 탈퇴 그룹 목록 노출.

- **[ASSUMPTION/HIGH] setActiveGroup 사전검증 부재** (후속)
  - 근거: `setActiveGroup`이 인자 검증 없이 activeGroupId 갱신. 현재 경로는 listMyGroups 결과만 주입하므로 직접 위험 아님. 서버 requireActiveMembership이 최종 방어선.
  - 권고: `groups` 배열 내 존재 검증 강화(낮은 긴급도).

### 수정 반영 (자동수정 + 검증)
- ✅ [RISK/HIGH] 그룹 전환 실패 롤백 — `switchTo`가 Bool 반환, `switchActiveGroup`이 실패 시 `rollbackActiveGroup`으로 칩 복원. 검증: 코드 확인 + 빌드 그린.
- ✅ [RISK/HIGH] 활성그룹 이중 resolve — GroupContext.bootstrap() 단일 소스, MapView.task 자체 load() 제거, MainTabView가 resolved groupId 주입(load(groupId:)/loadEmpty). 검증 통과.
- ✅ [ASSUMPTION/HIGH] listMyGroups 활성 필터 — 백엔드 `GroupMemberRepository.listActiveGroupSummariesByUserId` 주석/도메인이 "활성 = left_at IS NULL" 명시, Group deletedAt soft-delete. **해소**.
- ✅ [Warning] 전환 재로드 async let 병렬, bootstrap do/catch 에러·빈 분리.
- ⏸ [GAP/HIGH] 봇 채팅 그룹 독립성 — 정책 결정 필요(봇 방=userId 전역). **사용자 확인 대기**(후속).
- ⏸ [ASSUMPTION/HIGH] setActiveGroup 사전검증 — 낮은 긴급도, 후속.
