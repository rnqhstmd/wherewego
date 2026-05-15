# group 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현

## 요구사항

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-GRP-1 | 그룹 생성 (로그인 사용자가 1인 그룹 생성) | ⬜ | Phase 3 |
| FR-GRP-2 | 초대 링크 발급 (UUID + TTL 24h) | ⬜ | Phase 3 |
| FR-GRP-3 | 초대 링크 수락 → GroupMember 추가 | ⬜ | Phase 3 |
| FR-GRP-4 | 1인 1활성 그룹 제약 (서비스 레이어 검증) | ⬜ | Phase 3 |
| FR-GRP-5 | 그룹 탈퇴 (GroupMember soft delete) | ⬜ | Phase 3 |
| FR-GRP-6 | 탈퇴 시 본인 핀은 그룹 잔류 + created_by 유지 | ⬜ | Phase 3 |
| FR-GRP-7 | 활성 GroupMember 기준 핀 조회/수정 권한 검사 | ⚠️ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — 챗봇 핀 등록 경로용 read-only 조회만 선행 도입 (`GroupMemberJpaRepository.findActiveGroupIdsByUserId() ORDER BY id DESC`). 권한 검사는 Phase 3 |

## 후속 작업

- **Phase 3**: 그룹 생성/초대/탈퇴 + 권한 검사 본 구현. Phase 2는 BR-6 "최근 가입 그룹" 자동 결정용 read-only repository만 선행 도입.
