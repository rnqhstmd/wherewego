# 설계 초안: P2 — 백엔드 앱 서비스 (채팅·실시간·푸시·계정 삭제)

## 설계 규모
**대형** — 신규 도메인(chat) 3개 엔티티 + STOMP 인프라 신규 도입 + APNs 외부 통합 + 4개 도메인(notification/user/group/auth) 변경. 3-PR 스택 배포.

## 아키텍처 개요
4개 영역(채팅/실시간/푸시/계정삭제). 레이어 컨벤션 `domain/application/infrastructure/interfaces/config` 유지.
- ChatV1Controller → BotChatService/CoupleChatService → ChatMessageAppender → ChatRoom/ChatMessageRepository(port)→Adapter→JpaRepository
- BotChatProcessor(@Async): MessageClassifier/MessageHandler chain/PlaceCardBuilder/Gemini 재사용
- DeviceV1Controller → DeviceService → DeviceRepository; PushNotificationService → ApnsPushSender → pushy ApnsClient
- UserV1Controller(수정) DELETE /users/me → UserDeletionService → GroupMemberService.leaveGroup(재사용)+BotUserMapping.unlink+Device삭제+chat_message null+AppleTokenRevoker
- config/websocket: WebSocketStompConfig + StompAuthChannelInterceptor(JwtTokenProvider 재사용); ChatStompPublisher → SimpMessagingTemplate → SimpleBroker(/topic/**)

**실시간 = 단방향 서버 push만**. 클라는 STOMP SUBSCRIBE 전용, 메시지 전송은 REST POST. 외부 브로커 없이 enableSimpleBroker(인메모리) 단일 인스턴스.
**트랜잭션 경계**: 동기 저장 REQUIRED 내, STOMP/APNs는 커밋 후 best-effort(try-catch 격리). 봇 @Async 결과 append는 별도 트랜잭션.

## 데이터 모델
### V015 chat_room/chat_message (PR-1)
- chat_room(id, type[BOT|COUPLE], group_id NULL, owner_user_id NULL, created/updated/deleted_at). 부분 UNIQUE: BOT owner_user_id WHERE type=BOT AND deleted_at IS NULL; COUPLE group_id WHERE type=COUPLE AND deleted_at IS NULL
- chat_message(id, room_id FK, sender_type[USER|BOT|SYSTEM], sender_user_id NULL, kind[TEXT|PLACE_CARDS|MEMO_PROMPT|PROCESSING|SYSTEM], payload_json JSONB NOT NULL, created/updated/deleted_at). INDEX(room_id, id DESC)
- BaseEntity 상속(created/updated/deleted_at 자동, id IDENTITY). chat_message updated_at NOT NULL 마이그레이션 포함 필수.
- JSONB: Hibernate 6 내장 @JdbcTypeCode(SqlTypes.JSON) + columnDefinition="jsonb". 별도 라이브러리 불필요. 도메인은 String 보유 권고(supports:jackson ObjectMapper 직렬화).
- cursor: ?cursor={lastId}&limit (기본20, 하드캡50). WHERE room_id AND (cursor IS NULL OR id<cursor) AND deleted_at IS NULL ORDER BY id DESC LIMIT limit+1 → hasMore 판정. 응답 {messages, hasMore, nextCursor}.

## 봇 방 리팩터 전략 (핵심)
**결정: 위임(delegation), 공유 코어 추출 아님.**
근거: ChatbotWebhookService.handle()은 카카오 SkillRequest/SkillResponse·botUserKey 세션에 강결합. 핸들러 13개+세션+SkillResponse 변환 전부 추상화하면 webhook 무변경(FR-6/BR-7)·웹 회귀0 리스크 폭증.
→ BotChatService는 별도 진입 서비스. webhook의 순수 stateless 부품만 재사용:
- MessageClassifier: 앱은 {text,actionPayload}→INSTAGRAM_LINK/PLACE_SELECTION/UNKNOWN 얇은 매핑
- MessageHandler 구현체: 직접 위임 불가(SkillRequest/Response 결합) → 하위 서비스 추출 또는 Gemini/PlaceSearch 직접 호출
- PlaceCardBuilder: 데이터(List<PlaceSearchHit>) 공유, 앱용 PLACE_CARDS payload 빌더 신규
- GeminiPlaceClient: 직접 재사용
- 인메모리 세션: 재사용 안 함(앱은 DB 영속+stateless, actionPayload로 멀티턴)
webhook 진입점·체인·세션·decorate는 완전 무변경(FR-6,BR-7,AC-14).

세션 공존: 카카오=botUserKey 인메모리(무변경), 앱=userId DB 영속(세션 불필요). 키 모델 달라 자연 분리.

### PROCESSING + @Async (FR-4,BR-4)
요청 스레드 TX-A: ensureBotRoom→append(USER,TEXT)→append(BOT,PROCESSING) 커밋 → {messageId,kind:PROCESSING} 즉시 응답.
@Async botChatExecutor 별도 TX-B: 핸들러 코어(Gemini) → append(BOT,PLACE_CARDS) 신규행(PROCESSING 수정안함) → ChatStompPublisher.publishBot → PushNotificationService.pushBotResult(best-effort). catch/30초 타임아웃 → append(BOT,SYSTEM,오류) + STOMP.
- @EnableAsync/ThreadPoolTaskExecutor 코드베이스 없음 → 신규 AsyncConfig + botChatExecutor bean, @Async("botChatExecutor") 명시.
- 30초: CompletableFuture.orTimeout(30,SECONDS) 또는 Gemini 호출 타임아웃.

## 커플 방 CoupleChatService
@Transactional postCoupleMessage(userId, groupId, text):
- groupMemberService.requireActiveMembership(userId,groupId) 재사용(GROUP_NOT_MEMBER→403, BR-3/AC-5)
- ensureCoupleRoom(groupId)(BR-2 부분 UNIQUE)
- append(USER,TEXT,{text},senderUserId)
- findOtherActiveMemberIds(groupId,userId)(NotificationService 동일 메서드) → 상대 있으면 publishCouple + pushCoupleMessage. 멤버1명이면 저장만(BR-5,AC-15)
- 봇 미개입(FR-10)

## 실시간 STOMP
신규 의존성 spring-boot-starter-websocket(BOM).
WebSocketStompConfig(@EnableWebSocketMessageBroker): addEndpoint("/ws/chat")(SockJS 불필요), enableSimpleBroker("/topic"), setApplicationDestinationPrefixes("/app"), clientInboundChannel interceptor 등록.
StompAuthChannelInterceptor(FR-11/14,AC-6): preSend에서 StompCommand.CONNECT 시 Authorization Bearer → jwtTokenProvider.parseAccessToken(P1 재사용) → valid면 accessor.setUser(principal=Long userId, JwtAuthenticationFilter 동일 모델), invalid면 MessageDeliveryException→STOMP ERROR 연결거부.
SecurityConfig: /ws/chat/** permitAll(인증은 STOMP CONNECT 레이어).
토픽: /topic/chat/bot/{userId}, /topic/chat/couple/{groupId}. 프레임 ChatMessageFrame{messageId,roomId,senderType,kind,payload,createdAt(ISO8601)}.
ChatStompPublisher(SimpMessagingTemplate 래퍼): BotChatProcessor/CoupleChatService에서만 호출, 실패 best-effort.

## 푸시 APNs
신규 의존성 com.eatthepath:pushy:0.15.x(버전 고정).
ApnsProperties(@ConfigurationProperties): keyId/teamId/bundleId/p8Key(env 주입)/production. AppleAuthProperties/KakaoApiProperties 패턴.
ApnsClientFactory+ApnsPushSender: ApnsClientBuilder.setSigningKey(loadFromInputStream(p8,teamId,keyId)) singleton. send→SimpleApnsPushNotification. 거부 BadDeviceToken/Unregistered/410→DeviceRepository.deleteByToken(FR-19,AC-9). .p8 미주입 시 graceful no-op.
DeviceService.upsert(userId,platform,token)(FR-15,AC-7): (user_id,device_token) UNIQUE, 존재 시 updated_at 갱신. BR-9: 동일 token 다른 userId면 reassign. delete(userId,token)(FR-16).
PushNotificationService 트리거3: ①핀저장=PinV1Controller.createPin try-catch 직후 별도 try-catch pushPinSaved(NotificationService 무변경) ②커플=CoupleChatService ③봇결과=BotChatProcessor. payload(FR-18) {aps{alert,sound,badge},type,roomId}. fan-out(FR-20) findActiveByUserId.
### V016 devices (PR-2)
devices(id, user_id, platform[IOS], device_token VARCHAR500, created/updated/deleted_at). UNIQUE(user_id,device_token), INDEX(user_id), INDEX(device_token).

## 계정 삭제
UserDeletionService(신규, UserService 분리). @Transactional deleteAccount(userId):
1. findActiveUserById(멱등/AUTH_USER_DEACTIVATED)
2. 그룹 탈퇴 재사용: 각 그룹 groupMemberService.leaveGroup(userId,groupId)(BR-6). leaveGroup이 leftAt+마지막1인 그룹 soft delete+초대만료+botUserMapping.unlink 포함 → FR-22/핀잔류/봇매핑해제 자동. unlink Propagation.MANDATORY 정합.
3. chat_message: sender_user_id NULL 처리(UPDATE WHERE sender_user_id=userId). 봇 방(owner=userId) soft delete.
4. deviceRepository.deleteByUserId(FR-21, PR-2 의존)
5. user.clearRefreshTokenHash()
6. oauthId/oauthProvider 초기화(재가입 정책에 따라)
7. user.delete()(soft delete deletedAt, AC-10)
8. Apple revoke(FR-23,AC-12): provider==APPLE이면 AppleTokenRevoker.revoke best-effort(try-catch, 실패 로그, 삭제 완료). 트랜잭션 밖 호출 권고.

### 재가입 정책(FR-24) — 권고: tombstone 치환 + 신규 행
제약: uq_users_oauth UNIQUE(oauth_provider,oauth_id)(V014), kakao_user_id UNIQUE(V001). UserLoginPersistence.upsertByOauthAndIssueTokens: 기존 행 isActive()==false면 AUTH_USER_DEACTIVATED throw(현재 차단).
권고(a): 삭제 시 oauthId를 tombstone(deleted:{oauthId}:{userId}) 치환, kakao_user_id NULL(V014에서 nullable). 재로그인 시 findByOauthProviderAndOauthId 빈 결과→신규 행→빈 계정(QE-2,AC-13). AuthService/UserLoginPersistence 무변경. V017 불필요(스키마 변경 없음). UserModel.guard() oauthId non-blank 요구 → NULL 대신 tombstone 문자열.
대안(b 재활성화): restore() 재사용 → 이전 멤버십/핀 잔존 "복구 미보장" 위반 + AuthService 분기 수정. 비권고.
AppleTokenRevoker: Apple /auth/revoke POST(client_id,client_secret JWT,token). client_secret은 Apple Sign In .p8 서명. revoke 대상 = Apple refresh token인데 P1 미저장 → 없으면 best-effort 로깅(질문5).

## 적용 컨벤션
네이밍: XxxService/XxxV1Controller+XxxV1ApiSpec+XxxV1Dto(중첩 record)/port 인터페이스+XxxRepositoryAdapter(infra)+XxxJpaRepository. snake_case 컬럼.
DI 생성자(@RequiredArgsConstructor). port(domain)+adapter(infra) 분리(Notification 사례).
엔티티 BaseEntity 상속, protected 기본생성자+static 팩토리+guard() 불변식. soft delete delete()/isActive().
에러 CoreException(ErrorType.*)→ApiControllerAdvice, ApiResponse<T>. UNIQUE 위반 DataIntegrityViolationException.
인증 @AuthUser Long userId(AuthUserArgumentResolver). 신규 엔드포인트 anyRequest().authenticated()(BR-8). STOMP JwtTokenProvider 재사용.
트랜잭션 외부 HTTP는 밖(AuthService). best-effort 부수효과 try-catch 격리. REQUIRES_NEW는 별도 컴포넌트(NotificationVisitWriter).

## 변경 범위
### PR-1 (채팅+STOMP) 신규~22/수정~2
신규: V015 마이그레이션; domain/chat {ChatRoom,ChatRoomType,ChatMessage,SenderType,MessageKind,ChatRoomRepository,ChatMessageRepository,ChatMessageAppender,BotChatService,BotChatProcessor,CoupleChatService,ChatMessageResult,ChatMessagePageResult,ChatStompPublisher,ChatMessageFrame}; infrastructure/chat {ChatRoomRepositoryAdapter,ChatMessageRepositoryAdapter,ChatRoomJpaRepository,ChatMessageJpaRepository}; interfaces/api/chat {ChatV1Controller,ChatV1ApiSpec,ChatV1Dto}; config/async/AsyncConfig; config/websocket {WebSocketStompConfig,StompAuthChannelInterceptor}
수정: config/security/SecurityConfig(/ws/chat/** permitAll); build.gradle.kts(websocket)
### PR-2 (APNs+devices) 신규~10/수정~3
신규: V016; domain/device {Device,DevicePlatform,DeviceService,DeviceRepository}; infrastructure/device {DeviceRepositoryAdapter,DeviceJpaRepository}; interfaces/api/device {DeviceV1Controller,DeviceV1ApiSpec,DeviceV1Dto}; config/env/ApnsProperties; infrastructure/push/apns {ApnsClientFactory,ApnsPushSender}; domain/push {PushNotificationService,PushPayload}
수정: build.gradle.kts(pushy); interfaces/api/pin/PinV1Controller(pushPinSaved FR-17①); domain/chat/CoupleChatService+BotChatProcessor(푸시 트리거 FR-17②③)
### PR-3 (계정삭제) 신규~3/수정~2
신규: domain/user/UserDeletionService; infrastructure/auth/apple/AppleTokenRevoker; (조건부)V017 생략 권고
수정: interfaces/api/user/UserV1Controller(DELETE /me); (재가입)domain/auth/UserLoginPersistence(tombstone이면 무변경 가능)

## 의존성/영향도
신규 의존성: spring-boot-starter-websocket(PR-1), com.eatthepath:pushy:0.15.x(PR-2). JSONB Hibernate6 내장.
영향: NotificationService 무변경(푸시는 PinV1Controller/PushNotificationService 배선, findOtherActiveMemberIds 읽기). GroupMemberService.leaveGroup 무변경 재사용. ChatbotWebhookService/체인/세션 완전 무변경(FR-6,BR-7,AC-14). AuthService/UserLoginPersistence tombstone이면 무변경. SecurityConfig /ws/chat permitAll 1줄. PinV1Controller try-catch 1블록.
하위호환: V015~V017 신규 테이블만. 각 PR 단독 배포 회귀0. STOMP/APNs 미구성 graceful no-op.

## 구현 순서
PR-1: 1)V015+엔티티/enum 2)Repository port+Adapter+Jpa 3)ChatMessageAppender 4)AsyncConfig 5)WebSocketStompConfig+StompAuthChannelInterceptor+SecurityConfig 6)ChatStompPublisher+Frame 7)BotChatService+BotChatProcessor 8)CoupleChatService 9)ChatV1Controller/ApiSpec/Dto 10)[Should]FR-7 타임아웃. (1·4·5 병렬, 6은5, 7은3/4/6)
PR-2(base PR-1): 11)V016+Device 12)Device Repo+Service(BR-9) 13)ApnsProperties+ClientFactory+PushSender(FR-19) 14)PushNotificationService+PushPayload 15)DeviceV1Controller 16)트리거 배선 ①PinV1Controller②CoupleChatService③BotChatProcessor(PR-1 머지 후)
PR-3(base PR-2): 17)AppleTokenRevoker 18)UserDeletionService(leaveGroup/unlink/chat null/device삭제/revoke) 19)DELETE /users/me 20)재가입 tombstone(또는 재활성화 시 UserLoginPersistence 수정)

## 3-PR 스택/브랜치 전략
권고: 스택 브랜치(PR-1→PR-2→PR-3).
근거: PR-2 트리거②③이 PR-1 생성 CoupleChatService/BotChatProcessor 동일 파일 수정. PR-3 UserDeletionService가 PR-1 ChatMessageRepository+PR-2 DeviceRepository import. 독립 develop 기반 불가(컴파일/파일 부재) → 스택 유일 정합.
| PR | 브랜치 | 베이스 | 타겟 | 마이그레이션 |
| PR-1 | feat/ios-native-p2-chat | develop | develop | V015 |
| PR-2 | feat/ios-native-p2-push | feat/ios-native-p2-chat | feat/ios-native-p2-chat | V016 |
| PR-3 | feat/ios-native-p2-account-deletion | feat/ios-native-p2-push | feat/ios-native-p2-push | V017(생략 권고) |
머지 bottom-up: PR-1 develop 머지→PR-2 타겟 develop rebase→머지→PR-3 동일.
마이그레이션 버전 충돌: 스택 순서=버전 순서(V015→V016→V017)라 Flyway 충돌 없음. 독립 브랜치면 둘 다 V015 잡아 충돌했을 것 → 스택이 구조적 방지. V017은 tombstone이면 생략.

## 탐색 추가 항목
- interfaces/api/notification/NotificationV1Controller → 기존 알림 REST(폴링), 변경 없음
- infrastructure/notification/NotificationRepositoryAdapter → port+adapter+Jpa 표준 템플릿
- domain/notification/NotificationVisitWriter → REQUIRES_NEW self-invocation 회피·best-effort 격리 패턴
- domain/bot/BotUserMappingService → unlink(MANDATORY), leaveGroup 경유 자동 호출
- config/security/AuthUserArgumentResolver+AuthUser → principal=Long userId(STOMP 인터셉터 동일)
- infrastructure/auth/apple/AppleIdentityTokenVerifier → Apple .p8/JWKS/nimbus(revoke client_secret 재사용)
- config/env/AppleAuthProperties, KakaoApiProperties → @ConfigurationProperties env 템플릿(ApnsProperties)
- domain/chatbot/handler/InstagramLinkHandler, PlaceSelectionHandler → 봇 코어 재사용 대상(질문1 결과)
- db/migration/V001~V014 → TIMESTAMPTZ NOT NULL, 부분 UNIQUE(WHERE deleted_at IS NULL) 스타일

## 확인이 필요한 사항 (5건 — 미해소)
1. 봇앱 PR-1 처리 범위: (a)인스타→Gemini→PLACE_CARDS 1턴 한정[권장] (b)카카오봇 전기능 재현
2. 핀저장 푸시 배선: (a)PinV1Controller try-catch[권장] (b)NotificationService 내부
3. 재가입 구현: (a)tombstone+신규행[권장,V017불필요] (b)restore 재활성화
4. STOMP SUBSCRIBE 인가: (a)PR-1 포함 토픽 소유권 검증[권장] (b)CONNECT만, 인가 후속
5. Apple revoke token: (a)best-effort 스킵 로그[권장] (b)refresh token 저장 V017
