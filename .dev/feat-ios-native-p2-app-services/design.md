# 설계: P2 — 백엔드 앱 서비스 (채팅·실시간·푸시·계정 삭제) [확정]

> 결정 반영: 봇 1턴 한정 / 핀푸시=PinV1Controller / 재가입=V017 partial unique / STOMP SUBSCRIBE 인가 PR-1 포함 / Apple revoke=best-effort 스킵. critic MUST-ADDRESS 4건 해소.

## 설계 규모
**대형** — 신규 도메인(chat/device/push) + STOMP 인프라 신규 + APNs 외부 통합 + 4개 도메인(notification/user/group/auth) 변경. 3-PR 스택 배포.

## 요구사항·수용 기준 (PRD 인용)
- [Must] FR-1~5, FR-8~13, FR-15~18, FR-21~24 / BR-1~6, BR-8 / AC-1~15
- [Should] FR-7, FR-14, FR-19, BR-9 / QE-1~3
- [Could] FR-20
- 마이그레이션: V015(chat_room/chat_message, PR-1), V016(devices, PR-2), V017(users UNIQUE → partial unique index, PR-3)

## 아키텍처 개요
레이어 `domain/application/infrastructure/interfaces/config` 유지. 4영역:
- ChatV1Controller → BotChatService/CoupleChatService/BotChatProcessor(@Async) → ChatRoom/ChatMessageRepository(port)→Adapter→Jpa. BotChatProcessor가 GeminiPlaceClient 재사용 + 앱용 PLACE_CARDS payload 빌더(신규)
- DeviceV1Controller → DeviceService → DeviceRepository; PushNotificationService → ApnsPushSender → pushy ApnsClient
- UserV1Controller(수정) DELETE /users/me → UserDeletionService → leaveGroup(활성 그룹만 멱등)+unlink(userId 1회)+Device삭제+chat_message nullifySender+UserModel soft delete(식별자 무변경)+AppleTokenRevoker(best-effort 스킵)
- config/websocket: WebSocketStompConfig + StompAuthChannelInterceptor(CONNECT 인증 + SUBSCRIBE 인가, JwtTokenProvider 재사용); ChatStompPublisher → SimpMessagingTemplate → SimpleBroker(/topic/**, 단일 인스턴스 인메모리)

**실시간 = 단방향 서버 push만**. 클라 STOMP SUBSCRIBE 전용, 전송은 REST POST. 메시지 저장 트랜잭션 **커밋 후** convertAndSend. enableSimpleBroker(인메모리) 단일 인스턴스.
**트랜잭션 경계**: 동기 저장 REQUIRED 내, STOMP/APNs는 커밋 후(afterCommit) best-effort(try-catch 격리). 봇 @Async 결과 append는 별도 트랜잭션.

### 운영 제약 / ADR (단일 인스턴스 전제)
SimpleBroker(인메모리 STOMP)·@Async 인메모리 큐는 t3.micro 단일 컨테이너(deployment.md) 전제. 오토스케일·롤링·다중 인스턴스 시: STOMP 토픽 인스턴스 분리(타 인스턴스 클라 push 미수신, Redis pub/sub relay 필요 — PRD 제외 범위) + @Async 큐 재시작 유실. PRD 리스크("WebSocket 수평 확장 단일 인스턴스 가정")와 정합. 본 설계는 단일 인스턴스 한정.

## 데이터 모델
### V015 chat_room/chat_message (PR-1)
```sql
CREATE TABLE chat_room (
    id BIGSERIAL PRIMARY KEY, type VARCHAR(20) NOT NULL,
    group_id BIGINT, owner_user_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ );
CREATE UNIQUE INDEX uq_chat_room_bot_owner    ON chat_room(owner_user_id) WHERE type='BOT'    AND deleted_at IS NULL;
CREATE UNIQUE INDEX uq_chat_room_couple_group ON chat_room(group_id)      WHERE type='COUPLE' AND deleted_at IS NULL;
CREATE TABLE chat_message (
    id BIGSERIAL PRIMARY KEY, room_id BIGINT NOT NULL REFERENCES chat_room(id),
    sender_type VARCHAR(20) NOT NULL, sender_user_id BIGINT,
    kind VARCHAR(20) NOT NULL, payload_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ );
CREATE INDEX idx_chat_message_room_id_desc ON chat_message(room_id, id DESC);
```
컨벤션: 기존 V001 = BIGSERIAL PK + TIMESTAMPTZ NOT NULL DEFAULT now() + deleted_at. BaseEntity GenerationType.IDENTITY(BIGSERIAL). updated_at NOT NULL 강제로 컬럼 포함 필수.

JSONB: Hibernate 6 내장 `@JdbcTypeCode(SqlTypes.JSON)` + columnDefinition="jsonb". 라이브러리 불필요. 도메인 String 보유(supports:jackson ObjectMapper 직렬화), 컨트롤러/STOMP 프레임에서만 JSON 노드 재파싱하여 payload(객체) 노출.

cursor(FR-5/9,AC-3): ?cursor={lastId}&limit (기본20,하드캡50). WHERE room_id AND (cursor IS NULL OR id<cursor) AND deleted_at IS NULL ORDER BY id DESC LIMIT limit+1 → hasMore 판정. 응답 {messages,hasMore,nextCursor}. 빈 방 {[],false,null}.

## 봇 방 리팩터 (확정: 위임 + 1턴 한정)
ChatbotWebhookService·핸들러13·인메모리 세션·decorate는 카카오 SkillRequest/Response·botUserKey 강결합 → 그대로 재사용 불가, 공유코어 추출은 webhook 무변경(FR-6/BR-7/AC-14) 리스크 폭증. → webhook/핸들러/세션 **완전 무변경**, BotChatProcessor가 stateless 부품만 재구성.
**앱 봇 = "인스타 링크→Gemini 장소 추출→PLACE_CARDS" 1턴 한정**:
- GeminiPlaceClient: 직접 재사용
- PlaceCardBuilder: 데이터(List<PlaceSearchHit>)만 재사용, 앱용 PLACE_CARDS payload 빌더 신규
- MessageClassifier: 재사용 안 함(앱은 인스타 URL 1차 판정만, INSTAGRAM_URL 패턴 상수 참조 가능)
- MessageHandler 체인·인메모리 세션: 재사용 안 함(다중선택/메모는 후속)
제외(후속): 다중 장소 선택 멀티턴, 메모 대기, 룰렛/공유.
세션: 카카오=botUserKey 인메모리(무변경), 앱=userId DB 영속(1턴이라 세션 불필요). 키 모델 분리 충돌 없음.

### PROCESSING + @Async (FR-4,BR-4)
TX-A(요청): ensureBotRoom→append(USER,TEXT)→append(BOT,PROCESSING) 커밋 → {messageId,kind:PROCESSING} 즉시 응답.
@Async("botChatExecutor") TX-B: 인스타 URL 판정→Gemini 추출→append(BOT,PLACE_CARDS) 신규행(PROCESSING 수정 안 함) → **afterCommit** publishBot + pushBotResult(best-effort). catch/30초 타임아웃(TX-C) → append(BOT,SYSTEM,오류) + afterCommit STOMP.
- @EnableAsync/ThreadPoolTaskExecutor 코드베이스 없음 → 신규 AsyncConfig + botChatExecutor bean, @Async("botChatExecutor") 명시.
- 트랜잭션 분리: PROCESSING(TX-A)↔결과(TX-B)/실패(TX-C) 독립 커밋. STOMP/APNs는 각 afterCommit(read-after-write 일관성).
- 30초: CompletableFuture.orTimeout 또는 Gemini 호출 타임아웃 → 초과 시 SYSTEM append.
**알려진 한계(인지)**: @Async 풀 포화·서버 재시작 시 PROCESSING 고아. 서버측 추가 방어는 범위 밖, 클라 stale 처리(P5)로 노트.

## 커플 방 CoupleChatService
@Transactional postCoupleMessage(userId,groupId,text): requireActiveMembership(GROUP_NOT_MEMBER→403, BR-3/AC-5) → ensureCoupleRoom(BR-2 부분 UNIQUE) → append(USER,TEXT) → **afterCommit** findOtherActiveMemberIds → 상대 있으면 publishCouple + pushCoupleMessage, 멤버1명이면 저장만(BR-5,AC-15). 봇 미개입(FR-10).

### ChatMessageAppender (정당 — 유지)
senderType/kind 조합별 ChatMessage 팩토리 + payload JSONB 직렬화(ObjectMapper)를 한 곳에 집약(3 호출자 중복 제거). ChatMessage 정적 팩토리(userText/botProcessing/botPlaceCards/botSystem/coupleText)는 도메인, payload→JSON 변환은 Appender 전담(엔티티 ObjectMapper 의존 회피, BaseEntity 순수성). 트랜잭션 경계는 호출자 소유, Appender는 stateless 헬퍼.

## 실시간 STOMP
신규 의존성 spring-boot-starter-websocket(BOM).
WebSocketStompConfig(@EnableWebSocketMessageBroker): addEndpoint("/ws/chat")(SockJS 불필요), enableSimpleBroker("/topic"), setApplicationDestinationPrefixes("/app"), clientInboundChannel interceptor 등록. Origin: 네이티브 앱 Origin 없음 → setAllowedOriginPatterns("*")/화이트리스트.
StompAuthChannelInterceptor(preSend, command 분기):
- **CONNECT(FR-11/14,AC-6)**: Authorization Bearer → jwtTokenProvider.parseAccessToken(P1). valid면 accessor.setUser(principal=Long userId, JwtAuthenticationFilter 동일), invalid면 MessageDeliveryException→STOMP ERROR 연결거부.
- **SUBSCRIBE 인가(Q4, PR-1 포함)**: /topic/chat/bot/{userId}→principal.userId==path만, /topic/chat/couple/{groupId}→findActiveByGroupIdAndUserId.isPresent()만 허용, 아니면 구독 거부. GroupMemberRepository(읽기) 의존. 타 사용자 토픽 구독 차단.
SecurityConfig: /ws/chat/** permitAll(인증은 STOMP CONNECT 레이어) 1줄 추가.
토픽/프레임(FR-12/13): /topic/chat/bot/{userId}, /topic/chat/couple/{groupId}. ChatMessageFrame{messageId,roomId,senderType,kind,payload,createdAt(ISO8601)}.
ChatStompPublisher(SimpMessagingTemplate 래퍼): BotChatProcessor/CoupleChatService afterCommit에서만, 실패 best-effort.

## 푸시 APNs
신규 의존성 com.eatthepath:pushy:0.15.x(버전 고정).
ApnsProperties(@ConfigurationProperties): keyId/teamId/bundleId/p8Key(env)/production. AppleAuthProperties/KakaoApiProperties 패턴. 코드 미포함.
ApnsClientFactory+ApnsPushSender: ApnsClientBuilder.setSigningKey(loadFromInputStream(p8,teamId,keyId)) singleton. send→SimpleApnsPushNotification. 거부 BadDeviceToken/Unregistered/410→deleteByToken(FR-19,AC-9). .p8 미주입 시 graceful no-op.
DeviceService.upsert(userId,platform,token)(FR-15,AC-7): (user_id,device_token) UNIQUE, 존재 시 updated_at 갱신. BR-9: 동일 token 다른 userId면 reassign. delete(userId,token)(FR-16).
PushNotificationService 트리거3(FR-17), fan-out(FR-20, findActiveByUserId):
- ①핀저장: PinV1Controller.createPin 기존 try-catch 직후 별도 try-catch pushPinSaved(groupId,registeredBy,pinId) — NotificationService 완전 무변경(Q2). 내부 findOtherActiveMemberIds로 파트너 추출.
- ②커플: CoupleChatService afterCommit
- ③봇결과: BotChatProcessor afterCommit
payload(FR-18): {aps{alert{title,body},sound:default,badge:1},type,roomId} — PushPayload 빌더.
### V016 devices (PR-2)
```sql
CREATE TABLE devices ( id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL, platform VARCHAR(20) NOT NULL,
  device_token VARCHAR(500) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ );
CREATE UNIQUE INDEX uq_devices_user_token ON devices(user_id, device_token);
CREATE INDEX idx_devices_user_id ON devices(user_id);
CREATE INDEX idx_devices_token ON devices(device_token);
```

## 계정 삭제
UserDeletionService(신규, UserService 분리). @Transactional deleteAccount(userId):
1. findActiveUserById(멱등/AUTH_USER_DEACTIVATED)
2. **활성 그룹만 멱등 순회**: groupMemberRepository로 활성 그룹 ID만 조회 후 각 leaveGroup(userId,groupId)(비활성/비멤버 진입 차단으로 GROUP_NOT_MEMBER throw 회피). 1인1활성그룹이라 0~1개. leaveGroup이 마지막1인 그룹 soft delete+초대만료+unlink 포함(FR-22/핀잔류/봇매핑해제 자동). **unlink는 deleteAccount에서 botUserMappingService.unlink(userId) 1회 직접 호출(MANDATORY, 멱등)** — 활성 그룹 0개 대비. 데드락: findByIdForUpdate 그룹 락 최대 1개라 위험 없음.
3. chat_message: nullifySenderByUserId(userId)(UPDATE sender_user_id=NULL). 봇 방(owner=userId) softDeleteByOwner.
4. deviceRepository.deleteByUserId(FR-21, PR-2 의존)
5. user.clearRefreshTokenHash()
6. user.delete()(soft delete deletedAt, AC-10). **식별자(oauthId/kakaoUserId) 무변경** — 재가입은 partial unique index 처리.
7. Apple revoke(FR-23,AC-12,Q5): provider==APPLE이면 AppleTokenRevoker.revoke(user) — .p8/refresh 인프라 부재로 실제 미수행, "revoke 스킵(인프라 부재)" 로그만 남기고 정상 반환. 트랜잭션 밖 호출. deletedAt 마킹은 완료(best-effort).

### 재가입 정책 — 확정: 후보 A (V017 partial unique index)
제약(코드 검증): uq_users_oauth(V014:23)·uq_users_kakao_user_id(V001:35, V014 DROP NOT NULL만) 둘 다 partial 아님. UserModel 생성자 KAKAO면 oauthId로 kakaoUserId 강제(80-82), guard() KAKAO+kakaoUserId null 금지(94-96)+oauthId non-blank(91-93). findByOauthProviderAndOauthId/findByKakaoUserId deleted_at 필터 없음(UserRepositoryImpl:22-29).
tombstone(후보 B) 비채택: oauth_id만 치환해도 kakao_user_id UNIQUE 충돌, kakao_user_id NULL+KAKAO는 guard 통과 불가, tombstone 문자열 Long.valueOf 파싱 실패.
**확정 V017**:
```sql
ALTER TABLE users DROP CONSTRAINT uq_users_oauth;
ALTER TABLE users DROP CONSTRAINT uq_users_kakao_user_id;
CREATE UNIQUE INDEX uq_users_oauth         ON users(oauth_provider, oauth_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_users_kakao_user_id ON users(kakao_user_id)            WHERE deleted_at IS NULL;
```
조회 보정(필수): UserRepository.findByOauthProviderAndOauthId/findByKakaoUserId를 ...AndDeletedAtIsNull(활성만)로 교체. 삭제 행 조회 미스→orElseGet 신규 INSERT→partial index가 활성 1개만 강제→충돌 없음→빈 계정(AC-13,QE-2). AUTH_USER_DEACTIVATED 분기(92-95)는 도달 불가화하되 방어적 유지. refresh isActive() 가드 유지.
행 단위 정합: Kakao 재가입 신규행(KAKAO,"123",123,deleted_at NULL) — partial이 deleted_at NULL만 검사→충돌 없음, guard 통과(Long.valueOf("123") 정상). Apple 동일. 식별자 무변경으로 guard·양 UNIQUE 동시 만족.
라이브 안전성: users 소규모(V014 주석). DROP CONSTRAINT+CREATE INDEX 짧은 락, 데이터 무변형 → 실질 additive. 대규모 시 CREATE INDEX CONCURRENTLY 노트.

### AppleTokenRevoker (축소, Q5)
.p8 client_secret·Apple refresh token 저장 미구축(범위 밖). 코드맵의 "AppleIdentityTokenVerifier(.p8) revoke 재사용" 전제 정정: AppleIdentityTokenVerifier는 JWKS 공개키 검증만(.p8 없음). revoke(user)는 토큰/인프라 부재 → 미수행, "Apple revoke 스킵(인프라 미구축)" 로그만. AC-12는 "시도(스킵 로그)하되 deletedAt 마킹 완료"로 충족.

## 적용 컨벤션
네이밍: XxxService/XxxV1Controller+XxxV1ApiSpec+XxxV1Dto(중첩 record)/port(domain)+Adapter(infra)+JpaRepository(Notification 사례). snake_case.
DI 생성자(@RequiredArgsConstructor). 엔티티 BaseEntity 상속+protected 기본생성자+static 팩토리+guard(). soft delete delete()/isActive().
에러 CoreException(ErrorType.*)→ApiControllerAdvice, ApiResponse<T>. UNIQUE 위반 DataIntegrityViolationException.
인증 @AuthUser Long userId. 신규 엔드포인트 anyRequest().authenticated()(BR-8). STOMP JwtTokenProvider 재사용, principal=Long userId.
트랜잭션 외부 HTTP는 밖(AuthService). 부수효과 afterCommit+try-catch 격리. REQUIRES_NEW는 별도 컴포넌트(NotificationVisitWriter).

## 변경 범위
### PR-1 (채팅+STOMP) 신규~23/수정~2
신규: V015; domain/chat {ChatRoom,ChatRoomType,ChatMessage,SenderType,MessageKind,ChatRoomRepository,ChatMessageRepository,ChatMessageAppender,BotChatService,BotChatProcessor,CoupleChatService,BotPlaceCardsPayloadBuilder,ChatMessageResult,ChatMessagePageResult,ChatStompPublisher,ChatMessageFrame}; infrastructure/chat {ChatRoomRepositoryAdapter,ChatMessageRepositoryAdapter,ChatRoomJpaRepository,ChatMessageJpaRepository}; interfaces/api/chat {ChatV1Controller,ChatV1ApiSpec,ChatV1Dto}; config/async/AsyncConfig; config/websocket {WebSocketStompConfig,StompAuthChannelInterceptor(CONNECT+SUBSCRIBE 인가)}
수정: config/security/SecurityConfig(/ws/chat/** permitAll); build.gradle.kts(websocket)
### PR-2 (APNs+devices) 신규~12/수정~3
신규: V016; domain/device {Device,DevicePlatform,DeviceService,DeviceRepository}; infrastructure/device {DeviceRepositoryAdapter,DeviceJpaRepository}; interfaces/api/device {DeviceV1Controller,DeviceV1ApiSpec,DeviceV1Dto}; config/env/ApnsProperties; infrastructure/push/apns {ApnsClientFactory,ApnsPushSender}; domain/push {PushNotificationService,PushPayload}
수정: build.gradle.kts(pushy); interfaces/api/pin/PinV1Controller(pushPinSaved 별도 try-catch, FR-17①); domain/chat/CoupleChatService+BotChatProcessor(푸시 트리거 afterCommit, FR-17②③)
### PR-3 (계정삭제) 신규~3/수정~4
신규: domain/user/UserDeletionService; infrastructure/auth/apple/AppleTokenRevoker(best-effort 스킵 logger); db/migration/V017__partial_unique_users_for_rejoin.sql(후보 A)
수정: interfaces/api/user/UserV1Controller(DELETE /me); domain/user/UserRepository+infrastructure/user/UserRepositoryImpl+UserJpaRepository(findBy...AndDeletedAtIsNull 활성 조회 보정); domain/chat/ChatMessageRepository(+Adapter, nullifySenderByUserId/softDeleteByOwner); UserLoginPersistence(조회 시그니처 반영, AUTH_USER_DEACTIVATED 방어적 유지)

## 의존성·영향도
신규 의존성: spring-boot-starter-websocket(PR-1), com.eatthepath:pushy:0.15.x(PR-2). JSONB Hibernate6 내장.
영향: NotificationService 완전 무변경(푸시는 PinV1Controller 배선, findOtherActiveMemberIds 읽기). GroupMemberService.leaveGroup 무변경(활성 그룹만 멱등 재사용). ChatbotWebhookService/체인/세션 완전 무변경(FR-6/BR-7/AC-14). AuthService refresh isActive() 유지. UserLoginPersistence/UserRepository 활성 조회 보정. SecurityConfig /ws/chat permitAll 1줄. PinV1Controller try-catch 1블록.
하위호환: V015/V016 신규 테이블만. V017 제약 형태 교체(데이터 무변형·소규모 락) → 실질 additive. 각 PR 단독 배포 회귀0. STOMP/APNs 미구성 graceful no-op.

## 구현 순서
PR-1: 1)V015+엔티티/enum 2)Repository port+Adapter+Jpa 3)ChatMessageAppender 4)AsyncConfig 5)WebSocketStompConfig+StompAuthChannelInterceptor(CONNECT+SUBSCRIBE)+SecurityConfig 6)ChatStompPublisher+Frame 7)BotPlaceCardsPayloadBuilder 8)BotChatService+BotChatProcessor(@Async 1턴, afterCommit STOMP) 9)CoupleChatService(afterCommit 브로드캐스트) 10)ChatV1Controller/ApiSpec/Dto 11)[Should]FR-7 타임아웃. (1·4·5·7 병렬, 6은5, 8은3/4/6/7)
PR-2(base PR-1): 12)V016+Device 13)Device Repo+Service(BR-9) 14)ApnsProperties+ClientFactory+PushSender(FR-19) 15)PushNotificationService+PushPayload 16)DeviceV1Controller 17)트리거 ①PinV1Controller②CoupleChatService③BotChatProcessor(PR-1의 8·9 의존)
PR-3(base PR-2): 18)V017 partial unique+UserRepository 활성 조회 보정+UserLoginPersistence 시그니처 19)ChatMessageRepository.nullifySenderByUserId/softDeleteByOwner(PR-1의 2 의존) 20)AppleTokenRevoker 21)UserDeletionService(활성 그룹 leaveGroup·unlink 1회·chat null·device삭제·revoke) 22)DELETE /users/me. (18·19·20 병렬)

## 3-PR 스택/브랜치 전략
권고: 스택 브랜치(PR-1→PR-2→PR-3). 근거: PR-2 트리거②③이 PR-1 생성 CoupleChatService/BotChatProcessor 동일 파일 수정 + PR-3 UserDeletionService가 PR-1 ChatMessageRepository+PR-2 DeviceRepository import → 독립 develop 기반 불가, 스택 유일 정합.
| PR | 브랜치 | 베이스 | 타겟 | 마이그레이션 |
| PR-1 | feat/ios-native-p2-chat | develop | develop | V015 |
| PR-2 | feat/ios-native-p2-push | feat/ios-native-p2-chat | feat/ios-native-p2-chat | V016 |
| PR-3 | feat/ios-native-p2-account-deletion | feat/ios-native-p2-push | feat/ios-native-p2-push | V017 |
머지 bottom-up: PR-1 develop 머지→PR-2 타겟 develop rebase→머지→PR-3 동일.
마이그레이션 버전 충돌: 스택 순서=버전 순서(V015→V016→V017) Flyway 충돌 없음(독립 브랜치면 chat/devices가 둘 다 V015 잡아 충돌 → 스택이 구조적 방지). V017=users 두 UNIQUE→partial unique 전환.

## 탐색 추가 항목
- db/migration/V001:35 uq_users_kakao_user_id(partial 아님), V014:23 uq_users_oauth(partial 아님) → V017 전환 대상
- domain/user/UserRepository:8-10 + infrastructure/user/UserRepositoryImpl:22-29 → deleted_at 필터 없음, ...AndDeletedAtIsNull 보정 대상
- domain/auth/UserLoginPersistence:91-103 → 조회 보정 후 orElseGet 신규생성이 재가입 빈 계정 보장, isActive()==false 분기 방어적 유지
- infrastructure/notification/NotificationRepositoryAdapter → port+adapter+Jpa 표준 템플릿
- domain/notification/NotificationVisitWriter → REQUIRES_NEW·best-effort 격리(봇 @Async append 참고)
- domain/bot/BotUserMappingService:59-62 → unlink(MANDATORY) userId 멱등(deleteByUserId)
- config/security/AuthUserArgumentResolver+AuthUser → principal=Long userId(STOMP 동일)
- infrastructure/auth/apple/AppleIdentityTokenVerifier → JWKS 검증 전용(.p8 없음), revoke 재사용 불가
- config/env/AppleAuthProperties, KakaoApiProperties → @ConfigurationProperties env 템플릿
- domain/chatbot/MessageClassifier:31-33 INSTAGRAM_URL 패턴; infrastructure/gemini/GeminiPlaceClient 재사용원; domain/chatbot/handler/PlaceCardBuilder 카드 데이터만 재사용
