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
| FR-PIN-4 | 핀 수정 (memo, tag, placeName, address 변경 가능) | ✅ | [#9](https://github.com/rnqhstmd/wherewego/pull/9), [#21](https://github.com/rnqhstmd/wherewego/pull/21) — `PinService.updatePin` + `PATCH .../pins/{pinId}` (JsonNode 부분 수정, 빈 메모 잠금 해제, PESSIMISTIC_WRITE). Phase 2.8에서 `PinUpdateCommand` 4→8 필드 확장 + placeName(1~200자 필수)/address(≤500자, 빈 문자열은 미변경 정규화) 분기 추가 |
| FR-PIN-5 | 핀 삭제 (활성 GroupMember만) | ✅ | [#9](https://github.com/rnqhstmd/wherewego/pull/9) — `PinService.softDeletePin` + `DELETE .../pins/{pinId}` (204, BaseEntity.delete 멱등, 등록자 무관) |
| FR-PIN-6 | 핀 직접 등록 웹 API (검색 결과 또는 십자선 좌표 + tag 선택 + 메모) | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `POST /api/v1/groups/{groupId}/pins` (`PinService.addPin` + `Pin.createFromUser` + `PinCreateCommand`). `@Valid` Bean Validation + `toCommand()` 이중 검증, UNIQUE 충돌 → `PLC_DUPLICATE_PIN` 변환, `requireActiveMembership` 권한 검증 |
| ~~FR-PIN-X~~ | ~~방문 인증 토글~~ — **제거됨** | — | |

## 후속 작업

- **Phase 2.8 완료**: 웹 등록 시 `instagramUrl` 명시 입력 UI (`MemoTagPanelContent` 공통, 검색·picker 양 경로 자동 커버). 클라이언트 `https://` 시작 검증 + 백엔드 `Pin.validateInstagramUrl` 양방향 보안 검증 + `PinCard.tsx` 조건부 href — [#21](https://github.com/rnqhstmd/wherewego/pull/21)
- **Phase 2.8 완료**: 핀 장소 정보(`place_name`, `address`) 텍스트 수정 — `PinUpdateCommand` 확장 + `PinEditDialog` 장소명/주소 편집 필드 (순서: 장소명 → 주소 → 태그 → 메모) + `PinListClient.applyPatch` 낙관적 반영 — [#21](https://github.com/rnqhstmd/wherewego/pull/21)
- **Phase 2.8 완료**: map ⋮ 메뉴 삭제 액션 — `PinPopup` footer에 HLine + 우측 정렬 텍스트 버튼, `PinDeleteConfirm` 재사용, `useOptimistic` reducer 일반화(`patch|remove`)로 마커 즉시 제거 + 실패 시 자동 롤백 — [#21](https://github.com/rnqhstmd/wherewego/pull/21)
- **별도 Phase 예정**: 핀 장소 좌표 수정 (지도 picker 재사용), 삭제 핀 복원 기능 — Phase 2.8 범위에서 제외
- **Phase 2.9 조건부**: 핀 페이지네이션 (1k+ 도달 시 도입)
