# Trust Ledger — Phase 10 (장소 방문 감지)

- 작성일: 2026-05-24
- 감사 라운드: phase-review 1회차
- 합산 원본: qa-manager (통합 리뷰) + security-auditor (ZeroTrust 통합 감사)

## 통합 감사 (review)

### CRITICAL / Critical(QA)
**0건.**

### HIGH (security-auditor)

- [HIGH/인증-인가] `NotificationService.createForVisitDetected` 내부 멤버십 재검증 부재
  - 근거: 컨트롤러에서 `requireActiveMembership` 통과한 userId만 전달되므로 정상 경로 위험 없음. 그러나 메서드 시그니처 자체는 비멤버 ID 허용. 외부 직접 호출 경로 또는 향후 호출자 추가 시 위험 가능.
  - 권고: `createForVisitDetected` 메서드 JavaDoc에 "호출자가 멤버십을 사전 검증해야 한다"는 계약을 명시. 또는 내부에서 `findActiveByGroupIdAndUserId` 사전 검증 추가.

- [HIGH/알림-팬아웃] `NotificationVisitWriter` REQUIRES_NEW + DataIntegrityViolationException 이중 catch
  - 근거: ZT는 "REQUIRES_NEW 안에서 예외 catch 시 UnexpectedRollbackException 발생" 경고. **검증 결과: 실제 위험 없음**. Spring REQUIRES_NEW는 자기 트랜잭션 자동 rollback 후 예외 그대로 propagate (REQUIRED 부모-자식 중첩에서만 UnexpectedRollbackException 발생). 게다가 B1 IT (b) race-free 케이스가 실측 PASS로 검증됨.
  - 권고: 코드 변경 불필요. 감사 종결 (INFO 수준 재분류).

- [HIGH/데이터-무결성] `visit_pin_id` FK ON DELETE 정책 미명시 (묵시적 RESTRICT)
  - 근거: V009에 ON DELETE/ON UPDATE 절 없음. PostgreSQL 기본은 RESTRICT. pins는 soft-delete 정책이라 실제 영향 없음. 운영 DB에서 hard DELETE 실수 또는 운영 정책 변경 시 FK 위반 발생 가능.
  - 권고: V009 마이그레이션에 `ON DELETE RESTRICT` 또는 `ON DELETE SET NULL`을 명시하여 의도 코드화. **간단 보강 권장**.

### MEDIUM (security-auditor)

- [MEDIUM/입력검증] UpdatePinRequest memo 500자 검증이 DTO 레이어가 아닌 PinUpdateCommand 팩토리에서만 수행 (CreatePinRequest와 불일치). 실질 통과 위험 없음. 기록만.
- [MEDIUM/개인정보] `handleVisitConfirm` 실패 시 `console.error`에 `groupId, pinId` 노출. 운영 빌드에서는 code만 남기는 게 안전.
- [MEDIUM/DoS] `shownPinIdsRef` Set 누적 상한 없음. MVP 핀 수백 개 수준이라 실질 위험 없음.
- [MEDIUM/트랜잭션] `writeOne` 반환 타입 설계서(`boolean`) vs 구현(`void`) 불일치. 구현이 더 안전한 패턴 — 설계서 동기화 권장.
- [MEDIUM/XSS] React JSX 자동 escape 활용 확인. dangerouslySetInnerHTML 미사용. **안전**.
- [MEDIUM/CSRF] JWT stateless API + csrf.disable 기존 정책 유지. **안전**.

### Critical 외 QA Warning (자기점검 인계)

- [Warning/SPEC] **MapClient.tsx:1044~1047 — AC-VD-14 미충족**. 1차 PATCH 실패 시 `console.error`만, 인라인 에러 토스트 UI 없음. **사용자 결정 필요**.
- [Warning/MAINT] NotificationVisitWriter `visit_pin_id`와 `notification_pins` 이중 저장. 의도된 분담이나 코드 주석으로 역할 명시 권장.
- [Warning/PERF] `runMarkerBounceAndConfetti` 600ms setTimeout 안 detached node 조작 위험. 가드 있어 실제 위험 낮음.

### QUESTION (QA + 자기점검 인계, 사용자 확인 필요)

- [QUESTION-Q1] **AC-VD-14 처리 방향**: (a) 인라인 에러 토스트 추가 구현 / (b) 현 상태 Accept (스펙 완화) / (c) 별도 이슈 분리.
- [QUESTION-Q2] NotificationPinList.tsx 동사 분기 "{actor} 다녀온/저장한 N곳" 텍스트 확인.
- [QUESTION-Q3] Controller IT 3건 (c/d/g) 본 PR 포함 여부.
- [QUESTION-Q4] useVisitDetection accuracy 불량 시 firstEnterAt 보존 정책 확인 → BR-VD-3 명시 해석에 부합 (의도된 동작 확률 높음).
- [QUESTION-Q5] (e)/(f) 테스트 실효성. (a) 현재 Accept / (b) PinServiceIT에 wasWishOrReelToMemory 단위 검증 추가.
- [QUESTION-Q6] visit-memo 시트 × 닫기 시 UX 피드백 없음. (a) 의도된 동작 / (b) 짧은 성공 토스트 추가.
- [QUESTION-Q7] writeOne void vs boolean 시그니처 — 실제 구현(void + caller-catch)이 더 안전, 설계서 동기화 권장.

### ZT INFO (사용자 확인 필요)

- [INFO/알림] **VISIT_DETECTED 본인에게도 알림 발송 (PRD FR-VD-27과 미묘 불일치)**: PRD는 "다른 활성 멤버에게 fan-out" 명시인데 구현은 `receiverIds.add(registeredBy)`로 본인도 추가. 의도된 변경인지 확인 필요. **사용자 결정**.

### LOW / INFO (참고만)

- [INFO] V009 부분 UNIQUE 인덱스 NULL 처리 PostgreSQL 표준 부합 확인.
- [INFO] JWT 인증 + @AuthUser 강제 확인.
- [INFO] `log.warn` stack trace 노출은 서버 로그 수준 (외부 노출 X).
- [Info] NotificationService.loadPinsByIds N 쿼리 (인지된 부채).
- [Info] VisitMemoSheet 날짜 `fonts.mono` (디자인 의도 가능).

---

## AC 충족 결과

| 항목 | 수 | 비율 |
|------|----|------|
| ✅ 충족 | 20 | 91% |
| ⚠️ 부분 충족 | 1 (AC-VD-18: 코드 OK, Controller IT 미작성) | 4% |
| ❌ 미충족 | 1 (AC-VD-14) | 4% |
