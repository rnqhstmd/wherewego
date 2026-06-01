## Background

봇 채팅이 카카오 i 오픈빌더 Webhook(`/chatbot/webhook`) 전용으로만 존재해, iOS 앱이 직접 메시지를 주고받을 수 있는 채팅 API·스토리지·실시간 채널이 없었다. iOS 네이티브 전환 로드맵에서 앱 채팅(P5)의 백엔드 기반이 필요하다. P2(백엔드 앱 서비스)는 채팅·실시간·푸시·계정 삭제를 포함하며, 라이브 웹 무중단(additive only) 제약 아래 3개 PR로 분할 배포한다. **본 PR은 PR-1(채팅 데이터 모델 + 봇 방 + 커플 방 + STOMP)** 이며, base는 `develop`이다. 후속 PR-2(APNs/devices), PR-3(계정 삭제/재가입)이 이 위에 스택된다.

## Summary

`chat_room`/`chat_message` 모델과 앱 채팅 REST + STOMP 실시간 채널을 신규로 도입한다. 봇 방은 기존 카카오봇 핸들러 체인을 변경하지 않고, stateless 부품(인스타 파싱·장소 검색)만 재사용하는 위임 방식으로 "인스타 링크 → 장소 추출 → PLACE_CARDS" 1턴을 처리한다. 사용자 요청에 즉시 PROCESSING 메시지를 응답하고 `@Async`로 결과를 append + STOMP 발행한다. 커플 방은 분류기·봇 미개입으로 텍스트 저장 + 상대 브로드캐스트만 수행한다. 실시간은 외부 브로커 없는 SimpleBroker(단일 인스턴스) 기반이며, CONNECT 시 Bearer 인증 + SUBSCRIBE 시 토픽 소유권 화이트리스트 인가를 적용한다. 기존 카카오봇/웹 경로는 무변경이다.

## Changes

- **채팅 모델/마이그레이션**: `chat_room`(BOT/COUPLE, 부분 UNIQUE로 활성 방 1개 강제), `chat_message`(JSONB payload) + V015. `ChatMessageAppender`가 senderType/kind 조합별 팩토리와 JSONB 직렬화를 캡슐화한다.
- **봇 방**: `BotChatService`(PROCESSING 즉시 응답 + afterCommit `@Async` 트리거), `BotChatProcessor`(인스타 1턴 처리, 외부 호출 데드라인/HTTP read timeout으로 시간 상한, 실패 시 SYSTEM 안내). 기존 `ChatbotWebhookService`·핸들러·세션은 무변경.
- **커플 방**: `CoupleChatService`(멤버십 검증으로 비멤버 403, afterCommit 브로드캐스트, 멤버 1명이면 저장만).
- **실시간(STOMP)**: `/ws/chat` 엔드포인트, `StompAuthChannelInterceptor`(CONNECT Bearer 인증 + SUBSCRIBE 토픽 소유권 화이트리스트, 미인식 토픽/미인증 SEND 거부), `ChatStompPublisher`.
- **REST/인프라**: `ChatV1Controller` 4종(cursor 페이지네이션, limit 1~50 클램프), `AsyncConfig`(@EnableAsync + 전용 executor + 미처리 예외 핸들러).
- **리뷰 반영**: 방 생성 race의 `DataIntegrityViolationException` 재조회 폴백, text 최대 길이 제약, `/ws/chat` permitAll 범위 최소화.

## Audit Summary

통합 감사(QA + ZeroTrust) 결과 — 판정: 조치 후 머지 가능.

- **CRITICAL: 0** / HIGH: 4(전량 해소) / MEDIUM: 6(일부 수정·일부 이월)
- 해소(HIGH): 방 생성 race UNIQUE 위반 폴백, STOMP SEND principal 검증, SUBSCRIBE 화이트리스트 인가, `@Async` 예외 핸들러, `/ws/chat` permitAll 축소.
- 이월(MEDIUM, 차기/문서화): 브라우저 WebSocket 도입 시 Origin 화이트리스트(CSWSH), 그룹 탈퇴 후 STOMP 구독 잔존(발행 시 활성 멤버 재확인 또는 세션 종료 필요), `chat_message` FK cascade, CONNECT 후 토큰 만료 미재검증, `INSTAGRAM_URL` 리터럴 중복.
- 수용 기준: PR-1 범위 [Must] AC-1~6, AC-14, AC-15 전량 충족(인수 검증 ACCEPT). AC-7~13은 PR-2/PR-3 이월.

상세: `.dev/feat-ios-native-p2-app-services/trust-ledger.md`

## Checklist

- [ ] 봇 방/커플 방 메시지 송수신과 STOMP 실시간 수신이 로컬에서 정상 동작하는지 확인
- [ ] 기존 카카오봇(`/chatbot/webhook`)·웹 경로 회귀 0인지 확인
- [ ] V015 마이그레이션이 정상 적용되는지 확인(부분 UNIQUE 인덱스, JSONB)
- [ ] STOMP 인증/인가(무토큰 거부, 타 사용자 토픽 구독 차단) 동작 확인
- [ ] (후속) 채팅 도메인 단위/통합 테스트 추가 — 본 PR에는 미포함
- [ ] (스택) PR-2(APNs/devices) → PR-3(계정 삭제)가 본 브랜치 위에 순차 진행
