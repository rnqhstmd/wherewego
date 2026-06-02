# 자기점검 결과

## CERTAIN (자동 처리)

### [Warning] PinJpaRepository.findActiveByGroupPlaceNear LIMIT 1 누락 → 자동 수정 완료
- 변경: `Pageable pageable` 추가 + `PinRepositoryImpl`에서 `PageRequest.of(0, 1)` 전달.
- 컴파일 재검증 완료.

## SELF_CHECK_FINDINGS (phase-review 이월)

### [Critical/Out-of-scope] InstagramLinkHandler:499-507 handleGoogleFallback 기존 dead code
- 본 PR 변경 전부터 존재한 broken 분기 (`ctx.remaining() < threshold` else 분기).
- `runSync()`를 다시 호출하면서 결과 무시 + `assert jobCtx != null` 무의미.
- 이번 변경 범위 외이므로 별도 PR로 분리 권장.

### [Warning/PERF] idx_pins_group_location 인덱스 활용
- 인덱스 `(group_id, latitude, longitude)`는 placeName 컬럼이 없어 플래너가 부분 활용.
- 현재 그룹당 핀 수가 적어 영향 적음. 대량 확장 시 `(group_id, place_name)` 복합 인덱스 검토.

## SELF_CHECK_QUESTIONS (사용자 확인 필요)

1. handleGoogleFallback dead code 동시 정리 여부
2. PlaceSelectionHandler(후보 카드 선택 경로)에도 좌표+이름 dedup 적용 여부
3. 이미 저장된 핀에 사용자 메모가 함께 도착했을 때 메모 갱신 여부
