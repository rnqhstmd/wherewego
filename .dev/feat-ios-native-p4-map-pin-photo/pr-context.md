# PR 컨텍스트 — P4 iOS 지도·핀·사진·방문감지

> base: **develop** (iOS 전환 트랙). 의존 P1·P3 머지 완료.

## 비즈니스 맥락

WhereWeGo 웹→SwiftUI iOS 네이티브 전환의 **4단계(최대 공수 Phase)**. P1(백엔드 인증)·P3(iOS 골격·인증·온보딩) 위에 앱의 핵심 가치인 **커플 공유 지도 경험**을 SwiftUI로 포팅한다. 기존엔 온보딩 종착(`GroupsView` 플레이스홀더)에서 멈췄던 흐름을, 그룹 진입 후 실제 지도 화면으로 연결한다. 웹 `MapClient.tsx`(Mapbox GL JS)의 지도 렌더·핀 CRUD·태그/메모·룰렛·방문감지를 Mapbox iOS SDK 기반으로 재현한다. `ios/**`만 변경 → 라이브 배포 무관.

### 핵심 요구사항 (PRD)
- 지도 렌더(태그별 마커 REEL/WISH/MEMORY·클러스터·필터·빈 상태), 카메라(flyTo/fitBounds)
- 핀 CRUD(낙관적 업데이트+롤백), 정보창(태그·메모·장소명·삭제·Instagram https 가드)
- 장소 검색→태그 선택→추가, 크로스헤어 임의좌표 추가
- 사진(MEMORY 핀): PHPicker + SwiftUI 자작 1:1 크롭 + 1600px JPEG 압축, multipart 업로드/삭제
- 룰렛: 반경 확장 추첨, 지도에서 보기/재추첨/MEMORY 토글
- 방문감지(포그라운드): WISH·REEL 100m·30초 체류 → MEMORY 전환 제안 + confetti + 메모

### 확정 결정
- **Mapbox: 배선 우선·토큰 나중** — `#if canImport(MapboxMaps)` 단일 파일 격리 + 플레이스홀더. token 없이 빌드·테스트 통과(DoD-A), 실렌더링은 secret download token(.netrc)+public token 발급 후 검증(DoD-B).
- 방문감지 정확도 게이트 **50m**(PRD/AC-12 일치, 웹 운영값 100m와 다름 — 코드 주석 명시).
- 사진 크롭 **SwiftUI 자작 1:1**.

## 검증 상태
- **XCTest 170개 통과(0 failures)** — token 없이 `xcodebuild test`(iOS 26.5 시뮬레이터, ad-hoc 서명). AC-1~17 + MUST-1/2/3 커버.
- MUST-1 격리: `grep -rl "import MapboxMaps" ios/WhereWeGo` == `MapboxMapView.swift` 1개.
- 인수 검증 ACCEPT([Must] AC-1~17 충족).

## Audit Summary
- 총 13건 (CRITICAL: 0, HIGH: 3, MEDIUM: 5, Warning 3 / LOW 2)
- [Critical/QA] scenePhase 백그라운드 복귀 시 방문감지 미재개 → **수정 완료**(stop/startVisitDetection)
- [HIGH-1] PlaceAPI 검색 쿼리 인젝션(=,&,+ 미인코딩) → **수정 완료**(값 전용 문자셋 + 방어 테스트)
- [HIGH-3] SquareCropView 제스처 취소 시 크롭 rect 오산 → **수정 완료**(commit/임시 분리)
- [MEDIUM-3] confirmVisit 실패 시 무한 토스트 루프 → **수정 완료**(세션 재토스트 차단)
- [MEDIUM-1] 메모 ≤500자 API 계층 검증 누락 → **수정 완료**
- 수용/이월: 204 NO_CONTENT 경계(동작 정확), 빈 메모 skip(설계), reRoll 위치실패, authorizedAlways(포그라운드 전용). 자세한 내용은 Trust Ledger 참조.

## 후속(DoD-B, 이 PR 머지 게이트 아님)
secret download token(.netrc)+Mapbox public token 발급 → project.yml SPM 주석 해제 → `xcodegen generate` → `#if` 분기 컴파일·실기기 E2E(지도 렌더·핀 CRUD·사진·방문감지) 검증.
