# 자기점검 결과 (GM-1)

## CERTAIN (자동수정 완료)
- [GroupV1ApiSpec.java:15~16] createGroup Swagger description에 해제된 "1인 1활성 그룹 제약(BR-1)" 문구 잔존 → "GM-1: 1인 다중 활성 그룹 지원(제약 해제), 동일 그룹 재가입만 GROUP_REJOIN_FORBIDDEN" 문구로 **수정 완료**.

## QUESTION (해소됨)
- [UserDeletionService unlinkedViaLeaveGroup 플래그] race 시 백업 unlink 미실행 가능성 → `BotUserMappingService.unlink`=`deleteByUserId`로 userId 단위 멱등(design-critic 확인). 정합, 유지.
- [UserDeletionServiceMultiGroupIT owner_user_id=NULL INSERT] chat_room.owner_user_id nullable 여부 → IT(QE-3) 통과로 nullable 확인됨. 유지.

## 추가 발견 (IT, 별도 처리)
- [acceptInviteLink 토큰 1회용 동시성] 정원 2→10으로 토큰 1회용 동시성 허점 노출(`acceptedAt` 체크가 group 락 전). → **조건부 원자적 UPDATE(`markAcceptedIfPending`)로 1회용 보장** (coder 수정). 초대 코드 **재사용** 시스템은 별도 작업으로 분리(사용자 결정 2026-06-05).

## 설계 확정사항 점검 (qa-manager)
- 예외 비대칭(createGroup catch 제거/acceptInviteLink pair+rethrow) ✓ / 공유쿼리 id DESC 유지 ✓ / 신규쿼리 정렬 ✓ / 엔티티명 GroupAggregate ✓ / existsActiveByUserId 3레이어 제거 ✓ / MAX=10 ✓ / GroupSummary ZonedDateTime ✓ / UserDeletion group_id 순회 ✓ / 챗봇 주석만 ✓ / GET /groups ✓
- AC-1~11 전부 코드/테스트 반영 확인.
