phase: review
status: in_progress
vcs-type: git
branch: feat/ios-native-p2-chat
base: develop
dev-dir: .dev/feat-ios-native-p2-app-services
project-type: java-spring, node
project-root: ./
args: "context에서 phase p2 구현 시작"
flags: (none)
mode: normal
intent-source: user-selection
started: 2026-06-01
current-step: "review PR-1: mechanical gate + QA/ZT"
multi-pr: "PR-1(feat/ios-native-p2-chat, base develop) 진행 중 → PR-2(feat/ios-native-p2-push) → PR-3(feat/ios-native-p2-account-deletion). 각 PR implement→review→complete"
current-pr: "PR-1 채팅+STOMP"
design-decisions:
  - Q1 봇 범위: 1턴 한정(인스타→Gemini→PLACE_CARDS)
  - Q2 핀 푸시: PinV1Controller try-catch (NotificationService 무변경)
  - Q3 재가입: tombstone/partial-unique 방식, architect가 kakao_user_id UNIQUE+guard() 정합 해결(V017 후보)
  - Q4 STOMP 구독 인가: PR-1 포함(토픽 소유권 검증)
  - Q5 Apple revoke: best-effort 스킵(.p8/refresh 미구축, AC-12 문구 완화)
  - 봇 리팩터: 위임(공유코어 추출 아님). webhook 완전 무변경
  - 3-PR 스택: PR-1(chat+STOMP, base develop) → PR-2(APNs+devices) → PR-3(계정삭제)
design-critic:
  - MUST-ADDRESS 4건: tombstone kakao UNIQUE/guard 2건, Apple revoke 인프라 부재, leaveGroup 멱등성
  - CONSIDER: afterCommit STOMP 발행, PROCESSING 고아 인지, 단일인스턴스 ADR, ChatMessageAppender 재평가
delivery-plan: "3개 PR 순차(이번 실행 전부). 전체 P2 설계 → PR-1 채팅+STOMP → PR-2 APNs+devices → PR-3 계정삭제. 브랜치/스택 전략은 design에서 architect 결정"
requirements-decisions:
  - Q1 재로그인: 재가입 허용(soft-delete 재활성화 또는 신규, 데이터 복구 미보장)
  - Q2 배포: 3개 PR 분할
  - Q3 실시간: WebSocket(STOMP) 신규
  - Q4 APNs: pushy + .p8 환경변수/Secret
  - Q5 영속화: 전체 메시지(PROCESSING 포함) 영속화
phases:
  setup: completed
  requirements: completed
  design: completed
  implement: completed  # PR-1
  review: completed     # PR-1 (2회차 머지 가능)
  complete: in_progress # PR-1
review-log-pr1:
  - mechanical-gate: "build -x test BUILD SUCCESSFUL (test 명령 없음 스킵)"
  - review-1: "QA Critical2(방생성race/FR-7) Warn4 QUESTION4; ZT CRITICAL0 HIGH4 MEDIUM6"
  - decision: "Q1 SUBSCRIBE 화이트리스트 거부, Q2 senderType BOT 유지. Critical2+HIGH4+MEDIUM2 자동수정"
  - fix-1: "8파일 수정(race 폴백/FR-7 log.error/SEND인증/SUBSCRIBE 화이트리스트/@Async핸들러/permitAll축소/text@Size/cursor정규화)"
  - review-2: "확인 리뷰 8건 전량 해소, 회귀 없음, 머지 가능"
  - deferred: "ZT MEDIUM 이월: CSWSH(브라우저WS시), 구독 멤버십 스냅샷(탈퇴후), FK cascade, CONNECT 토큰만료, INSTAGRAM_URL 중복"
domain-context: chatbot (architecture 로드됨). notification/auth/group/user 연계
references: (none)
notes:
  - 베이스 develop (P1 PR #86 MERGED 확인, git pull로 P1 머지 로컬 반영)
  - P1 state.md 완료 마커는 stash 보존 ("wip: P1 state.md 완료 마커")
  - 로드맵: .dev/feat-ios-native-swiftui/roadmap.md P2 = 백엔드 앱 서비스
  - 제약: 라이브 웹 무중단 additive only. WebSocket/STOMP·APNs·chat_room/message·device 신규
  - P5(iOS 채팅+푸시 UI)가 P2에 의존
execution-log:
  - phase: setup
    result: "develop 베이스(P1 머지 pull), feat/ios-native-p2-app-services 생성, codemap 15+신규, DOMAIN_CONTEXT=chatbot"
