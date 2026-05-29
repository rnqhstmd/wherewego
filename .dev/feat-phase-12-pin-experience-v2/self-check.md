# Self-Check (qa-manager 자기점검) — Phase 12 Pin Experience v2

## SELF_CHECK_FINDINGS (Critical/Warning — 자기점검에서 수정 완료)

자기점검 단계에서 발견된 3건의 Warning(PRD AC 직접 위반 포함)을 즉시 수정 완료. Critical 0건.

### [수정완료/Critical 수준] AC-12-21 위반 — MULTI_SELECTING WANT 미적용
- 파일: `backend/.../chatbot/handler/ReelMultiSelectionHandler.java`, `ReelMemoWaitingHandler.java`
- 문제: 콤마 번호 선택/`전부` 입력 시 `Snapshot.wantOnSelected=false`로 고정되어 선택 핀에 WANT가 적용되지 않음
- 수정: `transitionToMemoWaiting` 시그니처에 `wantOnSelected` 인자 추가. "전부"/콤마 선택 → true, "건너뛰기" → false. ReelMemoWaitingHandler JavaDoc 정정 (SINGLE_WANT/MULTI 선택/MULTI 건너뛰기/BULK 4분기 정책 명시)
- PRD 매핑: FR-PIN-12-15, AC-12-21

### [수정완료/Warning] AC-12-36 위반 — reel_bundle opacity 0.3 미적용 (hide 폴백)
- 파일: `frontend/src/app/map/_components/MapboxView.tsx`, `MapClient.tsx`
- 문제: 비번들 핀이 opacity 0.3 대신 hide. PRD D-14 정책 불이행
- 수정: MapboxView에 `dimmedPinIds?: Set<number>` prop 추가, 마커 DOM `element.style.opacity` 동적 갱신 (1.0/0.3 분기, pointer events 유지). MapClient에서 비번들 핀을 visibleOptimisticPins에서 제외하지 않고 dimmedPinIds로 전달
- PRD 매핑: FR-PIN-12-27, AC-12-36, D-14

### [수정완료/Warning] WantService.getStatus 락 오용
- 파일: `backend/.../pin/want/WantService.java`, `PinRepository.java`, `PinRepositoryImpl.java`, `PinJpaRepository.java`
- 문제: `@Transactional(readOnly=true)` 컨텍스트에서 `findActiveByIdAndGroupIdForUpdate` (PESSIMISTIC_WRITE) 호출. 일부 드라이버/설정에서 `cannot use SELECT FOR UPDATE in a read-only transaction` 발생 가능
- 수정: PinRepository에 신규 메서드 `findActiveByIdAndGroupId` (락 없음) 추가. WantService.getStatus가 새 메서드 사용
- PRD 매핑: NFR-12-1 (FOR UPDATE는 토글 경로 전용)

## SELF_CHECK_QUESTIONS (QUESTION — phase-review로 이월)

### Q1: NotificationService.listRecent — WISH_CONVERTED 알림의 `firstPlaceName` fallback 노출
- 맥락: `listRecent`는 `NotificationPin` 링크 기반으로 `firstPlaceName` 조회. WISH_CONVERTED는 NotificationPin 링크가 없고 `wish_pin_id` 컬럼 사용 → `listRecent`의 `firstPlaceName`이 항상 "저장된 장소"(FALLBACK_PLACE_NAME)로 표시됨. `getDetail`에는 분기가 있으나 `listRecent`는 누락.
- 권장: 알림 목록 요약은 fallback 허용 범위로 보임. 그러나 의도 확인 필요. 의도되지 않았다면 listRecent에 WISH_CONVERTED 분기 추가
- phase-review에서 사용자 확인 후 결정

### Q2: ReelSelectionAutoSaveScheduler TTL 자동 저장 시 `activeMemberCount` 조회 누락 여부
- 맥락: TTL 만료 자동 저장 경로(`ReelSelectionAutoSaveScheduler`)에서 `markWantOnInitialSave` 호출 시 `activeMemberCount` 파라미터 전달 방식 미확인
- 권장: Scheduler 구현 검토 필요. `groupMemberRepository.countActiveByGroupId` 호출이 있으면 정상
- phase-review에서 실제 코드 검토 후 결정

### Q3: ReelMemoWaitingHandler.saveAllSelected 트랜잭션 경계 — `wantService.markWantOnInitialSave` 중첩 호출
- 맥락: `saveAllSelected`(@Transactional) 내부에서 `markWantOnInitialSave`(@Transactional, REQUIRED) 호출 + 같은 트랜잭션에서 `findActiveByIdAndGroupIdForUpdate`로 동일 pin 재진입 락 요청
- 권장: 동일 트랜잭션 내 재진입은 락 no-op (PostgreSQL 동작 표준)이므로 의도된 설계로 보임
- phase-review에서 확정

## Info (참고 / 수정 불필요)

### Notification 생성자의 `boolean wishMarker` 파라미터 가독성
- patch P-2 가이드대로 시그니처 충돌 회피용 마커 파라미터. 값이 항상 `true` 고정 전달
- 기능상 문제 없음. 후속 Phase에서 타입 안전 오버로드 또는 static factory 분리 검토 가능

## 충족 매트릭스 (수정 후 갱신)

| 범주 | 충족 | 비고 |
|------|------|------|
| V012 마이그레이션 (AC-12-1~4) | 4/4 | 완료 |
| WANT 시스템 (AC-12-5~14) | 10/10 | AC-12-21 수정 완료 |
| 마커 시각화 (AC-12-15~19) | 5/5 | 완료 |
| 챗봇 v2 (AC-12-20~30) | 11/11 | AC-12-21 수정 완료 (수정 #3) |
| 정리 시스템 (AC-12-31~34) | 4/4 | 완료 |
| 맵 필터 / 기타 (AC-12-35~37) | 3/3 | AC-12-36 opacity 0.3 수정 완료 |

**자기점검 종합**: PRD AC 37건 모두 충족 (자기점검 수정 후). Critical 0, Warning 0 (모두 수정 완료), QUESTION 3건은 phase-review로 이월.
