# 자기점검 결과 (IG-2, 2026-06-12)

## CERTAIN (자동 수정 완료)
- [Critical/해소] NotificationInboxView.thumbnail — 확정 스펙 Q3(핀 사진 없으면 **생략**)와 달리 thumbnailUrl nil 시 회색 placeholder 타일을 표시 → nil 시 썸네일 자체 생략으로 수정(URL 있고 로딩/실패 중에만 타일).

## 오케스트레이터 직접 수정 (배치 검증 중)
- GroupChatViewModel.disappear(): cancel 후 폴링 태스크 종료를 await — reconcileLatest 플래키 근원(in-flight reconcile 가 disappear 반환 후 완료) 하드닝.
- MapViewModel.focusPins(): 단일 핀 카메라를 직접 cameraCommand 대신 기존 flyTo(lat:lng:) 재사용(MUST-1 규약 일관).

## Warning (phase-review 이월)
- [Warning] GroupChatView 헤더 — group(GroupSummary) nil(목록 미로딩 직진입·딥링크 진입)이면 멤버 수 생략 + 이니셜 폴백. 시각 확인은 Mac DoD-B.
- [Warning] 채팅 입력바 전송 아이콘이 isReel 여부로 paperplane/arrow.up 분기 유지(기존 동작) — 목업 단일 아이콘과 차이 가능, 시각 QA 이월.
- [Warning] iOS 빌드/단위 테스트는 Windows 로컬 실행 불가 — CI(GitHub Actions)에서 최종 확인 필요.

## QUESTION (이월)
- 없음.

## 검증 수행 내역
- 백엔드 compileJava+compileTestJava EXIT=0 (직접 실행 확인).
- 앵커 심볼(initialUnreadAnchorId/serverLastReadId/initialUnreadCount/didInitialScroll) 전 코드베이스 잔존 0건 grep 확인.
- 폐기 심볼(activeDetail/NotificationPinRow/clearDetail/flyToPin/showNicknameEdit) 잔존 0건 grep 확인.
- 신규 파일 ProfileEditView.swift — XcodeGen 폴더 소스(ios/project.yml sources: path WhereWeGo)라 자동 포함.
- 테스트: iOS NotificationInboxViewModelTests 7케이스 신설/교체(접근불가·nilGroupId·전부삭제·1개·N개·실패·sectionKey), GroupChatViewModelTests 앵커 테스트 삭제. 백엔드 pinCount 2케이스 + 알림 3필드 1케이스.
