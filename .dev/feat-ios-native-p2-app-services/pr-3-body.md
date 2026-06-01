## Background

App Store Guideline 5.1.1(v)는 회원 가입이 있는 앱에 계정 삭제 기능을 요구한다. 기존에는 soft-delete 컬럼(`deletedAt`)만 있고 삭제 플로우가 없었으며, 탈퇴 후 동일 소셜 계정으로 재가입할 방법도 없었다. P2의 세 번째이자 마지막 PR로, **PR-2(APNs+devices) 위에 스택**된다(base: `feat/ios-native-p2-push`, PR #88). 로그인 핵심 경로(조회 보정)를 건드리므로 회귀 0이 핵심 조건이다.

> ⚠️ 스택 PR: PR-1(#87) → PR-2(#88) 머지 후 base 리타겟/리베이스가 필요하다.

## Summary

`DELETE /api/v1/users/me`로 본인 계정을 삭제하고, 동일 소셜 계정으로 재가입(FR-24)할 수 있게 한다. 삭제는 단일 트랜잭션에서 그룹 탈퇴(기존 `leaveGroup` 재사용)·봇 매핑 해제·채팅 sender 익명화·봇방/디바이스 soft delete·refresh 토큰 무효화·계정 soft delete를 수행하고, Apple revoke는 커밋 후 best-effort로 분리한다. 재가입은 식별자를 변경하지 않고, V017에서 `users`의 두 UNIQUE를 **partial unique index(`WHERE deleted_at IS NULL`)** 로 전환하여 soft-delete 행을 제약에서 제외하는 방식으로 처리한다. 로그인 조회는 활성 행만 매칭하도록 보정하여 탈퇴 계정이 재로그인하면 깨끗한 신규 계정이 생성된다.

## Changes

- **계정 삭제**: `UserDeletionService.deleteAccount`(@Transactional) — 활성 그룹만 `leaveGroup`(TOCTOU race는 `GROUP_NOT_MEMBER` 흡수), 봇 매핑 `unlink` 정확히 1회, `chat_message.sender_user_id` NULL(활성 행), 봇방·device soft delete, refresh 무효화, 계정 soft delete. `DELETE /api/v1/users/me`(@AuthUser 본인 한정).
- **재가입(FR-24)**: V017 — `uq_users_oauth`/`uq_users_kakao_user_id`를 partial unique index로 전환(`DROP CONSTRAINT IF EXISTS` + `CREATE UNIQUE INDEX ... WHERE deleted_at IS NULL`). `UserRepository.findBy...` → `...AndDeletedAtIsNull`(활성 한정). `UserModel.kakao_user_id` 어노테이션을 partial index 현실에 맞게 정정. `UserLoginPersistence`는 활성 미스 시 신규 행 생성(`AUTH_USER_DEACTIVATED` 분기는 동시성 대비 방어적 유지, refresh의 `isActive()` 가드 유지).
- **Apple revoke**: `AppleTokenRevoker`(best-effort 스킵 logger) — `.p8` client_secret·refresh token 저장 인프라가 없어 실제 호출 대신 스킵 로그. afterCommit으로 호출되어 삭제 완료를 막지 않음.
- **chat 리포지토리**: `ChatMessageRepository.nullifySenderByUserId`(활성 행만), `ChatRoomRepository.softDeleteByOwner`(봇방).
- **테스트**: 인증 통합 테스트를 재가입 정책으로 갱신(탈퇴자 재로그인 → 401 차단에서 → 200 신규 계정). Kakao callback/native·Apple native 3경로 재가입 검증.

## Audit Summary

통합 감사(QA + ZeroTrust) — 판정: 정합성 수정 후 머지, 구조적 HIGH 이월.

- **CRITICAL: 0** (QA가 제기한 `@Column(unique=true)` 기동 실패는 ddl-auto=none + 통합 테스트 부팅 통과로 오탐 확인 — 어노테이션 정합성만 정정).
- 수정: leaveGroup TOCTOU race try-catch, V017 `DROP CONSTRAINT IF EXISTS`, UserModel 어노테이션 정정, unlink 1회·nullify 필터(자기점검).
- **이월(구조적/정책)**: 탈퇴 후 STOMP 세션 미종료(사용자 결정: 이월, 단일 인스턴스 베타·후속 SimpUserRegistry), 탈퇴 후 access JWT TTL 잔존(배포 TTL 짧게 권고, refresh는 isActive 차단), Apple revoke 실제 미수행(.p8 인프라 후속), 탈퇴 메시지 payload 보존(PRD 명시·법무 검토), UserDeletionService 통합 테스트 부재.
- 수용 기준: PR-3 [Must] AC-10/11/12/13 전량 충족(인수 ACCEPT). 로그인 회귀 0(인증 테스트 40개 통과).

상세: `.dev/feat-ios-native-p2-app-services/trust-ledger.md`

## Checklist

- [ ] `DELETE /api/v1/users/me` 삭제 후 연관 데이터(refresh/device/chat sender/멤버십) 정리 확인
- [ ] 마지막 1인 그룹 삭제 시 그룹 soft delete 확인
- [ ] 탈퇴 후 동일 소셜 계정 재로그인 → 신규 빈 계정 생성(재가입) 확인 (통합 테스트 통과)
- [ ] 기존 정상 로그인/신규 가입 회귀 0 확인 (인증 테스트 40개 통과)
- [ ] V017 마이그레이션 적용(partial unique index 전환) 확인
- [ ] (이월) 탈퇴 시 STOMP 세션 종료·Apple 실제 revoke·access 토큰 블랙리스트는 후속
- [ ] (스택) PR-1(#87)·PR-2(#88) 머지 후 base 리타겟/리베이스
