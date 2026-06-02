# 설계 확정본: iOS P6 — 디자인 정합성 최종 QA + 토큰 단일소스 가드

## 설계 규모
**중형** — 신규 Node.js 스크립트 1개(의존성 0) + 다수 SwiftUI 파일의 국소 `.tracking()` 추가 + 문서 1개. 새 아키텍처 레이어 없음. 백엔드/DB/API/`tokens.ts` 변경 없음.

## 배경 및 범위 (실질 가치 명문화)
- iOS 앱은 P1~P5로 기능 완료. 디자인 토큰은 `frontend/src/lib/design/tokens.ts`(웹 단일소스) → `ios/.../Theme.swift`(WGColor/WGFont) 1:1 이식.
- 색상 22키는 현재 값 일치하나 웹 `shadow`/`shadowMd`는 `rgba()` 문자열, iOS는 `Color(hex:opacity:)` → 표현형이 달라 사람 눈/grep으로 drift 감지 불가. 정규화 비교 가드 필요.
- **Windows 제약 + 폰트 미번들로 인한 실질 가치 한계:** 폰트 4종이 `Resources/Fonts/`에 미번들(`.gitkeep`만)이라 시뮬레이터/시각 렌더는 시스템 폰트 폴백. 따라서 `.tracking` 등 코드 보정의 시각 정합은 폰트 번들(Mac, DOD-B-1) 전까지 검증 불가. P6 Windows 산출물의 실질 가치 = 정확히 세 가지:
  1. 색상 회귀 가드(drift 자동 탐지 — Windows 완결 검증 가능)
  2. 코드 레벨 값 기입(웹 명시 letterSpacing → iOS `.tracking`, 전/후 주석 추적)
  3. Mac 잔여 체크리스트 준비(폰트 번들·시각 픽셀 QA·easing 대조·TestFlight·제출)

## 요구사항·AC 매핑 갱신표
| AC | 원 분류 | 확정 분류 | 충족 방식 |
|----|---------|-----------|-----------|
| AC-1 | Must | Must | 실파일 `node scripts/design-token-guard.mjs` → exit 0 (selftest와 별개) |
| AC-2 | Must | Must | 값 변경/키 추가 시 비-0 + 키·값 콘솔 출력 (`--selftest`로 로직 검증) |
| AC-3 | Must | Must | `rgba(26,26,46,0.08)` ↔ `#1A1A2E`/opacity 0.08 정규화 동등 |
| AC-4 | Must | Must | 키 누락 별도 메시지 + 실패 종료 |
| AC-5 | Must | Must | LoginView 워드마크 `.tracking(-1.5)` |
| AC-6 | Must | **재해석** | 워드마크 `.tracking` 적용 + 단일줄 lineHeight는 주석으로 Mac 이연(`.lineSpacing` 미적용) |
| AC-7 | Must | Must | 분류표 기준 — iOS 대응 화면의 웹 명시 letterSpacing을 `.tracking`으로 적용, 웹 전용/iOS 미대응은 BR-5 제외 |
| AC-8 | Must | Must | DoD-B 문서(폰트 4종·출처·plist·라이선스·렌더·TestFlight·제출) |
| AC-9 | Must | Must | DoD-B 각 항목 "Mac 필요"+"완료 조건" |
| **AC-10** | Should | **Mac 잔여(DoD-B) 이연** | 코드 보정 제거. DoD-B "Mac 시각 QA 시 cubic-bezier↔easing 대조·보정" 항목 |
| AC-11 | Should | Should | 수치 명시+상이 시 패딩/코너/폰트크기 보정, 전/후 주석 |

**AC-10 이연 사유:** 비판검토에서 기존 지목 `MapView.swift:356`·`ChatMessageRow.swift:115`가 웹 cubic-bezier 원본과 무관한 오지목으로 확인. 곡선 체감은 Windows 검증 불가 → easing 코드 보정 전면 제거.

## 변경 범위

### 신규 생성
| 파일 | 역할 |
|------|------|
| `scripts/design-token-guard.mjs` | (영역1) 색상 22키 drift 감지 + `--selftest`. `node` 단독, 의존성 0 |
| `.dev/feat-ios-native-p6-design-qa/dod-b-checklist.md` | (영역3) Mac 잔여 체크리스트(폰트 매핑표 + easing 대조 항목) |

### 수정 대상 (영역2 — 웹 letterSpacing 명시 + iOS 대응 화면, 분류표 기준)
| 파일 | 변경 요지 |
|------|-----------|
| `ios/.../Features/Auth/LoginView.swift:34-36` | 워드마크 `.tracking(-1.5)`. lineHeight 단일줄 → 미적용 주석. 태그라인(46-49)은 letterSpacing 없음 → tracking 금지 |
| `ios/.../Features/Onboarding/NicknameView.swift` | 헤드라인 `.tracking(-1)` (웹 NicknameClient:129) |
| `ios/.../Features/Onboarding/GroupStartView.swift` | 헤드라인 `.tracking(-1)` (웹 GroupStartClient:44) |
| `ios/.../Features/Onboarding/InviteCodeView.swift` | 헤드라인 `.tracking(-1)` (웹 InviteCodeClient:79) |
| `ios/.../Features/Onboarding/WelcomeWizardView.swift` | 스텝 헤드라인 `.tracking(-1)` (웹 Step1/2/3:41/119/86) |
| `ios/.../Features/Group/GroupCreateView.swift` | 헤드라인 `.tracking(-1)` (웹 NewGroupClient:82) |

**수정하지 않음:** `tokens.ts`(PRD 제외), `Theme.swift`(현재 색상 일치 — drift 발견 시에만 BR-1), 웹 전용 컴포넌트, easing iOS 파일(AC-10 이연).

## 적용 컨벤션
- iOS: `struct ...: View`, 선언형 body. 폰트 `WGFont.emo(48)`, 색 `WGColor.ink`. 수식어 체이닝(`.font().tracking().foregroundStyle()`). 한국어 인라인 주석 + `(FR-N/AC-N)` 추적 태그.
- Node 스크립트: ESM(`.mjs`), 의존성 0(`node:fs`만), `import.meta.url` 절대경로(CWD 비의존).

---

## 상세 설계

### 영역 1: 토큰 drift 가드 (`scripts/design-token-guard.mjs`)

**배치/실행:** 루트 `scripts/design-token-guard.mjs`. `node scripts/design-token-guard.mjs` 단독 실행만(package.json 미등록). `import.meta.url` 기준 `../frontend/src/lib/design/tokens.ts`, `../ios/WhereWeGo/Core/DesignSystem/Theme.swift` 절대경로화 → 어느 CWD에서도 동작(QE-1).

**파싱 정규식 (견고성):**
- `tokens.ts`: 블록 `colors\s*=\s*\{([\s\S]*?)\}\s*as const`. 라인 `^\s*(\w+)\s*:\s*"([^"]+)"` → key, value(`"#FAF8F5"` | `"rgba(26,26,46,0.08)"`). `fonts` 블록 미파싱(D-Q3 제외).
- `Theme.swift`: 블록 `enum\s+WGColor\s*\{([\s\S]*?)\n\}`(extension Color의 init은 enum 밖 → 자연 배제). 라인 `static\s+let\s+(\w+)\s*=\s*Color\((.+)\)` — 정렬용 다중 공백 `\s+` 흡수. args = `hex:\s*"([^"]+)"(?:\s*,\s*opacity:\s*([\d.]+))?`.

**정규화 `normalize(raw) → {r,g,b,a}`** (r,g,b 0..255 정수, a 0..1 실수 3자리):
1. `#RRGGBB` → `{r,g,b,a:1}`
2. `#RRGGBBAA` → 끝 2자리/255 = a
3. `rgba(R,G,B,A)` → 공백 허용 `/rgba\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*([\d.]+)\s*\)/`
4. `rgb(R,G,B)` → a:1
5. iOS `hex:"#RRGGBB"`(opacity 없음) → a:1
6. iOS `hex:"#RRGGBB", opacity:N` → r,g,b from hex, a=N
- hex 6/8자리, 대소문자 무시. a 비교 `Math.abs<0.001`, rgb 정수 일치.

**비교/출력:**
```
parseWebColors(src) → Map<key, rawString>
parseIosColors(src) → Map<key, rawString>
normalize(raw)      → {r,g,b,a}
compare(web, ios)   → { missingInIOS[], missingInWeb[], mismatched[] }
runSelftest()       → boolean
main()              → process.exit(0|1)
```
- 일치: `✓ 색상 21/21 키 일치` → exit 0 (AC-1)
- 키 누락: `✗ 키 누락 — Theme.swift에 없음: [...]` / `tokens.ts에 없음: [...]` → exit 1 (AC-4)
- 값 불일치: `✗ <key>: web=rgba(...) {r,g,b,a} vs ios=#... {r,g,b,a}` → exit 1 (AC-2)

**색상 값 중복 무해:** `#FFFFFF` panel/mapRoad 2회, rgba 베이스 shadow/shadowMd 2회 — 키 기준 1:1 대조라 무해.

**검증 절차 분리:**
- AC-1(실파일): Windows `node scripts/design-token-guard.mjs` → 실파일 파싱·비교 적용 exit 0. 이것만 AC-1 충족.
- `--selftest`(비교로직): 인메모리 fixture — ① 색 변조→mismatch ② 키 제거→missing ③ `rgba(26,26,46,0.08)`↔`#1A1A2E`/0.08 동등→match ④ 공백 포함 `rgba(26, 26, 46, 0.08)`→match. 통과 시 exit 0. AC-1 대체 안 함.

**폰트 토큰:** 가드 제외(var명↔PostScript명 도메인 불일치). DoD-B 매핑표(영역3 §2).

### 영역 2: SwiftUI 정적 대조·보정 (`.tracking`만 실효)

**대조 매핑:**
| 웹 속성 | iOS 변환 | 규칙 |
|---------|----------|------|
| `letterSpacing: N`(px) | `.tracking(N)` | 부호·값 그대로(px≈pt). `-1.5`→`.tracking(-1.5)` |
| `lineHeight: N`(배수) | `.lineSpacing((N−1)×fontSize)` | 진짜 멀티라인 텍스트에만. 단일줄은 미적용+주석 |
| `cubic-bezier` | — | 이번 범위 제외(DoD-B 이연) |

**`.tracking` 41곳 분류표 (AC-7 판정 기준):**

(a) 보정 대상 — iOS 대응 존재:
| 웹 위치 | letterSpacing | iOS 대응 | 적용 |
|---------|--------------|----------|------|
| login/LoginClient.tsx:103 | -1.5 | LoginView 워드마크 | `.tracking(-1.5)` |
| onboarding/nickname/NicknameClient.tsx:129 | -1 | NicknameView 헤드라인 | `.tracking(-1)` |
| onboarding/group-start/GroupStartClient.tsx:44 | -1 | GroupStartView 헤드라인 | `.tracking(-1)` |
| onboarding/invite-code/InviteCodeClient.tsx:79 | -1 | InviteCodeView 헤드라인 | `.tracking(-1)` |
| welcome/_steps/Step1:41,Step2:119 | -1 | WelcomeWizardView 스텝1·2 헤드라인 | `.tracking(-1)` (Step3Bot=iOS 2스텝 구조라 대응 화면 없음 → BR-5 의도적 차이) |
| groups/new/NewGroupClient.tsx:82 | -1 | GroupCreateView 헤드라인 | **이연** — GroupCreateView는 P4 미구현 플레이스홀더(헤드라인 부재). P4 화면 구현 시 적용(코드 내 TODO 주석). AC-7 판정에서 제외 |

(b) BR-5 의도적 차이 — 제외(웹 전용/iOS 미대응): gate, bot/connect, groups list, settings, invite preview/expired, groups/invite, map/loading, welcome 보조 라벨, map/_components 데스크톱 전용(ActionBar/DesktopActionPill/MobileTopNav/NotificationBell/NotificationPanel/PinPhotoCropper/TagFilterButton/TagLegendButton/VisitToast) — 35곳.

> AC-7 판정: **구현된 화면 기준** — LoginView·Nickname·GroupStart·InviteCode·WelcomeWizard(스텝1·2) = 헤드라인 6개 보정 적용. GroupCreateView(P4 미구현 플레이스홀더)·Step3Bot(iOS 2스텝)·BR-5 제외 35곳은 "iOS 미대응/의도적 차이"로 제외. 즉 "구현 화면에서 웹 명시 letterSpacing이 iOS에 모두 적용됨" 충족.

**실증(LoginView 워드마크 — AC-5):**
```
Text("우리가 갈 지도")
    .font(WGFont.emo(48))
    .tracking(-1.5)   // 웹: letterSpacing:-1.5 (AC-5)
    // 웹 lineHeight:1.05 — 단일줄이라 lineSpacing 효과없음, 행간 정합은 Mac QA(DoD-B-3)
    .foregroundStyle(WGColor.ink)
```

**태그라인 BR-3:** 웹 태그라인(LoginClient:112-122)은 letterSpacing 없음(lineHeight:1.5만) → 태그라인 `.tracking` 금지. 멀티라인이면 `.lineSpacing((1.5-1)*15.5≈7.75)` 후보(단일줄이면 미적용+주석).

**기적용 재대조:** NicknameView:22(.lineSpacing(6)), GroupStartView:77(.lineSpacing(2)), PermissionDialogView:42(.lineSpacing(4)) — 웹 대응값과 정합성만 확인.

**변경 추적:** 인라인 주석(`// 웹: letterSpacing:-1.5 (AC-N)`) + 커밋 메시지 화면별 전/후 요약. 별도 diff 로그 미생성.

**FR-6/AC-11(Should):** 웹 수치 명시 + iOS 상이한 패딩/코너/폰트크기에만 보정, 전/후 주석.

### 영역 3: DoD-B 체크리스트 (`.dev/feat-ios-native-p6-design-qa/dod-b-checklist.md`)

구조:
- §0 개요 — Windows 완료분 vs Mac 필요분 + 실질 가치 한계
- §1 폰트 번들(DOD-B-1,2)[Mac✅]: 4종 표(PostScript명/plist 파일명/다운로드 출처/라이선스/완료조건). ⚠️ .custom() 인자=PostScript명(파일명 아님) 검증 항목.
  - NotoSerifKR-Regular | Google Fonts noto-serif-kr | SIL OFL 1.1
  - GowunBatang-Regular | Google Fonts Gowun Batang | SIL OFL 1.1
  - Pretendard-Regular | github.com/orioncactus/pretendard/releases | SIL OFL 1.1
  - JetBrainsMono-Regular | github.com/JetBrains/JetBrainsMono/releases | Apache 2.0
- §2 웹↔앱 폰트 매핑표(D-Q3 흡수): serif/emo/sans/mono → var → next/font → WGFont → PostScript명
- §3 시각 픽셀 대조(DOD-B-3)[Mac✅]: 11도메인 체크박스 + **easing 대조(AC-10 이연)**: bubble-pop 180ms·pin-drop 360ms cubic-bezier(0.2,0.8,0.2,1), PinShareSheet cubic-bezier(0.16,1,0.3,1), 단일줄 lineHeight 행간
- §4 TestFlight(DOD-B-4)[Mac✅] / §5 앱스토어 제출(DOD-B-5)[Mac✅]
- §6 CI 참고(FR-7 Could 제외): `run: node scripts/design-token-guard.mjs`

AC-8/AC-9 충족: 전 항목 포함 + 각 "Mac 필요"+"완료 조건" 명시.

## 의존성 및 영향도
- 새 의존성: 없음(`node:fs`만). frontend devDependencies 무변.
- 영역2는 텍스트 `.tracking` 추가뿐 → 레이아웃/로직 무변. Theme.swift 미변경. easing 파일 미변경.
- 하위 호환: `.tracking` iOS 16+, deploymentTarget iOS 17.0 → 안전.

## 구현 순서 (easing 단계 제거)
1. [Must] `scripts/design-token-guard.mjs` 작성 — 파싱·정규화(공백 허용)·비교·출력 + `--selftest`(공백 rgba fixture).
2. [Must] 가드 검증 — 실파일 `node` 실행 exit 0(AC-1), `--selftest` AC-2~4 재현(별개).
3. [Must] LoginView 워드마크 `.tracking(-1.5)` — AC-5 실증, lineHeight 단일줄 주석.
4. [Must] 전 화면 `.tracking` 일괄 보정 — 분류표 (a) 6화면. AC-7.
5. [Should] 패딩/코너/폰트크기 상이 보정 — AC-11, 전/후 주석.
6. [Must] DoD-B 체크리스트 작성 — AC-8/AC-9 + easing 대조 항목(AC-10 이연).

병렬: {1→2} ∥ {3→4→5} ∥ {6}.
