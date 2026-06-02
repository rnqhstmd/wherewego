# Cross-Review 결과

- advisor: claude (qa-manager + security-auditor)
- 브랜치: feat/ios-native-p6-design-qa (base: develop)
- DEV_DIR: .dev/feat-ios-native-p6-design-qa
- 실행: 2026-06-02 (PR #93 생성 후 단발)

## AC 충족 매트릭스
| AC | 분류 | 충족 | 근거 |
|----|------|------|------|
| AC-1~4 | Must | O | design-token-guard.mjs 실행 검증(22/22 exit0, selftest 5케이스) |
| AC-5 | Must | O | LoginView.swift:36 `.tracking(-1.5)` |
| AC-6 | Must | O(재해석) | LoginView.swift:37 lineSpacing 미적용+Mac 이연 주석(D-MA2) |
| AC-7 | Must | O | 구현 화면 6곳 적용. GroupCreateView=P4 플레이스홀더 제외(TODO), Step3Bot=iOS 2스텝 미대응(BR-5) |
| AC-8/9 | Must | O | dod-b-checklist.md 산출, 각 항목 Mac필요·완료조건 명시 |
| AC-10/11 | Should | O(이연/미적용) | easing Mac 이연, 레이아웃 명백한 불일치 없어 미적용 |

[Must] 9/9 충족, [Should] 2/2 정책 처리.

## 설계 범위 이탈
**이탈 없음.** 변경 7파일 모두 설계 "변경 범위" 내. tokens.ts·Theme.swift·easing 파일군 미수정(설계 "수정하지 않음" 일치).

## 신규 위험
trust-ledger 6항목 재발 0건(전수 재확인). 신규 CRITICAL/HIGH 0.
- [MEDIUM] normalize() hex 분기 `#?` 앵커 미적용 → 미래 `Color(.systemRed)` 등 오탐 위험. (Gemini :112와 수렴) → **반영(앵커 추가)**.
- [LOW] selftest main() end-to-end 경로 미커버(FR-7 CI 시 보강 권장).
- [LOW/Info] WelcomeWizard 설계표 명칭(step1Group/step2Invite) 구체화 권장 / NicknameView 수식어 순서(무해).

## PR #93 Gemini 리뷰 (병합 반영)
- [HIGH] design-token-guard.mjs 빈 맵 파싱 시 silent exit 0 가드 무력화 → **반영**(web.size/ios.size===0 시 exit 1).
- [MEDIUM] parseWebColors 싱글쿼트 미지원 → **반영**(`["']` 허용).
- [MEDIUM] hex 정규식 앵커 누락 → **반영**(`^...$`).
- [MEDIUM] design.md "21/21"·codemap "21키" → **반영**(22 정정).

## 총평
- 강점: 가드 견고성(CRLF·앵커·직접실행가드·selftest 양방향) 선제 보완, tracking 추적 주석 일관성.
- 합산: 신규 Critical 0. Gemini HIGH 1 + MEDIUM 3 + cross-review MEDIUM 1(수렴) 전부 반영.
- 권고: 리뷰 통과. 반영 후 재실행 불필요(코드 회귀 검증 완료).

## 처리 결과
- 코드 3건(빈맵가드·싱글쿼트·hex앵커) + 문서 2건(design/codemap 22키) 반영. 가드 재검증 22/22 exit0·selftest exit0.
- [LOW] selftest main() e2e·WelcomeWizard 명칭은 영향 경미로 이번 미반영(기록만).
