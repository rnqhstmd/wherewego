# 자기점검 결과 (phase-implement)

> qa-manager 자동 리뷰 (2026-06-02). AC 17개 전부 코드 충족 확인. MUST-1/2/3 확인. 169 XCTest 통과(token 없이).

## 자동 수정 완료 (Critical + 스펙 갭)
- **[Critical] BR-4 입력 검증**: SearchPinViewModel.createPin + MapViewModel.addPinAtCenter에 좌표 범위(-90~90/-180~180)·장소명 ≤200자 클라이언트 검증 추가(`MapViewModel.validatePinInput` 공유). 위반 시 한국어 에러.
- **[스펙갭] BR-2 403**: 핀 생성 경로(Search/크로스헤어)에 GROUP_NOT_MEMBER → "권한이 없어요" 처리(`MapViewModel.message(for:)`).
- **[Warning] FR-24 순서**: 룰렛 stale 재조회를 `RouletteViewModel.spin()` 첫 부분 `await refreshPinsIfStale()`로 단일 보장(MapView fire-and-forget 제거).
- 재검증: 169 XCTest 통과.

## SELF_CHECK_FINDINGS (Warning/Info — phase-review 이월)
- [Warning] PinDetailViewModel.swift:39 - `unowned let mapViewModel` → View 재생성 엣지케이스에서 dangling 위험. 방어적으로 `weak`+옵셔널 권장(현재 패턴은 테스트와 정합, 즉시 위험 낮음).
- [Info] MapViewModel `refreshPinsIfStale` fire-and-forget 의도 주석 권장.
- [Info] CreatePinRequest `memo:nil`/`instagramUrl:nil`이 일반 Encodable이라 `{"memo":null}` 전송 — 백엔드 toCommand()가 null 정규화하므로 안전(UpdatePinRequest는 MUST-2 custom encode). 주석 명시 권장.

## SELF_CHECK_QUESTIONS (phase-review에서 사용자 확인)
- [해소됨] confirmVisit 낙관 미반영 → **의도된 UX로 수용**(서버 응답 후 MEMORY 전환 — confetti/transitionedToMemoryNow 타이밍). 변경 안 함.

## DoD 상태
- **DoD-A(머지 게이트)**: token 없이 `xcodebuild build/test` 통과 ✅, 169 테스트 그린 ✅, MUST-1 격리(`import MapboxMaps`==MapboxMapView.swift 1개) ✅, 권한 키 project.yml 존재 ✅.
- **DoD-B(token 후 체크리스트)**: MapboxMapView.swift `#if` 실구현(마커/flyTo/fitBounds/클러스터/제스처) — secret download token(.netrc)+public token 발급 후 컴파일·실기기 검증.
