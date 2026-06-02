# 자기점검 결과 (phase-implement)

백엔드 최종 컴파일: **exit 0 (그린)**. iOS는 Windows 컴파일 불가 → 정적 코드리뷰.

## Critical (자동수정 — coder 수정 모드 실행됨)
1. CoupleChatViewModel `isMine()` 항상 true → 파트너 메시지 유실 위험(FR-12). → messageId dedup만으로 단순화.
2. BotChatView draft.count(raw) vs trimmed 불일치(AC-4 UI). → trimmed 단일 기준.
3. CoupleChatView canSend vs isOverLimit 불일치(AC-5 UI). → trimmed 단일 기준.
4. CoupleChatViewModel 1000자 절단 전송 → 차단(guard return)으로(AC-5 스펙).
5. ChatRealtimeService 단일 콜백 덮어쓰기 → 탭 전환 시 한 방만 보완(AC-9). → id 키 옵저버 딕셔너리.

## Warning/Info (phase-review 이월)
- [Warning] StompClient.swift:106-114 — connect 타임아웃 Task가 성공 후 취소 안 됨(10초 잔존, 누수 아님). Task 핸들 취소 권장.
- [Warning] CoupleChatViewModel.swift:73-85 — appear()의 옵저버 등록 순서(onStateChange/onReconnected 분리 등록) 비일관. Critical 5 수정으로 해소 예상.
- [Info] BotChatViewModel.swift:263 — botTopic이 userId nil 시 .bot(userId:0) 등록 → 최초 연결 후 resubscribe 시 최신 id 불일치 여지. appear 진입 시 CurrentUser.load 선행 권장.
- [Info] ChatScrollContainer.swift:105 — Color.clear.onAppear loadMore가 recompose마다 재호출 가능(isLoading 가드로 중복 차단되나 비효율). PreferenceKey 오프셋 방식 권장.
- [Info] AppNotificationDelegate.swift:35 — weak deepLinkRouter 수명이 주석 의존. 주석 보강/ unowned 검토.
- [Info] CoupleChatViewModel.swift:307 — ISO8601 isoFormatter가 +HH:MM 오프셋(서버 Z 기대 시 포맷 차). 표시용이라 런타임 무해.

## QUESTION (phase-review 이월 — 사용자 확인)
- Q1 [낙관 버블 JSON 생성]: CoupleChatViewModel.makeLocalTextFrame이 JSONSerialization+JSONDecoder로 ChatFrame 생성. 권장(a) 유지(ChatFrameDecodingTests가 동일 구조 검증). 대안(b) ChatFrame 패키지 내부 memberwise init 추가.
- Q2 [wire 타이밍]: WhereWeGoApp .task의 wire(appDelegate:)가 didFinishLaunching 이후 실행 → 최초 기동 직후 포그라운드 푸시 수신 창 미세 누락 가능. 권장(a) 유지(.task 충분히 빠름).
- Q3 [데모 시드 핀 instagramUrl]: DemoSeedRunner가 첫 핀에 DEMO_REEL_URL을 instagramUrl로 저장. 권장(a) 의도된 설계(핀 출처 URL ≠ 미디어 원본 저장, BR-6/AC-20과 무관). 대안(b) instagramUrl=null.

## AC 충족 요약
AC-1~21 중 AC-4/5/9가 Critical로 부분 미충족 → 수정 후 충족 예상. 나머지 18건 충족.
