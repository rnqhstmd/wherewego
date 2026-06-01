# Cross-Review 결과

- advisor: claude (qa-manager + security-auditor)
- 브랜치: feat/ios-native-p4-map-pin-photo (base: develop)
- DEV_DIR: .dev/feat-ios-native-p4-map-pin-photo
- 대상: PR #91 (3커밋: 구현 + context + Gemini 수정)

## AC 충족 매트릭스

**[Must] 17/17 충족** (AC-1~17 전수 코드 충족, 근거 파일:라인 확인). [Should] AC 항목 없음.
- AC-1 token 없이 빌드(#if/#else 격리) / AC-2 PinAPI·PlaceAPI 테스트(multipart 401 포함) / AC-3 PinSummary 18필드 / AC-4 태그필터 / AC-5 빈상태 / AC-6 낙관 태그+롤백 / AC-7 삭제 다이얼로그+낙관 / AC-8 크롭→JPEG→multipart / AC-9 MEMORY만 사진 / AC-10 룰렛 exhausted / AC-11 flyTo / AC-12~14 방문감지 게이트·세션차단 / AC-15 transitionedToMemoryNow=false 분기 / AC-16 권한 문구 / AC-17 IG https.

## 설계 범위 이탈

이탈 없음. 신규 테스트 5개(Crosshair/MapCacheAndPolling/PinDetailViewModel/RouletteViewModel/VisitOrchestration)는 설계 "순수 헬퍼 단위테스트"·Should 검증 범위 내. MutableClock 주입으로 flaky 없음.

## 신규 위험 (trust-ledger·Gemini 수정 제외)

### Warning
- [MAINT] RouletteViewModel.spin() — `guard let mapViewModel else { return }` 실패 시 `state`가 `.spinning` 고착(무한 스피너). → **수정**: nil 시 `.locationError`로 전이.

### MEDIUM (ZT)
- [GAP] VisitMemoSheet finish() — `activeSheet=.none` + `selectedPinId` 동일 사이클 설정 → 두 `.sheet(item:)` 전환 경쟁으로 PinDetail 미오픈 가능. → **수정**: 시트 전환 시퀀싱(dismiss 후 selectedPinId).
- [ASSUMPTION] APIClient.makeURL — `?` 경로에서 percentEncodedQuery 덮어쓰기(단일 파라미터만). 현재 PlaceAPI만 사용해 안전. → **수정**: 주석 명시.
- [GAP] 룰렛 computeTagsAllowed 빈 Set(필터 전체 OFF) → exhausted가 "필터 OFF"와 "핀 없음" 미구분. PRD 미요구. → **P6 이월**.

### LOW (ZT)
- [ASSUMPTION] VisitDateFormatter nonisolated(unsafe) static — 읽기 전용 사용으로 실질 안전(OnboardingFlags 선례). → 유지.
- [GAP] SearchPinSheet 검색 디바운싱/버튼 비활성화 미확인. → **수정**: isSearching 중 버튼 disabled 확인·보강.

### Info
- [SPEC] EmptyMapCard가 `pins.isEmpty`(필터 미반영) 기준 — 필터 전체 OFF 시 빈 화면이나 EmptyMapCard 미표시. PRD 미요구. → **P6 이월**.

## references 위반
위반 없음 (references/ 디렉토리 없음).

## 총평
- 강점: AC 17/17 코드 충족, MUST-1/2/3·BR-1~6 정합, DoD-A(173 XCTest token 없이 통과), trust-ledger·Gemini 수정 항목 재발 없음.
- 합산: Critical/CRITICAL 0, Warning 1, MEDIUM 3, LOW 2, Info 1.
- 권고: Warning(룰렛 스피너 고착)·MEDIUM(VisitMemoSheet 시퀀싱)·makeURL 주석·SearchPin 버튼 가드 수정 후 머지. 필터-OFF UX는 P6 디자인 정합 단계 이월.

## 처리 결과
- 수정: RouletteViewModel 스피너 고착, VisitMemoSheet 시트 시퀀싱, makeURL 주석, SearchPin 버튼 가드 → coder 위임.
- P6 이월: 필터 전체 OFF UX 명확화(EmptyMapCard·룰렛 메시지). VisitDateFormatter 주석 유지.
