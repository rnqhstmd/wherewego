# Trust Ledger — Phase 2.8

## Mechanical Gate

| Gate | 결과 |
|------|------|
| Frontend `npm run build` | ⏸️ 차단 (node_modules 미설치, `next: command not found`) |
| Backend `./gradlew test` | ⏸️ 차단 (JDK 21 미설치) |
| 정책 | 사용자가 phase-implement에서 "로컬 빌드 검증 없이 진행, 수동 검증 직접 수행"으로 결정. 동일 원칙 적용. |

## QA 통합 리뷰 (qa-manager)

### Critical
없음.

### Warning (4건)
- **W1** [PinEditDialog.tsx:59] `placeNameChanged` trim 비대칭 — 사용자가 trailing space 입력 시 changed=false 오인
- **W2** [PinService.java:98] `cmd.memo().isEmpty()` null 체크 없음 — 현재 도달 불가하나 계약-방어 불일치
- **W3** [MemoTagPanelContent.tsx:77] AC-5 문구 "이미 등록된 Instagram URL입니다" vs 코드 "이미 등록된 장소예요" 불일치 (Q3 결정 반영, PRD 미갱신)
- **W4** [PinEditDialog.tsx:107-113] address 빈 문자열 저장 UX — 사용자가 지웠다고 인식하나 실제 변경 없음

### Info (3건)
- I1 lib/pin/constants.ts MEMO_MAX_LENGTH 주석 "502자" 오기
- I2 map/actions.ts DeletePinActionResult 타입 중복
- I3 PinV1ControllerIntegrationTest 주석 오해 소지

### QUESTION (3건)
- SQ1 검색 진입 placeName 빈 문자열 방어 필요?
- SQ2 deleteErrorByPinId 초기화 순서 의도?
- SQ3 placeName 빈 문자열 검증 책임 레이어?

### AC 17건 충족
| # | 결과 | 비고 |
|---|------|------|
| AC-1~4 | ✅ | |
| **AC-5** | ⚠️ | 메시지 문구 불일치 (Q3 결정 vs PRD 잔재) |
| AC-6~17 | ✅ | |

**충족: 16/17** (AC-5만 문구 차이)

## ZeroTrust 통합 감사 (security-auditor)

### CRITICAL
없음.

### HIGH (1건)
- **H1** [RISK] **`PinCard.tsx:71` instagramUrl 비-HTTPS URL 오픈 리다이렉션/XSS 벡터**
  - 챗봇 경로(`registerFromInstagram`/`registerFromSelection`)는 instagramUrl 형식 검증 없이 그대로 저장. `javascript:...` 등 위험 schema가 `<a href={pin.instagramUrl}>`에 바인딩되면 XSS/오픈 리다이렉션
  - Phase 2.8 클라이언트 검증은 웹 등록 경로에만 적용. 기존 챗봇 경로는 무방비
  - 권고: 백엔드 `Pin.autoFromInstagram`/`fromSelection`에서 `https://` 시작 검증 추가 + 프론트엔드 PinCard href 바인딩 전 검사

### MEDIUM (7건)
- **M1** [GAP] `PinEditDialog` address 빈 문자열 UX 피드백 부재 (W4와 동일)
- **M2** [GAP] `PinServiceIT` address-only 수정 케이스 미검증
- **M3** [GAP] `PinV1ControllerIntegrationTest`에 `{"address":""}` 단독 전송 → PIN_UPDATE_EMPTY 케이스 누락
- **M4** [ASSUMPTION] `https://` 검증만으로 임의 https URL 차단 불가 (PRD OOS이나 도메인 검증 부재 인식 필요)
- **M5** [POLICY] PinDeleteConfirm `<dialog>` z-index 미지정 — Safari/구형 WebKit에서 mapbox-gl 위 노출 미검증
- **M6** [GAP] PinListClient handleSave optimistic patch와 서버 응답 정합성 — 사실상 발생 없음 (W4/M1 UX 문제만 남음)
- **M7** [ASSUMPTION] PinUpdateCommand.of() 호출처 전체 갱신 — 빌드 미검증

### LOW (3건)
- L1 map/actions.ts 주석 통일 권고
- L2 PinPopup deleteError 표시 시 팝업 높이 증가 가능성 (SpeechBubblePopup 레이아웃 확인)
- L3 PinService memo null 방어 (W2와 동일 주제)

### 교차 검증 정합 ([정합] 항목 13건)
- BR-1~6, MUST-1, PESSIMISTIC_WRITE, QE-1/QE-2, AC-16, ErrorType 메시지 일반화 모두 정합

### 판정
근본적 구조 문제 없음. **HIGH 1건 (H1 PinCard XSS)** 배포 전 처리 권고.

## 통합 findings 요약

| 심각도 | 건수 |
|--------|------|
| Critical / CRITICAL | 0 |
| QA Warning | 4 |
| ZT HIGH | 1 |
| ZT MEDIUM | 7 |
| QA Info / ZT LOW | 6 |
| QUESTION / 확인 사항 | 5 |

**자동 수정 트리거**: 없음 (Critical 0).
**사용자 결정 필요**: H1 (XSS) 본 Phase 처리 여부, AC-5 문구 정합, address 빈 문자열 UX, Warning 4건 처리.

## 사용자 결정 + 적용 결과 (1차 → 확인 리뷰)

| 항목 | 결정 | 적용 |
|------|------|------|
| H1 (XSS) | 백엔드 Pin.autoFromInstagram + fromSelection까지 검증 | ✅ Pin.java validateInstagramUrl 헬퍼 + ErrorType.PIN_INSTAGRAM_URL_INVALID + PinCard.tsx 조건부 렌더 |
| AC-5 문구 | PRD를 "이미 등록된 장소예요"로 갱신 | ✅ prd.md AC-5/시나리오/엣지케이스 4곳 갱신 |
| Warning 4건 (W1/W2/W4) + MEDIUM 2건 (M2/M3) + I3 + SQ1 | 자동 수정 | ✅ 8개 파일 수정 완료 |
| SQ2 deleteErrorByPinId 초기화 순서 | 현행 유지 | ✅ 코드 변경 없음 |
| SQ3 placeName 검증 책임 레이어 | 현행 Command 레이어 | ✅ 코드 변경 없음 |

## 확인 리뷰 결과 (qa-manager)
- 전반 판정: **통과**
- Critical: 0, Warning(신규): 0, 회귀: 0
- 8개 수정 항목 모두 ✅
- 미해소: I1 (constants.ts 주석 "502자" 오기) — 사소, 차기 Phase

## Mechanical Gate 최종
- Frontend `npm run build`: 차단 (node_modules 미설치)
- Backend `./gradlew test`: 차단 (JDK 21 미설치)
- 사용자 별도 검증 예정 (PR 전)

