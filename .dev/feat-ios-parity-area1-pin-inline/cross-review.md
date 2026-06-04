# Cross-Review 결과

- advisor: claude (qa-manager + security-auditor, cross-review 미션)
- 브랜치: feat/ios-parity-area1-pin-inline (base: develop)
- DEV_DIR: .dev/feat-ios-parity-area1-pin-inline
- 실행: 2026-06-04 (phase-review 9건 수정 후 독립 검증)

## AC 충족 매트릭스
**AC-1~16 + 보강 AC-17~20 전항 충족 (20/20).** qa-manager가 각 AC → 코드 위치(파일:라인) 1:1 대조 완료.
- [Must] 핵심: AC-1(enterAddPin)·AC-2(CrosshairOverlay allowsHitTesting)·AC-3(InlineAddPlaceCard)·AC-4(.sheet 제거)·AC-5/6(cameraIdle→onMapMoved 재사용)·AC-7(createPin+견고화 종료)·AC-8/9/10(검색)·AC-11(isAddingPin)·AC-12(탭전환 종료)·AC-13(markerTapped 차단)·AC-14(탭바 패딩)·AC-15(AddPlaceSheet 삭제)·AC-16(줌 분기)
- 보강: AC-17(프로그래매틱 idle 스킵)·AC-18(seed 1회+userDragged 가드)·AC-19(createTask/Debouncer 취소)·AC-20(mapZoom 시드+가정값)

## 설계 범위 이탈
**이탈 없음.** 변경 파일 전부 design.md "변경 범위"에 포함. `EmptyMapCard.swift`는 주석 1줄(콜백 doc) — 설계 의도와 일치, 코드맵 등재.

## trust-ledger 9건 수정 코드 반영
**9건 전부 코드 반영 확인** (qa + security 교차):
clusterTapped guard ✓ / cancelPendingWork isCreating=false ✓ / entryZoomTask 저장·취소 ✓ / applyInitialCamera mapCenter 시드 ✓ / 주석 2건 ✓ / addVM.selectResult ✓ / onChange 제거+performCreate 직접 종료 ✓ / 테스트명 ✓

## 신규 위험

### HIGH (동일 1곳, 기능 버그 없음 — 명세 대칭/명시성)
- [GAP] `AddPlaceViewModel.performCreate` 성공 경로 `mapViewModel.exitAddPin()` **직전 `guard !Task.isCancelled` 누락**. `appendPin` 직전·catch 3곳엔 가드 있으나 `exitAddPin` 앞만 비대칭(AC-19 "appendPin/flyTo/didCreate 직전 가드" 의도 부분 미이행). @MainActor 동기 연속 실행(중간 await 없음)이라 취소 플래그가 새로 set될 수 없어 **실제 버그는 발생하지 않음**. 단 명시성/대칭 결여.
  - qa 권고: `exitAddPin()` 직후 `return` 추가(defer 낙하 전 종료, self 수명 명확).
  - security 권고: `exitAddPin()` 직전 `guard !Task.isCancelled else { return }` 추가(외부 종료 시 중복 회피, AC-19 대칭).
  - 종합: 직전 가드 + 직후 return + 수명 추론 주석.

### MEDIUM/ASSUMPTION (근본 문제 없음 — 분석 기록)
- clusterTapped 비-인라인 정상 탭 미차단 확인 / entryZoomTask cancel·재할당 race 없음(@MainActor 직렬) / applyInitialCamera mapCenter 조건부 시드 기존 흐름 무영향 / 자기취소·자기참조 use-after-free 미발생(@MainActor strong 유지) / withTaskGroup try? 타임아웃 안전.

## references 위반
references/ 없음 — 해당 없음.

## 총평
- 강점: trust-ledger 9건 정확 반영 + AC 20/20 + 견고화(performCreate→exitAddPin)가 MUST-1(pendingProgrammaticIdle)과 정합되게 닫힘. 보안 취약점 0.
- 합산: Critical 0, HIGH 2(동일 1곳, 기능무해), MEDIUM 3(근본문제 없음), ASSUMPTION 2.
- 권고: `performCreate` exitAddPin 직전 가드 + 직후 return 1곳만 적용하면 명세 완전 이행.

## 처리 결과
- ✅ 적용: `AddPlaceViewModel.performCreate` 성공 경로 `exitAddPin()` 직전 `guard !Task.isCancelled else { return }` + 직후 `return` + 수명 추론 주석(AC-19 명세 완전 대칭).
- MEDIUM/ASSUMPTION: 근본 문제 없음 확인 — 수정 불필요(분석 기록).
