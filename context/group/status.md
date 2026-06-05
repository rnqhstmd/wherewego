# group 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현
- ⚠️ 부분 반영 — 인프라만 제공, 외부 통합은 후속 Phase에서 완성
- 🔄 변경됨 — 이후 작업에서 정책이 교체됨

## 요구사항

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-GRP-1 | 그룹 생성 (로그인 사용자가 1인 그룹 생성, 이름 1~30자 trim 검증) | ✅ | [#7](https://github.com/rnqhstmd/wherewego/pull/7) |
| FR-GRP-2 | 초대 링크 발급 (UUID + TTL 24h, 재발급 시 기존 미수락 토큰 즉시 만료) | ✅ | [#7](https://github.com/rnqhstmd/wherewego/pull/7) |
| FR-GRP-3 | 초대 링크 수락 → GroupMember 추가 (만료/이미수락/soft-delete 그룹/자기수락 거부) | ✅ | [#7](https://github.com/rnqhstmd/wherewego/pull/7) |
| FR-GRP-4 | ~~1인 1활성 그룹 제약 (서비스 + DB partial unique 이중 보호)~~ → **GM-1에서 해제**(1인 N그룹, V018) | 🔄 | [#7](https://github.com/rnqhstmd/wherewego/pull/7) → [#99](https://github.com/rnqhstmd/wherewego/pull/99) |
| FR-GRP-5 | 그룹 탈퇴 (GroupMember soft delete + 마지막 멤버 시 그룹 자동 soft delete + 토큰 일괄 만료, 단일 TX) | ✅ | [#7](https://github.com/rnqhstmd/wherewego/pull/7) |
| FR-GRP-6 | 탈퇴 시 본인 핀은 그룹 잔류 + created_by 유지 | ✅ | [#7](https://github.com/rnqhstmd/wherewego/pull/7) |
| FR-GRP-7 | 활성 GroupMember 기준 핀 조회/수정 권한 검사 | ⚠️ | [#7](https://github.com/rnqhstmd/wherewego/pull/7) — `GroupMemberService.requireActiveMembership()` 인프라 + 단위 테스트 제공. Pin REST API 통합은 Phase 4 |

## GM-1: 1인 다중 활성 그룹 (PR [#99](https://github.com/rnqhstmd/wherewego/pull/99), AC-1~11 충족)

| ID | 요구사항 | 상태 |
|----|----------|------|
| GM1-FR-1 | V018 `uq_group_members_active_user` partial unique 제거 (1인 1활성 해제, additive-only) | ✅ |
| GM1-FR-2/3 | createGroup·acceptInviteLink `existsActiveByUserId` 제거 (GROUP_ALREADY_ACTIVE 미발생) | ✅ |
| GM1-FR-4/5 | `GET /api/v1/groups` 내 활성 그룹 목록(GroupSummary), 0개→`[]` | ✅ |
| GM1-FR-6 | `GET /groups/me` 응답 유지 (id DESC 최신 1개, 웹 호환) | ✅ |
| GM1-FR-7 | `UserDeletionService` 전체 활성 그룹 순회 탈퇴 (group_id 순서, 커플방 race 해소) | ✅ |
| GM1-BR-2 | 그룹당 정원 2→10 (`MAX_GROUP_MEMBERS`) | ✅ |
| GM1-동시성 | 토큰 1회용 `markAcceptedIfPending` 조건부 원자적 UPDATE | ✅ |
| GM1-챗봇 | 챗봇 5곳 단수전제 GM-2 이관 TODO (코드 무변경) | ✅ |

## 동시성 보호

- 비관적 락(`SELECT ... FOR UPDATE`)으로 `groups` 행을 직렬화하여 락 → count → INSERT/UPDATE 순서 보장
- ~~1인 1활성 그룹 3단 방어(서비스 사전검사 + partial unique + GROUP_ALREADY_ACTIVE 변환)~~ → **GM-1(V018)에서 해제**. 1인 N그룹 허용. 정원(10인)은 `groups` 비관락으로 직렬화. 토큰 1회용은 `markAcceptedIfPending` 원자적 UPDATE로 보장
- 마지막 멤버 탈퇴 + 정원 race 모두 `groups` 행 락으로 보호. 계정삭제 다중 그룹 순회는 group_id 오름차순 락 순서로 데드락 방지
- 동시성 통합 테스트(ExecutorService 기반)는 Phase 2.7 + GM-1(동일유저 다중 createGroup 허용 = 사양 변경 반영) — [#20](https://github.com/rnqhstmd/wherewego/pull/20), [#99](https://github.com/rnqhstmd/wherewego/pull/99)

## 후속 작업

- **Phase 4 완료**: Pin REST API([#9](https://github.com/rnqhstmd/wherewego/pull/9), [#13](https://github.com/rnqhstmd/wherewego/pull/13))에 `requireActiveMembership` 통합 완료
- **Phase 2.7 완료**: 동시성 통합 테스트 3종 (createGroup/acceptInviteLink/leaveGroup 각각 5스레드 race) — [#20](https://github.com/rnqhstmd/wherewego/pull/20)
- **GM-1 완료**: 1인 다중 활성 그룹 (제약 해제·정원10·목록 API·다중 순회 탈퇴·토큰 동시성) — [#99](https://github.com/rnqhstmd/wherewego/pull/99)
- **별도 작업(미착수)**: 초대 링크 → **초대 코드 시스템** 전환 (1코드 N명 재사용, 앱 코드 입력 + 랜딩 페이지). `accepted_at` 재설계 + iOS + 웹
- **장기**: 재가입 허용 정책 검토 (uq_group_members_pair 변경 필요, 별도 PRD)
