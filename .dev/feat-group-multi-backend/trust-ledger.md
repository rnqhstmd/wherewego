# Trust Ledger (GM-1 그룹 다중지원 백엔드)

## 통합 감사 (security-auditor)
총 8건 — CRITICAL 0, HIGH 2, MEDIUM 6. 보안 취약점(인젝션/인증우회/정보노출) 없음.

- **[GAP/HIGH] 정원 TOCTOU 직렬화 명시 누락** (GroupMemberService.java:125~132)
  - 근거: `findByIdForUpdate`(groups 행 비관락)가 동일 그룹 동시 수락을 직렬화하나, 코드에 의도 미명시. 실제로는 안전(동일 그룹 락 공유).
  - 권고: "정원 직렬화는 findByIdForUpdate 그룹 락으로 보장" 주석 추가.
- **[GAP/HIGH] UserDeletion wasLastMember race** (UserDeletionService.java:82~88)
  - 근거: `wasLastMember`를 leaveGroup **전** 판정 → race(파트너 동시 탈퇴) 시 leaveGroup이 그룹 soft delete하나 커플방(chatRoom)은 미정리 → 고아. 기존 코드 race이나 다중 그룹으로 경로 증가.
  - 권고: leaveGroup 후 group.deletedAt 재조회로 정리, 또는 leaveGroup이 마지막멤버 여부 반환.
- [ASSUMPTION/HIGH] unlinkedViaLeaveGroup 백업 unlink (UserDeletionService.java:77,102) — BotUserMapping=deleteByUserId 멱등 확인됨. 주석 명시 권고.
- [GAP/MEDIUM] markAcceptedIfPending 롤백 — 동일 TX 롤백으로 accepted_at 복원(정상). 주석 권고.
- [ASSUMPTION/MEDIUM] listActiveGroupSummariesByUserId 서브쿼리 memberCount — READ COMMITTED서 목록 일시 불일치 가능(보안X).
- [GAP/MEDIUM] AC-11 테스트 — 정원 초과 거부 후 토큰 미소진(accepted_at IS NULL) 단언 추가 권고.
- [확인완료] GET /api/v1/groups userId 스코프 안전(authenticated + @AuthUser), JPQL @Param 정상, FK/pair 우회 없음.
- [POLICY/MEDIUM] ApiControllerAdvice dead path — PRD 결정(웹 중단 후 제거 티켓).

## QA 리뷰 (qa-manager)
- Critical 0. AC-1~11 전부 충족.
- Warning: wasLastMember race(ZT 중복), memberCount 서브쿼리 PERF(MVP 허용).
- QUESTION(해소): group 락 순서(정상), owner_user_id nullable(IT 통과 확인).

## 합산 결과
- **CRITICAL/Critical: 0건**
- HIGH: 2건 (정원 직렬화 주석, wasLastMember race)
- MEDIUM: 다수 (주석/테스트 강화)
- 보안 취약점: 없음
