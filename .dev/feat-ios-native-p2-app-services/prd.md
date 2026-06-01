# PRD: P2 — 백엔드 앱 서비스 (채팅·실시간·푸시·계정 삭제)

> 확정 결정(2026-06-01): 재가입 허용 / 3개 PR 분할 / STOMP 신규 / pushy+.p8(env) / 전체 메시지 영속화

## 배경

**현재 제품 상태:**
- 봇 채팅은 카카오 i 오픈빌더 Webhook(`/chatbot/webhook`) 전용으로만 존재. iOS 앱이 직접 메시지를 주고받을 수 있는 채팅 API·스토리지·실시간 채널이 없음
- 알림은 웹 클라이언트의 사용자 행위(탭 포커스·마운트) 기반 REST 폴링 방식. iOS APNs 푸시 없음. 기기 토큰 저장 테이블 없음
- 계정 삭제 기능 없음 (App Store Guideline 5.1.1(v) 미충족). `UserModel`에는 soft-delete 컬럼(`deletedAt`)이 있으나 삭제 플로우가 구현되지 않음. Apple OAuth 연동 사용자에 대한 Apple token revoke 미구현
- P1 완료: Bearer 헤더 인증, Kakao/Apple 네이티브 로그인, refresh 토큰, oauth 공급자 일반화. Flyway 최신 버전 V014

**변경이 필요한 이유:**
iOS 네이티브 앱 전환(P5 채팅+푸시 UI)의 백엔드 기반을 구성한다. 앱 심사 통과를 위한 계정 삭제도 포함한다. 기존 웹/카카오봇 경로는 무변경·무중단이어야 한다.

## 목표

- iOS 앱이 봇 채팅(userId 기반)과 커플 1:1 채팅을 사용할 수 있는 REST + 실시간 채널 제공
- APNs 푸시로 파트너 핀 저장·커플방 새 메시지·봇 처리 완료 3가지 이벤트를 iOS 기기에 전달
- App Store Guideline 5.1.1(v) 준수를 위한 계정 삭제 엔드포인트 제공 (재가입 허용 정책)
- 기존 카카오봇 Webhook·웹 REST 경로: 웹 회귀 0건

## 요구사항

### 기능 요구사항

**[채팅 데이터 모델]**
- [Must] FR-1: `chat_room(id, group_id BIGINT NULL, type ENUM[BOT|COUPLE], owner_user_id BIGINT NULL)` 테이블 신설. BOT 방은 `owner_user_id = 해당 userId`, `group_id NULL`. COUPLE 방은 `group_id = 커플 그룹 ID`, `owner_user_id NULL`
- [Must] FR-2: `chat_message(id, room_id FK, sender_type ENUM[USER|BOT|SYSTEM], sender_user_id BIGINT NULL, kind ENUM[TEXT|PLACE_CARDS|MEMO_PROMPT|PROCESSING|SYSTEM], payload_json JSONB, created_at TIMESTAMPTZ)` 테이블 신설
- [Must] FR-3: `devices(id, user_id FK, platform ENUM[IOS], device_token VARCHAR(500), created_at, updated_at)` 테이블 신설. `(user_id, device_token)` UNIQUE 제약

**[봇 방 — BotChatService]**
- [Must] FR-4: `POST /api/v1/chat/bot/messages` — 입력 `{text, actionPayload?}`. 인증된 `userId`로 봇 방을 조회(없으면 자동 생성). "처리 중" 메시지(kind=PROCESSING)를 즉시 DB에 저장 후 응답 반환(`{messageId, kind: "PROCESSING"}`). 이후 `@Async`로 기존 핸들러 체인(MessageClassifier → MessageHandler → Gemini → PlaceCardBuilder) 실행 → 결과 메시지(kind=PLACE_CARDS 등)를 DB append + STOMP 토픽 발행 + APNs 푸시
- [Must] FR-5: `GET /api/v1/chat/bot/messages?cursor={id}&limit={n}` — 봇 방 메시지 목록. cursor 기반 페이지네이션(cursor 미전달 시 최신 20건, 최대 50건). 메시지 없으면 빈 배열 + `hasMore: false`
- [Must] FR-6: 기존 `ChatbotWebhookService`(`/chatbot/webhook`) 경로는 무변경. 핸들러 체인·Gemini·PlaceCardBuilder는 `BotChatService`와 공유(재사용)
- [Should] FR-7: 봇 `@Async` 처리 실패 시 kind=SYSTEM, `payload_json: {"text": "처리에 실패했어요. 다시 시도해 주세요."}` 메시지를 신규 행으로 append하고 STOMP 발행. 처리 타임아웃: 30초. 타임아웃 초과 시 동일 실패 처리

**[커플 방 — CoupleChatService]**
- [Must] FR-8: `POST /api/v1/chat/couple/{groupId}/messages` — 입력 `{text}`. 호출자가 해당 groupId의 활성 멤버인지 검증. 텍스트 메시지 DB 저장 + 상대방에게 STOMP 브로드캐스트 + APNs 푸시
- [Must] FR-9: `GET /api/v1/chat/couple/{groupId}/messages?cursor={id}&limit={n}` — 커플 방 메시지 목록. cursor 기반 페이지네이션(cursor 미전달 시 최신 20건, 최대 50건). 메시지 없으면 빈 배열 + `hasMore: false`
- [Must] FR-10: 커플 방에 MessageClassifier·봇 로직 미개입. 텍스트 저장 + 브로드캐스트만

**[실시간 — WebSocket/STOMP]**
- [Must] FR-11: STOMP 엔드포인트 `/ws/chat` 신설. 클라이언트는 연결 시 Bearer 토큰으로 인증
- [Must] FR-12: 봇 방 구독 토픽 `/topic/chat/bot/{userId}`. 커플 방 구독 토픽 `/topic/chat/couple/{groupId}`
- [Must] FR-13: STOMP 프레임 payload 스키마: `{"messageId": Long, "roomId": Long, "senderType": "USER|BOT|SYSTEM", "kind": "TEXT|PLACE_CARDS|MEMO_PROMPT|PROCESSING|SYSTEM", "payload": {...}, "createdAt": ISO8601}`
- [Should] FR-14: 인증 실패(만료·무효 토큰) 시 연결 거부(STOMP ERROR 프레임 반환). 재연결은 클라이언트 책임

**[푸시 — APNs]**
- [Must] FR-15: `POST /api/v1/devices` — 기기 토큰 등록/갱신. 입력 `{platform: "IOS", deviceToken: String}`. 동일 `(userId, deviceToken)` 중복 등록은 upsert(updated_at 갱신)
- [Must] FR-16: `DELETE /api/v1/devices/{deviceToken}` — 기기 토큰 삭제(로그아웃 시)
- [Must] FR-17: APNs 푸시 트리거 3가지:
  - ① 파트너가 핀 저장 시 (`PinV1Controller.createPin` 기존 알림 생성 흐름에서 추가로 발송)
  - ② 커플 방 새 메시지 수신 시 (상대방 기기)
  - ③ 봇 방 처리 완료(PLACE_CARDS 등 결과 메시지 append) 시 (본인 기기)
- [Must] FR-18: APNs 인증은 **pushy 라이브러리 + .p8 키를 환경변수/Secret으로 주입**하는 토큰 기반 방식. push payload 스키마: `{"aps": {"alert": {"title": String, "body": String}, "sound": "default", "badge": 1}, "type": "PIN_SAVED|COUPLE_MESSAGE|BOT_RESULT", "roomId": Long?}`
- [Should] FR-19: APNs 응답이 `BadDeviceToken`·`Unregistered`이면 해당 device_token 행을 자동 삭제
- [Could] FR-20: 기기 토큰 1인 다중 기기 지원(같은 userId로 복수 토큰 허용). 푸시 발송은 userId에 연결된 모든 활성 토큰에 fan-out

**[계정 삭제]**
- [Must] FR-21: `DELETE /api/v1/users/me` — 삭제 대상: `users.deleted_at` 마킹, oauth 정보(`oauthId`, `oauthProvider`) 초기화, refresh 토큰, device 토큰, 본인이 보낸 chat_message(`sender_user_id` 참조 null 처리 또는 행 삭제), group_member 활성 멤버십(left_at 마킹)
- [Must] FR-22: 계정 삭제 시 활성 그룹에서 **마지막 1인**이면 그룹 soft delete + 해당 그룹의 핀은 현행 탈퇴 정책과 동일하게 그룹에 잔류
- [Must] FR-23: OAuth 공급자가 APPLE인 사용자 삭제 시 Apple token revoke API 호출(pushy와 별개로 Apple REST API 직접 호출). revoke 실패(네트워크 오류·Apple 서버 오류)여도 계정 삭제는 완료(best-effort). revoke 실패는 로그 기록
- [Must] FR-24: 재가입 허용 정책 — 삭제된 계정과 동일 oauthId로 재로그인 시 정상 가입 처리. 재가입 시 기존 데이터(핀·채팅 히스토리·그룹) 복구는 보장하지 않음. AuthService가 삭제된 행을 재활성화하거나 신규 행을 생성하는 방식은 설계 단계에서 결정

### 비즈니스 규칙

- [Must] BR-1: BOT 방은 userId당 1개. 최초 `POST /chat/bot/messages` 시 없으면 자동 생성, 이후 재사용
- [Must] BR-2: COUPLE 방은 groupId당 1개. 최초 `POST /chat/couple/{groupId}/messages` 시 없으면 자동 생성
- [Must] BR-3: 커플 방 진입 시 호출자가 해당 groupId의 활성 멤버(`group_members.left_at IS NULL`)인지 검증. 비멤버는 403
- [Must] BR-4: PROCESSING 메시지와 결과 메시지는 별개 행. `@Async` 완료 후 결과 메시지(PLACE_CARDS 등)를 신규 행으로 append. PROCESSING 행은 수정하지 않음. 모든 메시지(PROCESSING 포함)는 즉시 DB에 영속화
- [Must] BR-5: 커플 방 멤버 1명(파트너 없음) 상태에서도 메시지 저장은 허용. 브로드캐스트 수신자가 없으면 STOMP 발행 생략, APNs 발송 생략
- [Must] BR-6: 계정 삭제는 그룹 탈퇴 흐름(`GroupMemberService.leaveGroup`)을 내부적으로 재사용. 봇 매핑 해제(`BotUserMappingService.unlink`)도 포함
- [Must] BR-7: 기존 `/chatbot/webhook` 경로는 P2 변경과 무관하게 동작. 기존 카카오봇 세션(인메모리)·응답 포맷에 영향 없음
- [Must] BR-8: 모든 신규 엔드포인트(`/chat/**`, `/devices`, `DELETE /users/me`)는 Bearer 인증(P1) 전용. 쿠키 인증 경로 미추가
- [Should] BR-9: device_token 등록 시 동일 토큰이 다른 userId로 등록되어 있으면 기존 행의 userId를 신규로 교체(기기 재가입 시나리오)

### 품질 기대

- [Should] QE-1: 봇 처리 완료 메시지가 사용자 화면에 나타나는 지연이 체감상 즉각적으로 느껴질 것(STOMP push 기반, 폴링 없음)
- [Should] QE-2: 계정 삭제 후 동일 oauthId로 재로그인 시 재가입이 정상 완료되어 신규 빈 계정(그룹 미가입, 채팅 히스토리 없음)으로 진입할 것
- [Should] QE-3: 기존 카카오봇 사용자가 P2 배포 전후 동일하게 webhook 응답을 받을 것(웹 회귀 0)

## 사용자 시나리오

**정상 흐름 — 봇 방 첫 메시지**
1. 앱이 `POST /chat/bot/messages {text: "인스타 링크"}` 전송
2. 서버: BOT 방 자동 생성 → PROCESSING 메시지 DB 저장 → 응답 반환(`{messageId, kind: "PROCESSING"}`)
3. 앱: STOMP `/topic/chat/bot/{userId}` 구독 중 → PROCESSING 메시지 UI 노출("처리 중...")
4. 서버(`@Async`): 핸들러 체인 실행 → PlaceCardBuilder → PLACE_CARDS 메시지 신규 행 append → STOMP 발행 → APNs 발송(본인 기기)
5. 앱: STOMP 수신 → 처리 완료 카드 노출

**정상 흐름 — 커플 방 메시지**
1. 사용자 A가 `POST /chat/couple/{groupId}/messages {text: "어디 갈까?"}` 전송
2. 서버: 멤버십 검증 → 메시지 DB 저장 → 사용자 B STOMP 브로드캐스트 → 사용자 B APNs 푸시
3. 사용자 B: 앱이 포그라운드면 STOMP로 수신, 백그라운드면 APNs 알림 수신

**정상 흐름 — 계정 삭제 후 재가입**
1. 사용자가 `DELETE /users/me` 호출 → soft-delete 완료
2. 동일 Apple/Kakao 계정으로 재로그인 → 재가입 처리(기존 데이터 복구 없음)
3. 신규 빈 계정으로 앱 진입(그룹 미가입 상태)

**엣지케이스**
- 봇 `@Async` 30초 초과: SYSTEM 오류 메시지를 신규 행으로 append + STOMP 발행
- 커플 방 파트너 없음(멤버 1명): 메시지 저장 성공, STOMP/APNs 발송 없음
- 계정 삭제 — 마지막 1인: 그룹 soft delete + 핀 그룹 잔류 + Apple revoke(APPLE 사용자)
- Apple revoke 실패: 오류 로그 후 계정 삭제 완료
- device_token 만료(`BadDeviceToken`): APNs 응답 처리 후 해당 토큰 행 자동 삭제
- `GET /chat/bot/messages` 메시지 없음(빈 방): 빈 배열 + `hasMore: false` 응답
- STOMP 연결 중 JWT 만료: 연결 거부, 클라이언트가 토큰 갱신 후 재연결

## 영향 범위

- **기존 NotificationService**: 파트너 핀 저장 트리거(FR-17 ①)에서 기존 알림 DB 저장과 별개로 APNs 발송이 추가됨. 기존 알림 생성 로직 무변경
- **기존 GroupMemberService.leaveGroup**: 계정 삭제(FR-21~22)에서 내부 재사용. 외부 API(`DELETE /groups/{groupId}/members`) 동작 무변경
- **기존 ChatbotWebhookService**: 핸들러 체인 공유만. webhook 진입점·응답 포맷 무변경
- **기존 MeV1Controller 또는 UserV1Controller**: `DELETE /api/v1/users/me` 추가. 기존 엔드포인트 무변경
- **기존 AuthService**: FR-24 재가입 허용 정책으로 삭제된 oauthId 재로그인 처리 분기 추가. 기존 신규 가입·정상 로그인 흐름 무변경
- **웹 클라이언트**: 신규 엔드포인트만 추가(additive). 기존 쿠키 인증·알림 폴링 무변경
- **하위 호환성**: V015~V017 마이그레이션은 신규 테이블 추가만(기존 테이블 컬럼 변경 없음). 모두 additive

## 수용 기준

- AC-1: 인증된 사용자가 `POST /chat/bot/messages` 호출 시 kind=PROCESSING인 메시지가 즉시(동기) 응답되고, DB에 저장된다 → [FR-4, BR-1, BR-4]
- AC-2: `@Async` 봇 처리 완료 후 `/topic/chat/bot/{userId}` 토픽으로 PLACE_CARDS 메시지가 발행된다 → [FR-4, FR-12, FR-13]
- AC-3: `GET /chat/bot/messages?limit=20` 호출 시 최신 20건 이하의 메시지가 cursor 기반으로 반환된다. 메시지가 없으면 빈 배열과 `hasMore: false`가 반환된다 → [FR-5]
- AC-4: 인증된 그룹 멤버가 `POST /chat/couple/{groupId}/messages` 호출 시 메시지가 DB 저장되고 `/topic/chat/couple/{groupId}`에 브로드캐스트된다 → [FR-8, FR-10, BR-3]
- AC-5: 비멤버가 `POST /chat/couple/{groupId}/messages` 호출 시 403 응답이 반환된다 → [BR-3]
- AC-6: STOMP `/ws/chat` 연결 시 유효한 Bearer 토큰이 없으면 연결이 거부된다 → [FR-11, FR-14]
- AC-7: `POST /api/v1/devices`로 동일 (userId, deviceToken) 재등록 시 중복 행 없이 updated_at만 갱신된다 → [FR-15, BR-9]
- AC-8: 파트너가 핀 저장 시 본인 기기에 등록된 device_token으로 APNs 푸시가 발송된다 → [FR-17 ①, FR-18]
- AC-9: APNs 응답이 `BadDeviceToken`이면 해당 device_token 행이 DB에서 삭제된다 → [FR-19]
- AC-10: `DELETE /api/v1/users/me` 호출 시 users.deleted_at이 마킹되고, refresh 토큰·device 토큰이 삭제된다 → [FR-21]
- AC-11: 그룹 내 마지막 멤버가 계정 삭제 시 groups.deleted_at이 마킹된다 → [FR-22, BR-6]
- AC-12: Apple 공급자 사용자 계정 삭제 시 Apple token revoke를 시도한다. revoke 대상 토큰/인프라가 없으면(P1 미저장) 스킵하고 로그를 남긴다. revoke 시도/스킵/실패 어느 경우든 users.deleted_at 마킹은 완료된다 → [FR-23] (best-effort. .p8 client_secret·refresh token 저장은 P2 범위 밖)
- AC-13: 계정 삭제 후 동일 Apple/Kakao oauthId로 재로그인 시 재가입이 정상 완료되고 그룹 미가입 상태의 빈 계정으로 진입한다. 이전 계정의 핀·채팅 히스토리는 복구되지 않는다 → [FR-24, QE-2]
- AC-14: 기존 `POST /chatbot/webhook` 호출이 P2 배포 후에도 동일 응답 포맷으로 정상 동작한다 → [FR-6, BR-7, QE-3]
- AC-15: 커플 방 멤버 1명 상태에서 메시지 전송 시 저장은 성공하고 STOMP/APNs 발송은 생략된다 → [BR-5]

## 배포 계획

P2는 3개 PR로 분할 배포한다. 모두 additive(신규 테이블·엔드포인트 추가만)이므로 각 PR 단독 배포 시 기존 웹/카카오봇 경로에 회귀 없음. 브랜치·스택 전략은 설계 단계에서 architect가 결정한다.

| PR | 범위 | 포함 FR/BR | 의존 |
|----|------|-----------|------|
| PR-1 | 채팅 데이터모델 + 봇방 + 커플방 + STOMP | FR-1~14, BR-1~5, BR-7~8 | P1 완료(develop 기반) |
| PR-2 | APNs 클라이언트 + devices 엔드포인트 + 푸시 트리거 | FR-3, FR-15~20, BR-9 | PR-1 완료(커플방·봇방 roomId가 푸시 payload에 포함됨) |
| PR-3 | 계정 삭제 + 재가입 정책 | FR-21~24, BR-6 | PR-1·PR-2 완료(device 토큰 삭제, chat_message 처리 포함) |

**의존 순서 근거:**
- PR-2의 커플 방·봇 결과 푸시 트리거(FR-17 ②③)는 PR-1의 `chat_room`·`chat_message` 테이블과 room 생성 로직에 의존한다
- PR-3의 계정 삭제는 PR-1의 `chat_message` 처리와 PR-2의 device 토큰 삭제를 포함하므로 두 PR 완료 후 진행한다

## 제외 범위 (Out of scope)

- 웹 클라이언트용 채팅 UI (P5 범위)
- 카카오봇 레이어(`domain/bot`, `/chatbot/webhook`, 쿠키 auth) 제거 — 컷오버(앱 게시 후) 범위
- 채팅 메시지 읽음 처리·읽음 상태 UI
- FCM(Android) 푸시
- 채팅 메시지 삭제·수정
- 그룹 채팅(3인 이상)
- WebSocket 수평 확장(Redis pub/sub 브로커) — 단일 인스턴스 운영 가정
- `chat_message.payload_json` 스키마 버전 관리(마이그레이션 전략) — 초기 버전 고정
- 계정 삭제 시 이전 데이터(핀·채팅 히스토리) 복구 보장

## 마이그레이션·데이터 영향

| 버전 | 내용 | PR |
|------|------|----|
| V015 | `chat_room`, `chat_message` 테이블 신설 | PR-1 |
| V016 | `devices` 테이블 신설 | PR-2 |
| V017 | 계정 삭제 관련 컬럼 변경이 필요한 경우 추가 (설계 단계 확정) | PR-3 |

기존 테이블(users, groups, group_members, pins, notifications 등) 컬럼 변경 없음. 모두 additive.

## 리스크

| 항목 | 내용 | 완화 방안 |
|------|------|----------|
| STOMP 신규 도입 | 코드베이스에 WebSocket/STOMP 없음 → 검증 필요 | 단일 인스턴스 STOMP(외부 브로커 없음)로 범위 최소화 |
| Apple token revoke API | Apple 서버 의존성 추가 | best-effort(실패 시 로그 후 계정 삭제 완료) |
| APNs .p8 키 관리 | 키 파일 유출 시 전체 푸시 영향 | 환경변수/Secret Manager 보관. 코드베이스 미포함. pushy 라이브러리로 주입 |
| 봇 @Async 실패 | PROCESSING 메시지 이후 응답이 없는 상태 방지 | 30초 타임아웃 + SYSTEM 오류 메시지 신규 행 append |
| 재가입 구현 분기 | soft-delete 행 재활성화 vs 신규 행 생성 중 선택에 따라 oauthId UNIQUE 제약 처리 방식이 달라짐 | 설계 단계에서 architect가 결정. PRD는 정책만 명시(재가입 허용, 데이터 복구 미보장) |

---

추가 확인 사항 없음. PRD가 확정되었습니다.
