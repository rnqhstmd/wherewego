# DoD-B 체크리스트 — iOS P6 Mac 잔여 항목

## 0. 개요

P6(디자인 정합 QA)의 완료 정의(DoD)는 두 갈래로 나뉜다. Windows 환경에서 가능한 항목은 본 작업에서 완료했고, 나머지는 Mac(Xcode·시뮬레이터·TestFlight) 환경에서만 완결할 수 있다.

- **Windows 완료분**
  - 색상 drift 가드 스크립트(`scripts/design-token-guard.mjs` 등) — 웹 `tokens.ts` ↔ iOS `Theme.swift` 색 값 일치 검사
  - `.tracking` 코드 기입 — 자모 단위 자간(letter-spacing) 보정값을 SwiftUI 코드에 반영
  - 본 체크리스트 문서 작성
- **Mac 필요분**
  - 폰트 번들(폰트 파일 다운로드·프로젝트 포함·시뮬레이터 렌더 확인)
  - 시각 픽셀 QA(웹 스크린샷 vs iOS 시뮬레이터 화면별 대조)
  - easing(애니메이션 곡선) 대조·보정
  - TestFlight 빌드 업로드·내부 테스트
  - 앱스토어 심사 제출

> ⚠️ **실질 가치 한계** — 현재 `ios/WhereWeGo/Resources/Fonts/` 에는 폰트 파일이 없고 `.gitkeep` 만 있다(미번들). 폰트가 번들되기 전까지 iOS는 시스템 폰트로 폴백하므로, Windows에서 기입한 `.tracking` 자간 보정의 **시각 정합은 폰트 번들(§1)이 완료되기 전까지 검증할 수 없다.** 즉 §1이 §3 시각 QA의 선행 조건이다.

---

## 1. 폰트 번들 (DOD-B-1, DOD-B-2) — Mac 필요: ✅

| 폰트 | PostScript명 (Theme.swift `.custom` 인자) | Info.plist `UIAppFonts` 등록 파일명 | 다운로드 출처 | 라이선스 | 완료 조건 |
|------|------|------|------|------|------|
| Noto Serif KR | `NotoSerifKR-Regular` | `NotoSerifKR-Regular.otf` | https://fonts.google.com/noto/specimen/Noto+Serif+KR | SIL OFL 1.1 | 시뮬레이터 렌더 확인 |
| Gowun Batang | `GowunBatang-Regular` | `GowunBatang-Regular.ttf` | https://fonts.google.com/specimen/Gowun+Batang | SIL OFL 1.1 | 시뮬레이터 렌더 확인 |
| Pretendard | `Pretendard-Regular` | `Pretendard-Regular.otf` | https://github.com/orioncactus/pretendard/releases | SIL OFL 1.1 | 시뮬레이터 렌더 확인 |
| JetBrains Mono | `JetBrainsMono-Regular` | `JetBrainsMono-Regular.ttf` | https://github.com/JetBrains/JetBrainsMono/releases | Apache 2.0 | 시뮬레이터 렌더 확인 |

- [ ] **현 상태 확인** — `ios/WhereWeGo/Resources/Fonts/` = `.gitkeep` 만 존재(폰트 파일 미번들). `Info.plist` 의 `UIAppFonts` 등록은 **이미 완료**되어 있음. 실제 등록 라인(`Info.plist` L49–55):
  ```xml
  <key>UIAppFonts</key>
  <array>
      <string>NotoSerifKR-Regular.otf</string>
      <string>GowunBatang-Regular.ttf</string>
      <string>Pretendard-Regular.otf</string>
      <string>JetBrainsMono-Regular.ttf</string>
  </array>
  ```
  - **Mac 필요**: ✅ (폰트 파일 다운로드 + Xcode 프로젝트 타깃 멤버십 포함은 Mac/Xcode에서 수행)
  - **완료 조건**: 위 4개 파일을 `Resources/Fonts/` 에 배치 + Xcode 타깃에 포함 + 빌드 후 시뮬레이터에서 4개 폰트가 시스템 폴백 없이 렌더됨을 육안 확인.
- [ ] **PostScript명 일치 검증** — `Theme.swift` 의 `WGFont.*` 는 `.custom(<PostScript명>, size:)` 형태이며, 인자는 **파일명이 아니라 폰트의 PostScript명**이다. 다운로드한 폰트 파일별로 메타데이터(macOS 글꼴 정보 / `fc-scan` / Font Book)를 열어 PostScript명이 위 표의 값과 정확히 일치하는지 확인하고, 다르면 `Theme.swift` 의 `.custom` 인자를 실제 PostScript명으로 보정한다.
  - **Mac 필요**: ✅
  - **완료 조건**: 4개 폰트 모두 표의 PostScript명으로 `.custom()` 호출이 성공(폴백 없음)함을 확인.
  - ⚠️ 특히 가변 폰트(Pretendard variable, JetBrains Mono variable)나 KR 폰트는 PostScript명이 표기와 다를 수 있으니 반드시 메타로 검증할 것.

---

## 2. 웹↔앱 폰트 매핑 검증표

웹 `frontend/src/lib/design/tokens.ts` 의 `fonts` 키 ↔ CSS 변수 ↔ `layout.tsx` 의 `next/font` 선언 ↔ iOS `WGFont` ↔ PostScript명 대응이 일관됨을 확인한다.

| 웹 fonts 키 (tokens.ts) | 웹 CSS 변수 | next/font 선언 (layout.tsx) | iOS WGFont (Theme.swift) | PostScript명 |
|------|------|------|------|------|
| `serif` | `--font-serif` | `Noto_Serif_KR({ weight: ["400","700","900"] })` | `WGFont.serif(_:)` | `NotoSerifKR-Regular` |
| `emo` | `--font-emo` | `Gowun_Batang({ weight: ["400","700"] })` | `WGFont.emo(_:)` | `GowunBatang-Regular` |
| `sans` | `--font-sans` | `localFont({ src: "../../public/fonts/PretendardVariable.woff2" })` (self-host) | `WGFont.sans(_:)` | `Pretendard-Regular` |
| `mono` | `--font-mono` | `JetBrains_Mono({ weight: ["400","500"] })` | `WGFont.mono(_:)` | `JetBrainsMono-Regular` |

- [ ] 4개 매핑이 위 표대로 일치함을 확인. **Mac 필요**: ❌(코드/문서 대조로 충분, Windows에서도 검증 가능). **완료 조건**: 키·변수·iOS 함수·PostScript명 4열이 빠짐·불일치 없이 매칭됨.
- 참고: 웹 `geistMono`(`--font-geist-mono`)는 다른 페이지 호환용 별도 변수로, P6 디자인 토큰 4종 매핑 대상이 아니다.

---

## 3. 시각 픽셀 대조 (DOD-B-3) — Mac 필요: ✅

웹 화면 스크린샷과 iOS 시뮬레이터 렌더를 도메인 화면별로 1:1 대조한다. (§1 폰트 번들 완료가 선행 조건 — 폴백 상태에서는 자간/행간 정합을 판정할 수 없음.)

**도메인 화면별 체크박스** (웹 스크린샷 vs iOS 시뮬레이터):

- [ ] Auth — **Mac 필요**: ✅ / **완료 조건**: 로그인·온보딩 진입 화면 폰트·자간·여백 일치
- [ ] Chat (Bot) — **Mac 필요**: ✅ / **완료 조건**: 봇 채팅방 버블·텍스트 정합
- [ ] Chat (Couple) — **Mac 필요**: ✅ / **완료 조건**: 1:1 채팅방 버블·텍스트 정합
- [ ] Group — **Mac 필요**: ✅ / **완료 조건**: 그룹 화면 레이아웃·타이포 정합
- [ ] Map — **Mac 필요**: ✅ / **완료 조건**: 지도 오버레이·라벨 색/폰트 정합
- [ ] Onboarding — **Mac 필요**: ✅ / **완료 조건**: 온보딩 단계별 타이포·여백 정합
- [ ] Photo — **Mac 필요**: ✅ / **완료 조건**: 사진 화면 캡션·버튼 텍스트 정합
- [ ] Pin — **Mac 필요**: ✅ / **완료 조건**: 핀 상세/생성 폼 텍스트·자간 정합
- [ ] Place — **Mac 필요**: ✅ / **완료 조건**: 장소 카드·리스트 타이포 정합
- [ ] Common — **Mac 필요**: ✅ / **완료 조건**: 공통 컴포넌트(버튼/내비/시트) 타이포·색 정합

- [ ] **easing 대조·보정 (AC-10 이연)** — **Mac 필요**: ✅
  - [ ] `maygo-bubble-pop` 180ms `cubic-bezier(0.2, 0.8, 0.2, 1)` (웹: DesktopActionPill / MobileTopNav / NotificationBell) → iOS 대응 애니메이션을 `Animation.timingCurve(0.2, 0.8, 0.2, 1, duration: 0.18)` 로 보정·체감 비교
  - [ ] `maygo-preview-pin-drop` 360ms `cubic-bezier(0.2, 0.8, 0.2, 1)` (웹: MapboxView) → iOS 핀 드롭 곡선(`timingCurve(0.2, 0.8, 0.2, 1, duration: 0.36)`)과 대조
  - [ ] `PinShareSheet` `cubic-bezier(0.16, 1, 0.3, 1)` → iOS 시트 슬라이드 인/아웃 곡선(`timingCurve(0.16, 1, 0.3, 1, ...)`) 대조
  - [ ] 단일 줄 `lineHeight` 행간 정합(워드마크 등 1줄 타이틀) 최종 시각 확인
  - **완료 조건**: 화면별 픽셀 검수 통과 + 위 3개 easing 곡선의 시작/감속 체감이 웹과 일치.

---

## 4. TestFlight 빌드 + 내부 테스트 (DOD-B-4) — Mac 필요: ✅

- [ ] **완료 조건** — Xcode에서 릴리스 아카이브 → App Store Connect 업로드 → TestFlight 내부 테스터 그룹 배포 → 설치·실행 확인(폰트·화면 정합 포함).
  - **Mac 필요**: ✅ (Xcode 아카이브·업로드·서명은 Mac 전용)

---

## 5. 앱스토어 심사 제출 (DOD-B-5) — Mac 필요: ✅

- [ ] **완료 조건** — App Store Connect에서 앱 메타데이터·스크린샷·심사 정보 입력 후 심사 제출 완료(상태 "심사 대기/심사 중" 도달).
  - **Mac 필요**: ✅ (App Store Connect 제출 워크플로)

---

## 6. CI 연결 참고 (FR-7, Could — 이번 범위 제외)

색상 drift 가드를 CI에 연결하면 회귀를 자동 차단할 수 있다. 이번 P6 범위에는 포함하지 않으나, 후속 작업을 위한 참고 예시:

```yaml
# .github/workflows/*.yml 의 한 step 예시
- name: Design token drift guard
  run: node scripts/design-token-guard.mjs
```

- **Mac 필요**: ❌ (CI 러너에서 실행) / **상태**: 이번 범위 제외(Could).
