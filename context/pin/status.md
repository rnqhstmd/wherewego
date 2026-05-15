# pin 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현

## 요구사항

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-PIN-1 | 핀 등록 (그룹 스코프, tag 필수) | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — 챗봇 자동 등록 경로 (`PinService.registerFromInstagram/registerFromSelection`, tag=PLACE 고정) |
| FR-PIN-2 | 동일 group_id + instagram_url 중복 방지 (UNIQUE) | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — DB `uq_pin_group_instagram` + `DataIntegrityViolationException` catch + `PLC_DUPLICATE_PIN` 응답 |
| FR-PIN-3 | 핀 목록 조회 (그룹별, tag 필터 옵션) | ⬜ | 후속 (웹 API) |
| FR-PIN-4 | 핀 수정 (memo, tag 변경 가능) | ⬜ | 후속 (웹 API) |
| FR-PIN-5 | 핀 삭제 (활성 GroupMember만) | ⬜ | 후속 (웹 API) |
| ~~FR-PIN-X~~ | ~~방문 인증 토글~~ — **제거됨** | — | |

## 후속 작업

- **웹 API Phase**: FR-PIN-3/4/5 (목록/수정/삭제) + 활성 GroupMember 권한 검사
