# Trust Ledger — iOS P6 (디자인 정합성 QA + 토큰 가드)

## 통합 감사 (review) — security-auditor
요약: CRITICAL 0 / HIGH 1 / MEDIUM 3 / LOW 2. 스크립트 보안(경로 탈출·ReDoS·시크릿·임의 코드 실행) 문제 없음. tokens.ts·백엔드 미수정(PRD 제외 범위 준수) 확인.

### [GAP/HIGH] GroupCreateView `.tracking(-1)` 미적용 → AC-7 불완전 충족
- 근거: 설계 분류표(a)·diff는 GroupCreateView 헤드라인을 보정 대상으로 명시했으나, 실제 파일은 P4 미구현 플레이스홀더(`Text("그룹 생성 — P4에서 구현")`)라 보정 대상 헤드라인 자체가 부재.
- 권고: (a) AC-7 범위에서 "미구현 화면 제외"로 재분류 + 설계 분류표 갱신 + TODO 주석, 또는 (b) 헤드라인 구현 후 적용.

### [ASSUMPTION/MEDIUM] PRD/설계 "색상 21키" 표기 ↔ 실제 22키 불일치
- 근거: tokens.ts colors = WGColor = 22키(bg~shadowMd). PRD FR-1/AC-1은 "21". 스크립트 동작은 동적(web.size)이라 무해하나 AC-1 문구 부정확.
- 권고: PRD FR-1/AC-1/설계 배경의 "21키"→"22키" 정정.

### [GAP/MEDIUM] parseIosColors 블록 regex 중첩 중괄호 조기 종료 위험(미래)
- 근거: `enum WGColor {([\s\S]*?)\n}` 는 첫 `\n}`에서 종료. 현재 단순 `static let` 나열이라 정상이나, 추후 computed property/헬퍼(중괄호) 추가 시 조기 종료.
- 권고: 주석에 한계 명시 또는 brace-counting 방식.

### [ASSUMPTION/MEDIUM] WelcomeWizard Step3Bot 분류표 불일치(정당하나 미문서화)
- 근거: 설계 분류표(a)에 Step3 포함되나 iOS는 2스텝(챗봇 제거)이라 대응 화면 없음. 코드는 2곳만 적용(정당).
- 권고: 설계 분류표에서 Step3Bot을 (b) BR-5 의도적 차이로 재분류.

### [GAP/LOW] normalize() throw → main() try/catch 미포착(미래 hsl/oklch/var)
- 권고: main() 비교 루프 try/catch로 미지원 형식 시 exit 1.

### [GAP/LOW] selftest가 missingInWeb 케이스 미커버(FR-2 양방향)
- 근거: selftest ①~④는 mismatch/missingInIOS/rgba만. iOS-only 키(missingInWeb) 역방향 미검증.
- 권고: missingInWeb fixture 추가로 FR-2 양방향 완성.

### 교차 검증 정합 항목
BR-1(tokens.ts SSOT)·BR-3(웹 미명시 tracking 금지)·FR-2(compare 양방향 수집)·AC-3(rgba 정규화)·QE-1(CWD 무관)·AC-6 재해석·AC-10 이연 — 모두 정합 확인.

## QA 리뷰 — qa-manager
- CERTAIN Warning 1: 성공 메시지 `${web.size}/${web.size}` → `${web.size}/${ios.size}`(또는 "N 키 모두 일치").
- Info 3: hex 분기 `#` 선택적(rgba 분기 선행이라 무해), selftest 케이스별 성공 로그 부재, LoginView 주석이 tracking↔foregroundStyle 사이 삽입(컨벤션 미세).
- QUESTION: ① GroupCreateView AC-7 추적, ② compare() missingInWeb strict 대칭 정책 의도 확인.

## 미답변/이월
- AC-11(Should): diff에 패딩/코너/폰트크기 보정 없음 — 명백한 수치 불일치 미발견으로 미적용(설계대로).
