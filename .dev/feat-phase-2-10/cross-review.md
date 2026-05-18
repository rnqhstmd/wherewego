# Cross-Review 결과 + PR #24 리뷰 통합

- advisor: codex (GPT-5.4)
- 브랜치: feat/phase-2-10 (base: develop)
- DEV_DIR: .dev/feat-phase-2-10
- 실행 시각: 2026-05-18T20:50:00Z
- PR: https://github.com/rnqhstmd/wherewego/pull/24

## AC 충족 매트릭스

| AC | 충족 | 근거 |
|---|---|---|
| AC-1 | O | `PinV1ControllerIntegrationTest.java:435`, `MapClient.tsx:394` |
| AC-2 | O | `PinUpdateCommand.java:62`(범위 검증) + `ErrorType.java:58` + IT `:461` |
| AC-3 | O | `PinService.java:115` + IT `:478` |
| AC-4 | O | `PinPopup.tsx:288` 진입 + `MapClient.tsx:394,422` patch/롤백 + `PinPopup.tsx:104` 자동 펼침 |
| AC-5 | O | `Pin.java:213`, `PinService.java:135`, IT `:454` |
| AC-6 | O | `PinV1Dto.java:201`, IT `:495` |
| AC-7 | 부분 | `context/chatbot/status.md:28` 완료 기재 / 콘솔 증적은 repo 확인 불가 (trust-ledger 기보고, PR 본문 작성 예정) |
| AC-8 | 부분 | IT `ChatbotV1ControllerIntegrationTest.java:442` + status:28 / PR 본문 증적 작성 예정 |
| AC-9 | O | PLACE_SELECTION 5케이스 IT(`:442,459,476,491,508`) + self-check test exit 0 |
| AC-10 | O | `context/map/status.md:23` |
| AC-11 | O | `mapbox-token-sop.md` 절차 + `status.md:24` cross-link |
| AC-12 | O | `globals.css:48` body 폰트 + `layout.tsx:7,54` 주입 |
| AC-13 | O | 회귀 IT `:239,329,550` + `PinEditDialog.tsx:12` |
| AC-14 | O | `PinV1Controller.java:66`, `PinV1Dto.java:51`, IT `:663,689` |
| AC-15 | O | reducer `MapClient.tsx:143`, supercluster `MapboxView.tsx:371`, `PinPopup.tsx:123` |

[Must] 13건 충족 / [Must] 2건 부분(AC-7, AC-8 운영 증적은 PR 본문 기록 예정).

## 설계 범위 이탈

cross-review가 39개 파일을 "설계서 §1 명시 외"로 분류했다.

**판정**: develop 브랜치가 #19 머지 시점에 머물러 있어 Phase 2.7~2.9 PR들이 main에 머지된 후 develop에 backport되지 않은 상태다. 즉 cross-review가 본 PR 단일 커밋(ee67048 + c14fe91) 변경이 아닌 `develop...HEAD` 누적 변경(Phase 2.7~2.10) 전체를 본 결과다. **이는 false positive이며 본 PR의 실제 추가 범위는 설계서 §1과 일치한다**.

근거:
- `git show ee67048 + c14fe91 -- ':!.dev/'` 결과는 설계서 §1의 신규 2 + 수정 13 + 테스트 2 (+ 후속 mapbox-env.md, PinPopup useEffect)와 일치
- Phase 2.9 페이지네이션 파일들(`PinListResult`, `PinRepository`, `PinJpaRepository`, ...), Gemini 관련(`PlaceProperties`, `GeminiPlaceClient`, ...), 다른 도메인 status/architecture 문서, 프론트엔드 회귀 테스트들은 모두 Phase 2.7~2.9 누적이며 PR #20, #21, #22, #23에서 이미 머지된 변경

**예외 — 본 PR 신규 진입 1건**:
- `.claude/settings.local.json` 신규 추가 (ee67048 커밋에 포함됨). Windows 로컬 JDK 경로(`/c/Users/SQI/...`)가 들어있어 설계 범위 외 + 보안 우려. PR 리뷰 Security HIGH와 일치.

## 신규 위험

trust-ledger에 없는 신규 항목.

### Warning

- **[Warning] [ASSUMPTION] 좌표 소수점 7자리 서버 검증 부재**
  - 위치: `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinUpdateCommand.java:62-71`
  - 근거: PRD §FR-PIN-7 (`prd.md:48`)는 "위도 -90~90, 경도 -180~180, **소수점 7자리 이하**를 서버에서 검증"한다고 약속. 설계서 §2.1 NOTE(`design.md:83`)는 "scale 검증은 컨벤션상 생략"으로 해석. 실제 코드는 null + range만 검증하고 scale 검증 없음.
  - 권고: ① 서버 scale 검증 추가 (`latitude.stripTrailingZeros().scale() <= 7` 등) 또는 ② PRD를 "DB scale 자동 라운딩에 위임" 정책으로 명시 갱신. 정책 결정 필요.

### Info

- **[Info] [POLICY] `.claude/settings.local.json` 로컬 권한 파일 추적 진입** (PR 리뷰 Security HIGH와 동일)
  - 위치: `.claude/settings.local.json:3-5`
  - 근거: 설계서 §1 신규 파일은 `PinCoordinateEditPicker.tsx`, `mapbox-token-sop.md`만 명시. 실제로 ee67048에 `.claude/settings.local.json`이 신규 추가되었고 `JAVA_HOME="/c/Users/SQI/.jdks/openjdk-21.0.1"` Windows 절대경로 포함.
  - 권고: ① `.gitignore`에 `.claude/settings.local.json` 추가 + git tracked에서 제거(`git rm --cached`), 또는 ② 절대경로를 환경변수/상대경로로 치환.

## references 위반

위반 없음 (references/ 디렉토리 미존재)

## PR #24 자동 리뷰 코멘트 (gemini-code-assist)

### Security HIGH
- **[#1] `.claude/settings.local.json:5`** — 로컬 절대경로 노출 (위 [Info] [POLICY]와 동일 항목, 심각도 격상)

### Medium
- **[#2] `PinUpdateCommand.java:66-70`** — 좌표 boundary `BigDecimal.valueOf(-90/90/-180/180)` 매 호출 생성. `private static final BigDecimal LAT_MIN/LAT_MAX/LNG_MIN/LNG_MAX` 상수 추출 권고.
- **[#3] `PinCoordinateEditPicker.tsx:83`** — `coord.lat.toFixed(6)` → `.toFixed(7)` 변경 권고. DB `DECIMAL(10,7)` 스펙과 표시 정밀도 일치. (Trust Ledger MEDIUM과 동일 항목, gemini가 명시적 수정 제안)

## 총평

- **강점 1**: 좌표 수정 핵심 흐름은 백엔드 Provided 패턴 + 권한 검증 + `useOptimistic` 즉시 반영/롤백까지 PRD 주요 AC와 잘 매칭됨
- **강점 2**: Mapbox SOP가 토큰 발급/URL Restriction/환경변수/배포/롤백 흐름을 포함해 NFR-4 충족
- **신규 위험 합산**: Critical 0 / Warning 1 / Info 1 (cross-review) + Security HIGH 1 / Medium 2 (PR 리뷰)
- **머지 전 권고**:
  1. `.claude/settings.local.json` 제거 — Security HIGH
  2. 좌표 scale 검증 정책 결정 — PRD vs 코드
  3. (선택) BigDecimal 상수화 + toFixed(7) — Medium 미세 개선
  4. PR 본문에 develop 브랜치 노후화로 인한 diff 비대 + Phase 2.7~2.9 누적 변경 설명 추가 (선택)

## 통합 처리 항목 우선순위

| # | 항목 | 위치 | 심각도 | 출처 | 권고 |
|---|------|------|--------|------|------|
| 1 | `.claude/settings.local.json` 절대경로 노출 | `.claude/settings.local.json:5` | **Security HIGH** | PR 리뷰 + cross-review | 즉시 제거 (gitignore + cached 해제) |
| 2 | 좌표 scale 검증 부재 | `PinUpdateCommand.java:62-71` | Warning | cross-review 신규 | 사용자 결정 필요 (코드 추가 vs PRD 수정) |
| 3 | BigDecimal boundary 상수화 | `PinUpdateCommand.java:66-70` | Medium | PR 리뷰 | 처리 권장 |
| 4 | 좌표 표시 6→7자리 | `PinCoordinateEditPicker.tsx:83` | Medium | PR 리뷰 + Trust Ledger | gemini suggestion 적용 |
