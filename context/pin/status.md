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
| FR-PIN-3 | 핀 목록 조회 (그룹별, tag 필터 옵션) | ✅ | [#9](https://github.com/rnqhstmd/wherewego/pull/9) — `PinService.listGroupPins` + `GET /api/v1/groups/{groupId}/pins?tag=` + Next.js `/pins` UI |
| FR-PIN-4 | 핀 수정 (memo, tag 변경 가능) | ✅ | [#9](https://github.com/rnqhstmd/wherewego/pull/9) — `PinService.updatePin` + `PATCH .../pins/{pinId}` (JsonNode 부분 수정, 빈 메모 잠금 해제, PESSIMISTIC_WRITE) |
| FR-PIN-5 | 핀 삭제 (활성 GroupMember만) | ✅ | [#9](https://github.com/rnqhstmd/wherewego/pull/9) — `PinService.softDeletePin` + `DELETE .../pins/{pinId}` (204, BaseEntity.delete 멱등, 등록자 무관) |
| FR-PIN-6 | 핀 직접 등록 웹 API (검색 결과 또는 십자선 좌표 + tag 선택 + 메모) | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `POST /api/v1/groups/{groupId}/pins` (`PinService.addPin` + `Pin.createFromUser` + `PinCreateCommand`). `@Valid` Bean Validation + `toCommand()` 이중 검증, UNIQUE 충돌 → `PLC_DUPLICATE_PIN` 변환, `requireActiveMembership` 권한 검증 |
| ~~FR-PIN-X~~ | ~~방문 인증 토글~~ — **제거됨** | — | |

## 후속 작업

- **Phase 2.6**: 핀 추가 시 `instagramUrl` 명시 입력 UI (현재 웹 등록은 `instagramUrl=null`이라 UNIQUE 중복 차단 미발동)
- **Phase 2.6**: 페이지네이션 (핀 1k+ 도달 시 검토. 현재 MVP 200건 규모로 미적용)
- 장소 정보(`place_name`, `address`, 좌표) 수정 — 후속 미정
- 삭제된 핀 복원 — 후속 미정
