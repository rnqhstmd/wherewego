# 자기점검 — GC-2 iOS 그룹 채팅

## 변경 규모
32 파일, +1887/-205. 신규 코드 7(GroupChatModels·InstagramURL·GroupChatViewModel·GroupChatView·GroupMessageRow·ReelRegisterSheet·ChatPushSignal) + 신규 테스트 3(GroupChatModelsTests·GroupChatViewModelTests·InstagramURLTests) + 수정 다수.

## Critical
- 없음.

## 자기 코드 리뷰 — 정합성 점검(이상 없음)
- ChatAPIProtocol 추가 메서드(groupRooms/groupMessages/sendGroupMessage/extract) → ChatAPI 구현 + StubChatAPI stub 모두 정합(테스트 컴파일 유지).
- 딥링크 `.reelFocus(groupId:instagramUrl:)` 시그니처 변경 → 전 사용처 갱신(DeepLinkRouter 정의·MapViewModel.focusReel·MainTabView.consumePending·BotChatViewModel.showOnMap(dead)·PlaceCardSaveTests).
- ReelSaveResult 공용 이동(GroupChatModels) + BotChatViewModel 중복 정의 제거(GC-3 봇 삭제 후 보존).
- GroupChatFrame: 디코더 init + 메모리 init 공존(테스트용), registered 교체-병합 reconcile(false→true 자기치유).
- DMListViewModel(chatAPI:currentUser:) 변경 → MainTabView 주입 + DMListViewModelTests 갱신.
- willPresent async 델리게이트로 전환 — completionHandler 캡처 Swift6 동시성 회피, Sendable 값(type/roomId)만 await 경계 통과.
- XcodeGen `sources: WhereWeGo/WhereWeGoTests/ShareExtension` 폴더 자동 스캔 → 신규 파일 pbxproj 등록 불요(CI 자동 포함).
- ShareExtension 엔드포인트만 교체(groupRooms/sendReelLink, ShareGroup DTO 무변경 — groupId/groupName 호환).

## Warning/Info (phase-review 이월)
- [Info] 딥링크 레벨0→레벨1 진입 시 그룹 핀 1회 중복 로드(MapView.task load + focusReel.switchTo) — 설계 명시 수용(idempotent, 카메라는 focusReel.fitBounds 최종). DoD-B 시각 검증.
- [Info] reconcile registered 갱신은 최신 페이지(20건) 범위 — 설계 "자기치유" 정합(과거 메시지는 재진입/load 시 갱신).
- [Info] DeepLinkRouter 상단 주석 블록 push type 매핑에 GROUP_MESSAGE 미기재(switch 코드는 정확 — 주석 일관성만).
- [Info] Windows 빌드/단위테스트 불가 → GitHub Actions CI(xcodebuild, macos-15)가 검증. 시각/실기기 = Mac DoD-B.

## QUESTION
- 없음.
