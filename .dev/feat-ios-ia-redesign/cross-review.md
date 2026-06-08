# Cross-Review 결과 — C (맵/필터 정리)

- advisor: claude (오케스트레이터 직접 — oh-my-gx 읽기 전용 에이전트 산출물 미반환)
- 브랜치: feat/ios-ia-redesign (base: develop / 실제 비교 base: origin/develop=11afd42, merge-base 2715daa)
- DEV_DIR: .dev/feat-ios-ia-redesign
- 검증 대상: C-only diff(ios) 4파일 340줄 — MapView.swift, MapViewModel.swift, TagFilterBar.swift, MapViewModelTests.swift
- 미션: 산출물(PRD AC / 설계 변경범위) 약속 대비 충실도 + 신규 위험 (코드 품질 일반 리뷰 아님)

## AC 충족 매트릭스

| AC | 충족 | 근거 |
|----|------|------|
| AC-C1 상단 필터/범례 · 좌하단 어디가지 FAB 단독 | O | MapView `mapFilterRow`(390) 상단 노출(85, .loaded 게이트), 좌하단 클러스터 `rouletteFAB` 단독(372–381) |
| AC-C2 팝업 아래 펼침 · 그룹 오버레이/릴스 배너 겹침 없음 | O(코드) / DoD-B(시각) | TagFilterBar 팝업 `.topTrailing` + `offset(y:44+8)`(72,167); 상단 VStack(spacing:10) 그룹행/필터행 분리(81–88). 릴스 배너 시각 겹침은 self-check W2(Mac) |
| AC-C3 상호배타 · 바깥탭 닫힘 · 필터 토글/주황점 동일 | O | `activeCornerPopup` 바인딩·바깥탭 catcher 무변경, `TagFilterButton` 토글/주황점 로직 무변경 |
| AC-C4 전면 스피너 없이 줌아웃→줌인 + 핀 교체 | O | `switchTo` `.loading` 미설정, `zoomOutForSwitch`→`zoomInForSwitch`, 핀 원자 교체(307–331) |
| AC-C5 기존 기능 무손상 | O | `load()`/`applyInitialCamera()` 불변. **추가**: 리뷰서 발견된 `load(groupId:)` 결손(컴파일 에러) 수정으로 실제 무손상 달성. `selectedPinId=nil`(전환 시) 안전성 개선 |

[Must] AC-C1~C5 충족(C2 시각은 DoD-B). [Could] FR-C5(권한 거부 시 핀 bounds 줌인) 구현됨.

## 설계 범위 이탈

- **이탈 1건 (정당)**: `MapViewModel.load(groupId:)` 신규 오버로드 추가.
  - 변경 요약: MapView.task/재시도가 호출하던 `load(groupId:)`가 MapViewModel에 미정의 → 컴파일 에러(iOS CI red). 오버로드 추가로 해소.
  - 이탈 사유: 설계 "변경 범위"는 switchTo+헬퍼+상수만 명시했고 load(groupId:)는 없었음. **그러나 이는 골격 A(#106)에서 유입된 기존 컴파일 결함**이며, C의 PR CI를 green으로 만들려면 불가피한 수정. → **정당**.
- 재진입(reentrancy) 가드(switchTo/zoomInForSwitch)는 switchTo 범위 내 보강 → 이탈 아님.
- MapViewModelTests.swift 변경은 설계 "테스트" 섹션에 명시 → 이탈 아님.

## 신규 위험

(trust-ledger.md / self-check.md에 이미 있는 W1·W2·Info(zoom 수치)는 중복 제외)

### Critical / Warning
- 없음.

### Info
- [GAP] `load(groupId:)`에 재진입 가드 없음 — MapView.task(`.idle` 게이트, 진입 1회)와 재시도 버튼에서만 호출되어 switchTo와 동시 실행 경로가 없음(switchGroup은 이미 레벨1, task 종료 후). **저영향**. 향후 동일 화면에서 load(groupId:)가 비동기 중 재호출되는 시나리오가 생기면 가드 고려.
- [ASSUMPTION] `zoomInForSwitch` granted 분기의 `applyInitialCamera`는 "내 위치"(그룹 무관)라 stale 덮어쓰기를 무해로 판단해 가드를 생략. 다중 그룹 빠른 전환 + 위치 허용 환경에서도 카메라가 동일한 내 위치로 가므로 그룹 불일치 결과는 발생하지 않음.

## references 위반
- 해당 없음 (references/ 디렉토리 없음).

## 총평
- 강점: PR #107 봇 리뷰의 [Critical](컴파일 결손)·[High](재진입 레이스)를 정확히 반영. AC-C1~C5 충족. switchTo/load(groupId:) 단위 테스트 7건으로 회귀 가드.
- 합산: Critical 0 · Warning 0 · Info 2.
- 권고: iOS CI(빌드+시뮬 단위테스트) green 확인 후 리뷰어 머지. 시각(W2)·줌아웃 수치는 Mac DoD-B.

## 처리 결과
- 모든 항목 건너뛰기(기록만, 사용자 선택). 조치 필수 항목 없음 — 설계 이탈 1건은 정당(기존 컴파일 결함 수정), Info 2건은 실 레이스 경로 없는 저영향.
- 봇 리뷰 Critical/High는 commit cdfd901로 이미 반영 완료.
