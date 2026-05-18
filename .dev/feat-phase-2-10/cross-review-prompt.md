<task>
ttutak 파이프라인 산출물(PRD/설계/Trust Ledger/자기점검/코드 맵)과 변경 코드를 교차 검증한다.
변경된 코드가 산출물의 약속을 충족하는지, 산출물에 정의되지 않은 신규 위험이 있는지 보고한다.

프로젝트 루트(절대 경로): /Users/bonseung/projects/wherewego/

diff 파일: /Users/bonseung/projects/wherewego/.dev/feat-phase-2-10/diff.txt
이 파일을 Read하여 변경사항을 확인한다. 변경된 파일을 직접 Read해야 정확히 판단할 수 있는 경우 절대 경로로 추가 Read를 수행한다.
diff는 develop 머지 베이스(109ac408)부터의 누적 변경이며, .dev/ 산출물은 제외되어 있다 (검토 대상 한정).
</task>

<grounding_rules>
- 모든 지적은 PRD 또는 설계서의 정확한 인용으로 근거를 제시한다.
- trust-ledger.md에 이미 보고된 항목은 보고하지 않는다 (중복 금지).
- self-check.md의 Warning/Info/QUESTION은 중복 보고하지 않는다.
- 코드를 직접 확인하지 못한 추정은 ASSUMPTION으로 분리한다.
- PRD 자체가 코드와 일치하지 않을 가능성이 의심되면 ASSUMPTION으로 분류한다.
- 본 cross-review는 review 1회차 + 자기점검에서 [HIGH/RISK] coordinateError 미표시 결함이 발견되어 PinPopup.tsx에 useEffect 추가로 해소된 상태다. 이미 해소된 항목을 다시 보고하지 않는다.
</grounding_rules>

<structured_output_contract>
다음 5개 섹션을 정확히 이 순서로 출력한다. references 위반은 references/가 없으므로 본문에 "위반 없음"으로 적되 섹션 헤더는 유지한다.

## AC 충족 매트릭스
표 형식. 각 AC에 대해 충족(O), 미충족(X), 부분(부분) 판정 + 근거 파일:라인.

## 설계 범위 이탈
설계서 §1 "변경 범위 요약"에 명시되지 않은 파일 수정 목록. 항목별로 파일 경로 / 변경 요약 / 이탈 사유 추정. 없으면 "이탈 없음"이라고 적되 섹션 헤더는 유지.

## 신규 위험
trust-ledger.md에 없는 신규 risk/policy/gap/assumption만.
- [Critical/Warning/Info] [RISK/POLICY/GAP/ASSUMPTION] 항목 설명
  - 위치: 파일:라인
  - 근거: ...
  - 권고: ...

## references 위반
"위반 없음" (references/ 디렉토리 자체가 없음)

## 총평
- 강점 1-2개
- Critical/Warning/Info 합산
- 머지 전 권고 사항 1줄
</structured_output_contract>

<language>
모든 출력은 한국어로 작성한다. 영어 단어는 고유명사·기술 용어에 한해 허용한다.
</language>

<artifacts>
다음 산출물을 절대 경로로 Read하여 참조한다.

### PRD (전체)
/Users/bonseung/projects/wherewego/.dev/feat-phase-2-10/prd.md

핵심 참조 섹션: §2 요구사항, §4 수용 기준(AC-1~AC-15), §5 NFR, §6 위험/가정, §7 영향 도메인

### 설계서 (전체)
/Users/bonseung/projects/wherewego/.dev/feat-phase-2-10/design.md

핵심 참조 섹션: §1 변경 범위 요약(신규 2 + 수정 13 + 테스트 2), §2 백엔드 설계, §3 프론트엔드 설계, §7 구현 순서, §8 테스트 전략, §9 위험/대응

### 기존 Trust Ledger (이미 보고된 항목, 중복 금지)
/Users/bonseung/projects/wherewego/.dev/feat-phase-2-10/trust-ledger.md

본 PR의 review 1회차에서 보고된 항목들이 포함되어 있다. CRITICAL 0, HIGH 4, MEDIUM 5, LOW 4 + QA Warning 2 + Info 2 + QUESTION 2 (자기점검 이월 + 신규).

### 자기점검 (중복 금지)
/Users/bonseung/projects/wherewego/.dev/feat-phase-2-10/self-check.md

phase-implement 자기점검에서 발견된 Critical 1건(자동 수정 완료), Warning 1, Info 1, QUESTION 2 항목.

### 코드 맵 (탐색 가이드)
/Users/bonseung/projects/wherewego/.dev/feat-phase-2-10/codemap.md

§핵심 파일 섹션에 백엔드/프론트엔드/맵 도메인의 주요 파일 24개가 정리되어 있다.

### references (외부 표준)
없음. references/ 디렉토리 자체가 존재하지 않는다.
</artifacts>

<additional_context>
본 PR은 c14fe91(review 후속) + ee67048(본 작업)의 2 커밋으로 구성된다.

[HIGH/RISK] coordinateError 미표시 결함은 review 1회차에서 발견되어 c14fe91에서 PinPopup.tsx line 104~109에 useEffect 추가로 해소되었다. 이 항목은 이미 trust-ledger에 "해소 완료"로 기록되어 있다.

자기점검 이월 QUESTION 2건도 trust-ledger에 명시되어 있고 사용자 결정(현 코드 유지 / Number 캐스팅 유지)이 완료되었다.

cross-review에서 중점적으로 점검해야 할 영역:
1. AC-1~AC-15 충족 여부 (특히 AC-4의 인라인 에러 표시 완전 충족 확인)
2. 설계서 §1 "변경 범위 요약" 외 추가 파일 수정이 있는지
3. trust-ledger/self-check에 없는 신규 위험
4. NFR 4건의 실제 충족 여부 (회귀 안전성, 낙관적 UI 일관성, 에러 응답 일관성, SOP 운영자 단독 수행 가능성)

특히 검토 권장:
- backend/.../domain/pin/Pin.java의 changeCoordinate가 검증을 Command에 위임하는 구조의 적절성
- frontend/src/app/map/MapClient.tsx의 ActiveSheet union 확장(coordinate-edit) 및 startOptimisticTransition 분기
- frontend/src/app/map/_components/PinCoordinateEditPicker.tsx 신규 컴포넌트의 mapboxToken prop 미사용 (의도된 미래 확장 여지)
- context/map/mapbox-token-sop.md + mapbox-env.md 운영자 단독 수행 가능성 (NFR-4)
- PinV1ControllerIntegrationTest의 좌표 IT 5케이스 커버리지 (AC-1, 2, 3, 5, 6 + XOR)
</additional_context>
