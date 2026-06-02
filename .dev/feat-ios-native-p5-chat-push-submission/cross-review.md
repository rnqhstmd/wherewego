# Cross-Review 결과

- advisor: claude (qa-manager + security-auditor) + PR #92 Gemini 인라인 리뷰
- 브랜치: feat/ios-native-p5-chat-push-submission (base: develop)
- 실행: 2026-06-02

## AC 충족 매트릭스 (요약)
AC-1~21 중 **19건 충족**, AC-11(딥링크 삭제 폴백) 미충족, AC-16(아이콘 PNG) 부분(Mac 이월). 수정 7건(자기점검5+리뷰7) 전부 실코드 반영 확인.

## 설계 범위 이탈
이탈 없음. (.dev/ 타 feature 문서는 운영 코드 무관)

## 신규 위험 / PR 리뷰 (처리 대상)

### cross-review
1. [Warning] AC-11 딥링크 삭제 폴백 미구현 — DeepLinkRouter/MainTabView가 방 존재 검증·`.map` 폴백 없음(설계 §9 "대상 조회 실패 시 .map"). 봇방은 유저별 상존, **커플방(그룹 삭제/404)** 한정 위험.
2. [MEDIUM/GAP] BotChatViewModel.reconcileLatest cursor/hasMore 갱신이 `if appended` 조건부 → 끊긴 동안 신규 없으면 커서 미갱신(CoupleChatViewModel은 무조건 갱신, 비대칭).
3. [MEDIUM/ASSUMPTION] 봇 토픽 userId=0 placeholder 구독 후 재구독 타이밍 의존 → appear()에서 currentUser.load() 선행 필요.

### PR #92 Gemini (medium)
4. StompClient.swift:112 scheduleConnectTimeout — connectContinuation==nil 시 가드(불필요 타임아웃 태스크 방지).
5. PlaceCardsBubble.swift:97 — 저장 후 selectedIDs 초기화(체크박스 잔존·중복저장 방지).
6. StompFrame.swift:60 — `\r\n`→`\n` 정규화 후 파싱(STOMP 1.2 호환, 헤더/본문 구분 안전).

## 총평
- 강점: 수정 7건 전부 반영 확인, STOMP 재연결·옵저버 딕셔너리·데모 비하드코딩 정합.
- 합산: Critical 0, Warning/MEDIUM 6 (전부 처리 대상).
- 권고: 6건 일괄 수정 후 push(PR #92 반영). AC-16 PNG는 Mac 이월 유지.

## 처리 결과 (6건 전부 수정)
- 1 StompClient.swift: scheduleConnectTimeout connectContinuation==nil 가드 — 수정
- 2 PlaceCardsBubble.swift: 저장 후 selectedIDs=[] 초기화 — 수정
- 3 StompFrame.swift: decode 진입부 \r\n→\n 정규화 + CRLF 테스트 1건 — 수정
- 4 BotChatViewModel.reconcileLatest: cursor/hasMore 무조건 갱신(Couple과 대칭) — 수정
- 5 Bot/CoupleChatViewModel.appear: subscribe 전 currentUser.load 선행(userId 보장) — 수정
- 6 AC-11: CoupleChatViewModel.onUnavailable 콜백 + MainTabView가 deepLinkRouter.pending=.map 주입(커플방 그룹 부재 시 지도 폴백) — 수정
- 부수: BotChatViewModelTests StubMeURLProtocol 추가(테스트 /users/me 네트워크 차단)
- 커밋: cross-review/PR Gemini 반영 (9 files). AC-16 PNG는 Mac 이월 유지.
