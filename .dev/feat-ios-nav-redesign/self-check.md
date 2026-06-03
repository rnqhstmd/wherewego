# 자기점검 결과 — P7 iOS 내비게이션 재설계

advisor: qa-manager (sonnet)
date: 2026-06-03

## Critical (자동 수정 완료)
- [Critical→수정완료] `NotificationInboxView` VM 이중 소유 — `@StateObject`+autoclosure로 새 인스턴스 생성 → MainTabView 소유 VM(배지 unreadCount)과 분리되어 읽음 후 배지 안 사라짐. → `@ObservedObject var viewModel` + 단순 init 주입으로 수정. 단일 인스턴스 공유.
- [Critical→수정완료] `MapView.init(dependencies:)` 죽은 코드(호출처 0건 재확인) — 실수 호출 시 MainTabView 소유 VM과 분리되어 flyTo 깨짐. → 삭제, `init(viewModel:)`만 잔존.

## Warning (자동 수정 완료)
- [Warning→수정완료] `MyInfoViewModel.deleteAccount()` 성공 경로 `isBusy=false` 누락 → 로그아웃 전환 실패 시 고착. → `defer { isBusy = false }` 통일(`logout()`/`leaveGroup()`도 동일 정렬).

## Warning/Info (phase-review 이월)
- [Warning] `NotificationInboxViewModel.load()` 에러 후 재시도 시 `loadState=.loading`이 기존 items를 덮어 목록 깜빡임(UX). 재시도 시 기존 items 유지 검토.
- [Info] `NotificationInboxViewModel.selectItem` 실패 시 `activeDetail=nil` 무음 처리 → 탭 무반응 UX. 에러 노출 또는 미변경 검토.
- [Info] `FloatingTabBar` iOS26 `if #available` 분기 양쪽 동일 코드(의도적 `// TODO(DoD-B)` — Liquid Glass modifier는 Mac/Xcode26 보정).
- [Info] `AddPlaceViewModel` debouncer 클로저 내 `Task{ self? }` weak 캡처 — 해제 시 무음 실패 가능(취약 패턴, 기능 결함 아님).

## QUESTION (직접 해소 — 이월 없음)
- init(dependencies:) 의도 → 삭제(호출처 0).
- StateObject vs ObservedObject → ObservedObject(단일 인스턴스).
- AC-5/8/9 테스트 부재 → 자기점검 시점엔 미작성이 정상. Step 7(테스트 작성)에서 작성.
