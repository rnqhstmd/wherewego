# Trust Ledger — Phase 8 인앱 알림함

## 통합 감사 결과 (2회차 완료)

### 최종 상태
- **Critical: 0건**
- **HIGH: 0건** (1회차 3건 모두 해소)
- **MEDIUM: 4건** (수용 또는 Trust Ledger 기록)
- AC-1 ~ AC-22 모두 충족 확인

### 1회차에서 수정된 항목

#### CRITICAL (해소 완료)
- [QA] NotificationPin + V007 DDL 컬럼 불일치 (BaseEntity created/updated/deleted_at) → V007에 deleted_at + notification_pins.updated_at/deleted_at 추가
- [QA] NotificationJpaRepository.markAllRead @Modifying에 clearAutomatically=true + JPQL에 updatedAt 갱신 → 적용 + Adapter에서 Instant→ZonedDateTime 변환

#### HIGH (해소 완료)
- [ZT RISK] SSE 연결 수 제한 없음 (DoS) → MAX_EMITTERS_PER_USER=10 가드 추가
- [ZT GAP] 패널 열림 상태 알림 shownToastIds 미등록 (AC-16+엣지8 교차) → 등록 위치 일원화
- [ZT GAP] autoSavePreviousImmediately 알림 누락 경로 → 코드 추적: inner try-catch + runBackgroundAutoSave 안전망 확인됨 (변경 불필요)
- [ZT ASSUMPTION] @TransactionalEventListener 비동기 여부 명문화 → 동기 호출, MVP 2인 수용

#### Warning (해소 완료)
- [QA] markAllRead updatedAt 갱신 누락 → JPQL 확장 완료
- [QA] NotificationSseRegistry.broadcastHeartbeat completeWithError 누락 → 추가 완료
- [QA] NotificationPanel 로딩 스피너 순서 역전 → loading 분기 outer로 이동
- [QA] SSE effect markAllRead deps 취약성 → 현재 무관, useRef 패턴 후속

### QUESTION 답변 반영 (모두 완료)
- Q1 (registeredBy 0 하드코딩) → 타입 `number | null`, SSE prepend 시 null
- Q2 (sseClient onopen+connected 이중 핸들러) → 의도된 이중 (변경 없음)
- Q3 (패널 open 직후 ref stale) → 현 설계 유지 (known limitation)
- ZT Q1 (SSE BFF 프록시 경유) → BFF SSE 전용 라우트 신규 (`frontend/src/app/api/v1/notifications/stream/route.ts`)

### 2회차에서 수정된 항목

#### Warning + MEDIUM (해소 완료)
- [QA] BFF SSE route `duplex: 'half'` + `@ts-ignore` 불필요 → 제거
- [QA] BFF SSE route 클라이언트 disconnect 시 upstream 즉시 종료 안 됨 → `signal: request.signal` 추가
- [ZT RISK] 401 body-null EventSource 재연결 루프 → sseClient preflightAuth + BFF `Connection: close` 헤더 + 즉시 failed 상태

### 2회차 MEDIUM (수용 / 후속)
- [ZT RISK/MEDIUM] MAX_EMITTERS_PER_USER TOCTOU race (size 체크와 add 사이) → MVP 2인에서 일시적 11-12개 도달도 5분 타임아웃으로 자동 정리. 수용.
- [ZT GAP/MEDIUM] shownToastIds 무제한 증가 → MVP에서 미미한 메모리 사용. 페이지네이션 후속 도입 시 LRU cap 검토.
- [ZT GAP/MEDIUM] Transfer-Encoding chunked 명시 안 함 → Node.js fetch 런타임 자동 처리. 운영 확인 필요.
- [ZT ASSUMPTION/MEDIUM] `upstream.ok` 2xx 전체를 정상 간주 → 백엔드는 200만 반환, 실질 위험 없음.

### 보류 / 후속
- [ZT MEDIUM] registeredBy 응답 노출 → 현재 미사용. 후속에서 제거 검토
- [ZT GAP] 조사 처리 고정 (을/를) → 자연어 품질, UX 후속
- [ZT GAP] notification_pins.pin_id ON DELETE 정책 → soft delete 가정. ADR 후속 기록
- [QA Warning] SSE effect markAllRead deps → 현재 무관, useRef 후속
- [QA Info] NotificationPanel.loadDetail 실패 에러 처리 → 후속

### 잘 구현된 점
- 트랜잭션 격리(BR-3) 정합: Controller try-catch + NotificationService @Transactional + AFTER_COMMIT 패턴
- 4 챗봇 경로 자동 커버: 3곳 트리거로 handleCandidates/handleLegacySingle/handleGoogleFallback + autoSaveOnExpiry/autoSavePreviousImmediately 흡수
- BR-3, BR-4, BR-5, BR-6, BR-7 + FR-5, FR-6, FR-9, FR-10, FR-12 정합 검증
- AC-1 ~ AC-22 모두 충족
- SSE 인프라: ConcurrentHashMap + CopyOnWriteArrayList 동시성 안전, AFTER_COMMIT DB 일관성 보장, 5분 타임아웃 + 30초 heartbeat
- BFF SSE 전용 라우트: Node runtime streaming, request.signal 연동, preflight auth, X-Accel-Buffering

### 최종 권장
phase-complete 진행 가능. 모든 Critical/HIGH 해소, MEDIUM 항목은 MVP 수용 가능 + Trust Ledger 기록 완료.
