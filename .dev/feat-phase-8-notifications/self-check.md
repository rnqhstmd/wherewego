# Phase 8 자기점검 결과

## 종합
- Critical: 0건
- Warning/Info: 4건 (phase-review로 이월)
- QUESTION: 3건 (phase-review로 이월)
- AC-1 ~ AC-22 모두 구현 확인

## Warning/Info (phase-review로 이월)

### [Warning] NotificationService.java:204-211 [MAINT] - N+1 개별 조회
`loadPinsByIds`에서 `PinRepository.findById`를 핀 개수만큼 반복 호출. MVP 2인에서는 무관하지만, 향후 부담 증가 시 `findAllById(pinIds)` 배치 조회로 교체 필요.

### [Warning] useNotifications.ts:104-161 [MAINT] - SSE effect 의존성
`useEffect(..., [markAllRead])` + `markAllRead`는 `useCallback(..., [])` → 현재 마운트 1회만 실행. 향후 `markAllRead` 의존성 추가 시 SSE 재연결 위험. 장기적으로 SSE effect 분리 또는 useRef 래핑 고려.

### [Info] NotificationToast.tsx:43 [SPEC] - 조사 처리 고정
`"${firstPlaceName}을 저장했어요"` - 받침 없는 단어("홍대입구")는 "를"이 맞음. PRD FR-15에 미명시. UX 품질 차원에서 조사 로직 추가 권장.

### [Info] NotificationItem.tsx:19-21 - 동일 조사 이슈

### [Info] NotificationPanel.tsx:133-140 [MAINT] - 로딩 스피너 미표시
`activeDetail ? (loading ? spinner : list) : items` 구조에서 loading=true 동안 activeDetail은 아직 null이라 스피너 미표시. 수정안: `loading ? spinner : activeDetail ? list : items`.

## QUESTION (사용자 확인 필요)

### Q1. SSE payload registeredBy 미포함
`useNotifications.ts:116`에서 `registeredBy: 0` 하드코딩. 목록 fetch 시 실제 값으로 덮어씀. UI 미노출이므로 무관 가능. 의도 확인 필요.

### Q2. sseClient onopen + connected 이벤트 이중 핸들러
양쪽 모두에서 `retryCount=0; setState("open")` 호출. React 배칭으로 단일 렌더 처리되지만 의도 확인 필요.

### Q3. 패널 open 직후 SSE 수신 시 말풍선 노출 가능성
`isPanelOpenRef`는 useEffect로 동기화되므로 state 변경 직후 다음 effect 전까지 ref가 stale일 수 있음. 극히 드문 타이밍으로 허용 가능. 확인 필요.

## AC-1 ~ AC-22 매핑 확인

22개 수용 기준 모두 구현 위치 확인 완료. 상세는 자기점검 출력 표 참조.

## 잘 구현된 점

1. **트랜잭션 격리(BR-3)**: 모든 알림 트리거가 try-catch로 격리. AFTER_COMMIT 이벤트 리스너로 트랜잭션 + SSE push 타이밍 정확.
2. **챗봇 4경로 완전 커버**: handleCandidates + handleLegacySingle + handleGoogleFallback 3곳 트리거로 autoSaveOnExpiry / autoSavePreviousImmediately까지 자동 커버.
