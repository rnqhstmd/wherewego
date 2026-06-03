# 자기점검 결과 — iOS P6

## 요약
- qa-manager 자기점검 1회 패스 수행. **CERTAIN Critical 없음.** 견고성 Warning 3건은 가드 본연 목적(회귀 방지)을 무력화할 수 있어 즉시 수정·재검증 완료.

## SELF_CHECK_FINDINGS (수정 완료)
- [Warning→Fixed] `scripts/design-token-guard.mjs` parseIosColors/parseWebColors — **CRLF 취약점**. git autocrlf 활성 환경에서 신규 체크아웃 시 Theme.swift가 CRLF가 되면 `\n}` 정규식이 깨져 전체 키 누락 오탐 발생. → 두 파서에 `src.replace(/\r\n/g,"\n")` CRLF 정규화 추가. CRLF 시뮬레이션으로 22/22·mismatch 0 검증.
- [Warning→Fixed] `parseWebColors` 블록 앵커 — `colors\s*=` → `export\s+const\s+colors\s*=`로 강화. 다른 `colors`/`fonts` 블록 혼동 방지(QUESTION-2 반영).
- [Info→Fixed] 최상위 `main()` 무조건 호출 → import 시 부작용(process.exit)으로 테스트 불가. `if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)` 직접실행 가드 추가. 깨끗한 import 검증 완료.
- [Doc→Fixed] 헤더 주석 "21키" → "22키" 정정(실제 키 수 일치).

## 검증 증거 (재실행)
- 직접 실행: `✓ 색상 22/22 키 일치` exit 0 (AC-1)
- selftest: `✓ selftest 통과` exit 0 (AC-2~4)
- CRLF 내성: iOS키 22 / web키 22 / missing 0,0 / mismatch 0
- 깨끗한 import: main() 미실행, exit 0

## SELF_CHECK_QUESTIONS (phase-review 이월)
- [QUESTION-1] AC-6(LoginView 워드마크 lineHeight:1.05) 처리: 단일줄이라 `.lineSpacing` 효과없음 → Mac QA(DoD-B-3) 이연. **이는 설계 단계 D-MA2 결정과 일치**(qa-manager는 해당 결정을 모른 상태로 질문). 잔여 조치: **PRD AC-6 문구를 design.md 재해석(Mac 이연)과 정합되도록 갱신** 필요(문서 일관성). 기능상 결함 아님.
- [QUESTION-2] parseWebColors 앵커 강화: **자기점검에서 이미 적용함**(`export const colors`). 해소됨.

## AC 충족 현황 (자기점검 시점)
| AC | 결과 |
|----|------|
| AC-1~4 | ✅ 가드 실행·selftest 검증 |
| AC-5 | ✅ LoginView 워드마크 `.tracking(-1.5)` |
| AC-6 | ⚠️ Mac 이연(D-MA2 결정). PRD 문구 갱신 잔여 |
| AC-7 | ✅ 온보딩 4뷰 헤드라인 `.tracking(-1)`. 태그라인 BR-3 준수. GroupCreateView=P4 플레이스홀더(헤드라인 부재)로 정당 미적용 |
| AC-8/9 | ✅ dod-b-checklist.md 산출 |
| AC-10 | Mac 이연(DoD-B) |
| AC-11 | 명백한 수치 불일치 없어 미적용(설계대로) |
