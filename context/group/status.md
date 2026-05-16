# group 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현
- ⚠️ 부분 반영 — 인프라만 제공, 외부 통합은 후속 Phase에서 완성

## 요구사항

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-GRP-1 | 그룹 생성 (로그인 사용자가 1인 그룹 생성, 이름 1~30자 trim 검증) | ✅ | [#7](https://github.com/rnqhstmd/wherewego/pull/7) |
| FR-GRP-2 | 초대 링크 발급 (UUID + TTL 24h, 재발급 시 기존 미수락 토큰 즉시 만료) | ✅ | [#7](https://github.com/rnqhstmd/wherewego/pull/7) |
| FR-GRP-3 | 초대 링크 수락 → GroupMember 추가 (만료/이미수락/soft-delete 그룹/자기수락 거부) | ✅ | [#7](https://github.com/rnqhstmd/wherewego/pull/7) |
| FR-GRP-4 | 1인 1활성 그룹 제약 (서비스 + DB partial unique index 이중 보호) | ✅ | [#7](https://github.com/rnqhstmd/wherewego/pull/7) |
| FR-GRP-5 | 그룹 탈퇴 (GroupMember soft delete + 마지막 멤버 시 그룹 자동 soft delete + 토큰 일괄 만료, 단일 TX) | ✅ | [#7](https://github.com/rnqhstmd/wherewego/pull/7) |
| FR-GRP-6 | 탈퇴 시 본인 핀은 그룹 잔류 + created_by 유지 | ✅ | [#7](https://github.com/rnqhstmd/wherewego/pull/7) |
| FR-GRP-7 | 활성 GroupMember 기준 핀 조회/수정 권한 검사 | ⚠️ | [#7](https://github.com/rnqhstmd/wherewego/pull/7) — `GroupMemberService.requireActiveMembership()` 인프라 + 단위 테스트 제공. Pin REST API 통합은 Phase 4 |

## 동시성 보호

- 비관적 락(`SELECT ... FOR UPDATE`)으로 `groups` 행을 직렬화하여 락 → count → INSERT/UPDATE 순서 보장
- 1인 1활성 그룹: 서비스 사전 검사 + DB `uq_group_members_active_user` partial unique index + `DataIntegrityViolationException` → `GROUP_ALREADY_ACTIVE` 변환 (3단 방어)
- 마지막 멤버 탈퇴 + 정원 2명 race 모두 groups 행 락으로 보호
- 동시성 통합 테스트 3종(ExecutorService 기반)은 Phase 3 범위 제외, 후속 Phase 검토

## 후속 작업

- **Phase 4**: Pin REST API 신설 시 `requireActiveMembership` 호출 통합 + 단대단 403 검증, 동시성 통합 테스트 3종 구현 검토
- **장기**: 재가입 허용 정책 검토 (uq_group_members_pair 변경 필요, 별도 PRD)
