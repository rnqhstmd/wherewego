# 자기점검 — PR-3 (계정삭제+재가입)

## Critical
- 없음.

## Warning (자동 정리 완료)
- [해소] UserDeletionService unlink 이중 실행 → 활성 그룹 0개일 때만 직접 unlink(if !leftGroup). leaveGroup이 내부 수행.
- [해소] ChatMessageJpaRepository.nullifySenderByUserId에 `AND m.deletedAt IS NULL` 추가.

## Info (이월)
- UserDeletionService user.delete() 후 save(user) 명시 — 더티체킹상 중복 가능(기존 패턴 일관성으로 유지).
- UserV1ApiSpec @Operation에 내부 구현 순서 서술(클라 관점만 권장).

## QUESTION (이월 — 모두 의도)
- findById+isActive 패턴(AuthService 일관) — 의도.
- afterCommit else 분기(동기화 비활성 시 즉시 호출) — best-effort 의도.

## AC 충족 (자기점검 + 통합 테스트)
- AC-10(DELETE /me 마킹+연관삭제): UserDeletionService 충족.
- AC-11(마지막1인 그룹 soft delete): leaveGroup 재사용 충족.
- AC-12(Apple revoke 시도/스킵, deleted_at 마킹): AppleTokenRevoker afterCommit 충족.
- AC-13(재가입 신규계정 200): V017 partial index + 활성조회. **통합 테스트 3경로(Kakao callback/native, Apple native) 통과 검증**.
- 로그인 회귀: 인증 테스트 40개 통과(B1).
