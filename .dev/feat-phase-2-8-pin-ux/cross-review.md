# Cross-Review 결과 — Phase 2.8

- advisor: codex (GPT-5.4) + PR #21 자동 리뷰 (gemini-code-assist + copilot-pull-request-reviewer)
- 브랜치: feat/phase-2-8-pin-ux (base: develop)
- DEV_DIR: .dev/feat-phase-2-8-pin-ux
- 실행 시각: 2026-05-18

## AC 충족 매트릭스 (codex)

| AC | 충족 | 비고 |
|----|------|------|
| AC-1 ~ AC-16 | ✅ | 코드와 PRD 1:1 매칭 |
| AC-17 | 부분 ⚠️ | 코드 "권한이 없어요" vs PRD "권한이 없습니다" — 친근체 일관성으로 코드 유지 결정 (phase-review에서 확정) |

**총평**: Must 16/17 + Should 2/2 충족. 1건 문구 차이는 PRD가 갱신되어야 정합.

## 설계 범위 이탈 (codex)

8건 모두 정당화된 이탈로 확인:
- 백엔드 테스트 3종 (PinServiceIT/PinUpdateCommandTest/PinV1ControllerIntegrationTest) — phase-review M2/M3 보강
- PinCard.tsx — phase-review H1 XSS 이중 방어
- context 4개 파일 (pin/map architecture/status) — phase-complete Step 3/4 동기화

## PR 리뷰 신규 발견 (9건)

### 실제 버그 (Critical, 처리됨)

**G1/C2 [Pin.java validateInstagramUrl]**
- 발견자: gemini + copilot
- 위치: `Pin.java:161-168`
- 본질: trim 후 검증만 하고 원본 저장 → `" https://..."` 공백 포함 URL이 DB에 저장 → `PinCard.startsWith("https://")` 검사 실패 (링크 미노출) + UNIQUE 우회 가능
- 해소: `validateInstagramUrl(String) -> String` 시그니처로 변경. 3개 팩토리 모두 trim된 값을 entity에 저장. blankToNull 일관성으로 빈 trim은 null 반환

**C1 [PinEditDialog.tsx address 빈 patch 400]**
- 발견자: copilot
- 위치: `PinEditDialog.tsx handleSubmit`
- 본질: 사용자가 기존 address를 다 지우면 `addressChanged=true` + `canSave=true` → 빈 trim으로 patch.address 키 생략 → `{}` 전송 → 백엔드 400 PIN_UPDATE_EMPTY
- 해소: `addressChanged = trimmedAddress.length > 0 && trimmedAddress !== initialAddress.trim()`로 변경. 빈 입력은 changed=false로 정규화

### Warning (정규화, 처리됨)

**C3 [MapClient.tsx deleteErrorByPinId 누수]**
- 발견자: copilot
- 위치: `MapClient.tsx handleConfirmDelete` 성공 분기
- 본질: 성공 시 setPins로 제거하지만 deleteErrorByPinId 키는 orphan으로 남음
- 해소: 성공 분기에 `setDeleteErrorByPinId` 키 제거 추가 (`pinId in prev` 가드로 불필요 렌더 방지)

**G2 [PinEditDialog memo trim]**
- 발견자: gemini
- 위치: `PinEditDialog.tsx handleSubmit memoChanged`
- 본질: `patch.memo = memo`로 trim 미적용 → 데이터 정규화 일관성 깨짐
- 해소: `memoChanged = memo.trim() !== initialMemo.trim()` + `patch.memo = memo.trim()`. 빈 잠금 해제 시맨틱 유지

**C6 [PinUpdateCommand.of invariant]**
- 발견자: copilot
- 위치: `PinUpdateCommand.of`
- 본질: `addressProvided=true + address=null` 허용 → `Pin.changePlaceInfo(..., true, null)`로 address null 덮어쓰기 가능 → Q5 정책 위반
- 해소: of() 진입부에서 `addressProvided=true + null` 조합을 `addressProvided=false`로 정규화. 테스트 케이스 분리 (단독 null → PIN_UPDATE_EMPTY / memo 동반 → not-provided 정규화)

### Info (차기 Phase 이월)

**C5 [validateUrl 강도]**
- 발견자: copilot
- `"https:// "` 등 비정상 입력이 클라이언트 통과 가능. `new URL()` 파싱 권고
- 결정: phase-design D4 결정 ("PRD AC-3 명시 + 단순화") 유지. 차기 Phase에서 도메인 검증 강화 시 재검토

**C7 [PinService.updatePin 분기 단순화]**
- 발견자: copilot
- placeName-only / address-only 두 분기 통합 권고
- 결정: 현재 가독성/안전성에 큰 문제 없음. 차기 리팩토링 Phase에서 처리

**C4 [destructive 디자인 토큰 분리]**
- 발견자: copilot
- 삭제 버튼에 `colors.pinNew`(new-pin 의미) 재사용은 semantic 부정확. danger 토큰 신설 권고
- 결정: 디자인 시스템 일관성 작업이 별도 Phase로 분리될 수 있음. 차기 Phase 이월

## 처리 결과

| # | 항목 | 처리 | 커밋 |
|---|------|------|------|
| G1/C2 | Pin.validateInstagramUrl trim 저장 | 수정 | 09572e5 |
| C1 | address 빈 patch 400 | 수정 | 09572e5 |
| C3 | deleteErrorByPinId orphan | 수정 | 09572e5 |
| G2 | memo trim | 수정 | 09572e5 |
| C6 | PinUpdateCommand invariant | 수정 | 09572e5 |
| C5 | URL 파싱 강화 | 이월 (차기 Phase) | — |
| C7 | Service 분기 단순화 | 이월 (차기 Phase) | — |
| C4 | danger 토큰 분리 | 이월 (차기 Phase) | — |
| AC-17 | 권한 문구 정합 | 코드 친근체 유지 (phase-review 확정) | — |

## 총평

- 강점: codex가 산출물-코드 1:1 매핑을 정밀하게 수행했고, AC 17건 중 16건 충족 + 1건 문구 차이만 식별. PR 자동 리뷰는 phase-review가 놓친 **3건의 실제 버그(G1/C2, C1, C3)**를 발견하여 트림 정규화와 빈 patch 차단을 보강
- 합산: Critical 0건 / 신규 버그 3건 (모두 해소) / 정규화 2건 (모두 해소) / 이월 3건
- 권고: 차기 Phase에서 URL 파싱 강도 강화(C5), Service 분기 통합(C7), 디자인 토큰 의미 정합(C4) 검토

전문 원시 응답: `${DEV_DIR}/cross-review.raw.md` (codex 컴팩션 로그 포함 171줄)
