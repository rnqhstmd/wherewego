# PRD: iOS P6 — 디자인 정합성 최종 QA (웹↔앱) + 디자인 토큰 단일소스 가드

## 확정 결정 (사용자 승인)
- **D1 (FR-1 가드 방식)**: **Node.js 비교 스크립트**. `tokens.ts`와 `Theme.swift`를 텍스트 파싱·비교하는 단일 스크립트. 기존 파일 구조 유지, `node <script>` 단독 실행. (코드 자동생성 방식 미채택)
- **D2 (FR-3 보정 범위)**: **전체 일괄 보정**. 웹 레퍼런스에 값이 명시된 항목을 전 화면에서 모두 적용 (P6 "전체를 한 번에 대조" 취지).
- **D3 (폰트 수급)**: **다운로드 필요**. 각 폰트 공식 배포처에서 수급 → DoD-B 체크리스트에 다운로드 출처 링크 포함.

## 배경
- iOS 앱(SwiftUI)은 P1~P5로 기능 완료. 디자인 토큰은 `tokens.ts` → `Theme.swift`(WGColor/WGFont) 1:1 이식 구조.
- 색상 22키는 현재 값 일치하나, 웹 `shadow`/`shadowMd`는 `rgba()` 문자열 / iOS는 hex+opacity → 자동 비교 없이는 drift 감지 불가.
- 폰트 4종(NotoSerifKR-Regular, GowunBatang-Regular, Pretendard-Regular, JetBrainsMono-Regular)은 Info.plist `UIAppFonts`에 등록됐으나 `Resources/Fonts/`는 `.gitkeep`만 존재 — 폰트 파일 미번들.
- 웹 LoginClient는 `letterSpacing: -1.5`, `lineHeight: 1.05` 명시 / iOS LoginView는 `.tracking()`·`.lineSpacing()` 미적용.
- 웹 easing은 `cubic-bezier(...)` / iOS는 `.easeOut`·`.easeInOut`만 사용.
- Windows 제약: 시뮬레이터 구동·TestFlight·제출은 범위 밖 → 코드 레벨 보정 + Mac 잔여 체크리스트가 목표.

## 목표
- `tokens.ts` ↔ `Theme.swift` 색상 drift를 코드만으로 자동 탐지하는 가드 구축.
- 전 화면(11개 도메인)의 폰트·letter-spacing·line-height·easing 값을 웹 레퍼런스 기준 정적 대조·보정.
- 폰트 파일 미번들 갭 식별 + 번들 완성 요건 문서화.
- Mac에서만 완결 가능한 항목(시각 픽셀 QA, TestFlight, 제출)을 체크리스트로 산출.

## 요구사항

### 기능 요구사항
- **[Must] FR-1**: `tokens.ts` 색상 22키 ↔ `Theme.swift` WGColor 22키 비교 **Node.js drift 감지 스크립트**. `rgba(R,G,B,opacity)`를 hex+opacity로 정규화 비교, 불일치/키누락 시 비-0 종료 코드.
- **[Must] FR-2**: 새 토큰이 한쪽에만 추가될 때(키 누락)도 실패 감지.
- **[Must] FR-3**: iOS 전 화면(81 Swift 파일)에서 웹 레퍼런스 대비 누락된 `.tracking()`/`.lineSpacing()` **전체 일괄 보정**. 최우선: LoginView 워드마크(`letterSpacing: -1.5`), 태그라인.
- **[Must] FR-4**: iOS 폰트 파일 번들 상태 검증. 4종 폰트 파일 부재 시 번들 완성 요건과 Info.plist 등록 상태를 체크리스트로 문서화.
- **[Should] FR-5**: 웹 `cubic-bezier(0.2,0.8,0.2,1)` 계열(말풍선 팝, 핀 드롭)을 iOS `.timingCurve(c0x:0.2,c0y:0.8,c1x:0.2,c1y:1.0)`로 대응.
- **[Should] FR-6**: 웹 레퍼런스에 수치 명시 + iOS 코드에 상이한 패딩/코너 반경/폰트 크기 보정.
- **[Could] FR-7**: drift 가드를 GitHub Actions 워크플로 단계로 연결.

### 비즈니스 규칙
- **[Must] BR-1**: drift 가드 비교 기준은 `tokens.ts`. 불일치 시 `Theme.swift`를 수정한다.
- **[Must] BR-2**: 폰트 미번들 시 시스템 폰트 폴백으로 브랜드 경험 손상. 폰트 번들 완성은 Mac 잔여 체크리스트에 포함하되 라이선스(Pretendard: SIL OFL, Noto Serif KR: OFL, Gowun Batang: OFL, JetBrains Mono: Apache 2.0) 명시.
- **[Must] BR-3**: 정적 대조 대상 범위 = (1) 폰트 패밀리·크기, (2) letter-spacing(`.tracking()`), (3) line-height(`.lineSpacing()`), (4) 웹에 명시적 easing 값이 있는 애니메이션. 웹에 값 미명시 속성은 제외.
- **[Must] BR-4**: Mac 잔여 항목은 "DoD-B 체크리스트" 문서로 산출. 항목별 "Mac 필요 여부" + "완료 조건" 명시.
- **[Should] BR-5**: 웹 전용 요소(GlobeBg, 데스크톱 ActionPill, Mapbox CSS 등)는 대조 제외, "의도적 차이"로 분류.

### 품질 기대
- **[Should] QE-1**: drift 가드는 `node` 단독 실행(별도 빌드 없이)으로 Windows/Mac/CI 어디서든 동작.
- **[Should] QE-2**: 정적 대조 보정 결과는 화면별 변경 항목·전/후 값을 주석 또는 커밋 메시지로 추적 가능.

## 수용 기준

### 코드측 (Windows 검증 가능)
| # | 수용 기준 | 연결 |
|---|-----------|------|
| AC-1 | drift 가드를 `node <script>` 단독 실행 시 색상 22키 모두 일치하면 종료 코드 0 | FR-1, BR-1 |
| AC-2 | `tokens.ts` 임의 색상 변경/키 추가 후 실행하면 비-0 종료 + 어느 키·값 불일치인지 콘솔 출력 | FR-1, FR-2 |
| AC-3 | `rgba(26,26,46,0.08)`와 `#1A1A2E` opacity 0.08을 동등 판정(정규화 비교) | FR-1, BR-1 |
| AC-4 | `Theme.swift` 키 누락 상태에서 실행 시 "키 누락" 메시지 + 실패 종료 | FR-2 |
| AC-5 | LoginView 워드마크에 `.tracking(-1.5)` 적용 | FR-3, BR-3 |
| AC-6 | LoginView 워드마크에 `.tracking(-1.5)` 적용 + `lineHeight:1.05`는 단일줄이라 `.lineSpacing` 무효 → 주석으로 Mac QA(DoD-B-3) 이연 (D-MA2 재해석) | FR-3, BR-3 |
| AC-7 | 전 화면에서 웹에 `letterSpacing` 명시된 텍스트가 iOS `.tracking()`으로 대응 적용(웹 미명시 항목은 미적용) | FR-3, BR-3 |
| AC-8 | DoD-B 체크리스트 문서 산출: 폰트 4종 번들+다운로드 출처, Info.plist 등록 확인, 라이선스 명시, 시뮬레이터 렌더 확인, TestFlight 빌드, 앱스토어 제출 포함 | FR-4, BR-2, BR-4, D3 |
| AC-9 | DoD-B 각 항목에 "Mac 필요 여부" + "완료 조건" 명시 | BR-4 |

### [Should] 코드측 보완
| # | 수용 기준 | 연결 |
|---|-----------|------|
| AC-10 | 웹 `cubic-bezier(0.2,0.8,0.2,1)` 애니메이션이 iOS `.timingCurve(...)` 또는 동치 Animation으로 대응 | FR-5 |
| AC-11 | 웹에 수치 명시 + iOS 상이한 패딩/코너/폰트크기 보정(전/후 값 추적 가능) | FR-6, QE-2 |

### Mac 잔여 (DoD-B 체크리스트 — 이번 실행 범위 밖)
| # | 항목 | 완료 조건 |
|---|------|-----------|
| DOD-B-1 | 폰트 4종 다운로드 + `Resources/Fonts/` 추가 + Info.plist 등록 확인 | 시뮬레이터 커스텀 폰트 렌더 확인 |
| DOD-B-2 | 폰트 라이선스 확인(Pretendard OFL, Noto Serif KR OFL, Gowun Batang OFL, JetBrains Mono Apache 2.0) | 라이선스 기록 보관 |
| DOD-B-3 | 전 화면 시뮬레이터 시각 픽셀 대조(웹 스크린샷 vs iOS) | 화면별 검수 완료 |
| DOD-B-4 | TestFlight 빌드 업로드 + 내부 테스트 | TestFlight 배포 확인 |
| DOD-B-5 | 앱스토어 심사 제출 | 앱스토어 커넥트 제출 완료 |

## 제외 범위
- 실제 시뮬레이터 구동, 시각 스크린샷 비교(Mac 필요).
- TestFlight 빌드·앱스토어 제출(Mac 필요).
- 폰트 파일 자체 수급·Xcode 번들 작업(Mac 필요) — 단 다운로드 출처는 DoD-B에 명시.
- 웹 전용 UI(GlobeBg, 데스크톱 ActionPill, Mapbox CSS) iOS 이식 — 의도적 차이.
- 백엔드 변경 없음. `tokens.ts` 수정 없음.
