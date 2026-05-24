# 자기점검 결과 (Phase 10)

- 작성일: 2026-05-24
- qa-manager 자동 리뷰. Critical만 자동 수정 대상.

## Critical (CERTAIN)

**0건.**

## Warning (CERTAIN, 자동 수정 비대상 — phase-review로 이월)

- [Warning][SPEC] **MapClient.tsx:1029~1047** — FR-VD-21 / AC-VD-14 "인라인 에러 토스트(시스템 레벨)" 미구현. 현재 1차 PATCH 실패 시 `console.error`만. 사용자가 메모 시트가 안 열리는 이유를 알 수 없음. coder가 의도적 후속 작업으로 남긴 영역. phase-review에서 처리 방향 결정 필요.
- [Warning][MAINT] **NotificationVisitWriter.java:37~39** — `Notification.visitPinId`(부분 UNIQUE 인덱스 키)와 `NotificationPin.link(...)`가 동일 pinId를 두 곳에 저장. getDetail은 notification_pins 경로만 사용 (174:174). 의도된 분담이나 향후 유지보수 혼란 가능 → 코드 주석으로 역할 명시 권장.
- [Warning][PERF] **MapboxView.tsx:118~131** — `runMarkerBounceAndConfetti`의 600ms setTimeout 안에서 마커가 unmount되면 detached node 조작. `parentNode === markerEl` 가드가 이미 있어 실제 위험은 낮음.

## Info

- [Info][MAINT] NotificationService.java:216~223 — `loadPinsByIds` N 쿼리. 주석에 "수신 핀 수 작아 비용 허용"으로 이미 인지된 부채.
- [Info][CLEAN] VisitMemoSheet.tsx:79 — 날짜를 `fonts.mono`로 표시. `fonts.sans`가 더 자연스러울 수 있음.

## Question (phase-review로 이월)

1. **MapClient.tsx:1029~1047 (FR-VD-21)**: 인라인 에러 토스트를 (a) 추가 구현 / (b) 현 상태로 Accept(스펙 일부 완화) / (c) 별도 작업으로 분리.
2. **NotificationPinList.tsx:45 동사 분기**: `"내가 다녀온 1곳"` 또는 `"{nickname}님이 다녀온 1곳"`이 PRD 표현과 일치하는지 확인.
3. **NotificationServiceVisitDetectedIT 미완료 3개 (c/d/g)**: Controller IT 분리를 (a) 본 PR에 추가 / (b) 별도 이슈로 분리.
4. **useVisitDetection.ts:59 정확도 불량 시 firstEnterAt 보존**: 의도된 동작인지 확인 (BR-VD-3는 "타이머에 영향 주지 않음" 명시 → 보존이 맞을 가능성 높음). 정확도 불량이 연속으로 오면 이전 firstEnterAt이 무한 보존되어 정상 콜백 시 즉시 검출되는 부작용 검토.

## AC 체크리스트 결과

22개 수용 기준 중 **21개 충족, 1개 미충족 (AC-VD-14)**.

| AC | 상태 | 비고 |
|----|------|------|
| AC-VD-1~22 | 21/22 | AC-VD-14만 Warning |
| AC-VD-14 | ❌ | console.error만, 인라인 에러 토스트 미구현 |

## 총평

전반적 구현 품질 높음. 트랜잭션 경계 + 부분 UNIQUE race-free + useVisitDetection 차순위 누적 패턴 모두 설계 충실. AC-VD-14만 phase-review에서 결정.
