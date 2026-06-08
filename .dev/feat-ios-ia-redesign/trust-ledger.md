# Trust Ledger — C (맵/필터 정리)

> QA·security-auditor(읽기 전용) 산출물 미반환 → 오케스트레이터 직접 수행. 변경: ios/ Swift 3 + 테스트 1. 빌드/시뮬은 Mac DoD-B.

## Mechanical Gate
- iOS = Windows 빌드/시뮬/단위테스트 불가(문서화 제약). 백엔드 변경 없음(스테이징 전부 ios/) → Windows 빌드 게이트 대상 없음. **빌드·MapViewModelTests 실행 = Mac DoD-B 이월**.

## QA 리뷰 (스펙 충족 — 직접)
- CERTAIN(Critical): **0건**. AC-C1~C5 정합(self-check.md 참조).
- Warning: 2건
  - W1 switchTo 2단 카메라 병합 가능성 → **해소(코드 반영)**: 줌아웃 발행 시 `Task.sleep(switchZoomOutHoldNanos=120ms)`로 소비 보장. 최종 수치는 Mac DoD-B 미세조정.
  - W2 reelFocusBanner vs 상단 그룹/필터 행 시각 겹침 — 골격부터의 기존 DoD-B 수치 보정. C가 악화 안 함(필터 행=그룹 행 아래). **Mac 시각 확인 이월**(padding 실측 필요, blind 수정 안 함).
- Info: 1건 — switchOverviewZoom=10 수치 미세조정 DoD-B.
- QUESTION: 0건.

## 통합 감사 (security/policy/허점 — 직접)
- CRITICAL 0 · HIGH 0 · MEDIUM 0.
- 근거:
  - 신규 엔드포인트/인증/시크릿/사용자 입력 파싱 없음. switchTo는 기존 `pinAPI.list(groupId:)` 재사용(권한은 백엔드 강제, 클라 변경 없음).
  - 필터/범례 이동은 순수 SwiftUI 레이아웃 — 데이터/권한 경로 무관.
  - fitBoundsCommand=markers: 서버 핀 좌표 기반 기존 메커니즘 재사용, 주입 표면 없음.
  - selectedPinId=nil(전환 시): 구 그룹 좀비 말풍선/재탭 가드 오동작 제거 — 안전성 개선.
- 누락 시나리오 점검: 같은 그룹 no-op(가드 유지), fetch 실패(.error+pins 비움), 위치 거부+핀(bounds)/핀없음(서울 폴백) — 모두 처리·테스트됨.

## 미답변/이월
- 없음(QUESTION 0). DoD-B 항목(W1/W2/Info)은 Mac 검증 단계로 명시 이월.
