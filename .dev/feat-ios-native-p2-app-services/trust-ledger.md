# Trust Ledger — P2

## 통합 감사 (review) — PR-1 (채팅+STOMP)

요약: CRITICAL 0 / HIGH 4 / MEDIUM 6 (ZT) + QA Critical 2. 판정: 조치 필요(HIGH 해소 후 머지).

### Critical (자동 수정 대상)
- [SPEC/Critical] 방 생성 race — BotChatService.ensureBotRoom:80-83 / CoupleChatService.ensureCoupleRoom:85-88
  - 근거: 동시 진입 시 부분 UNIQUE(uq_chat_room_bot_owner/couple_group) 위반 → DataIntegrityViolationException 미처리 → 500 (BR-1/2 정상 재시도 실패)
  - 권고: save를 try-catch(DataIntegrityViolationException) → 재조회 폴백 (GroupMemberService 동일 패턴)
- [SPEC/Critical] FR-7 결함 — BotChatProcessor.publishSafely:188-194
  - 근거: appendBotSystem 실패 시 appendSystemSafely null → STOMP 발행 생략 → PROCESSING 고아(결과 없음)
  - 권고: null 시 log.error 격상 + (가능 시) 에러 프레임 발행

### HIGH (자동 수정)
- [RISK/HIGH] STOMP SEND principal 미검증 — StompAuthChannelInterceptor:52-58
  - 근거: CONNECT/SUBSCRIBE 외 default 통과. /app prefix 등록되어 SEND 가능, principal 검증 없음
  - 권고: SEND 시 principal null이면 MessageDeliveryException
- [RISK/HIGH] SUBSCRIBE 화이트리스트 미적용 — StompAuthChannelInterceptor:99
  - 근거: /topic 하위 bot/couple 외 미인식 destination 무조건 통과(SimpleBroker /topic 전체 활성)
  - 권고: else 분기 거부(화이트리스트). [사용자 Q1 확인]
- [ASSUMPTION/HIGH] @Async 예외 핸들러 부재 — AsyncConfig:23-33
  - 근거: AsyncUncaughtExceptionHandler/rejectedExecutionHandler 미등록 → 비정상 예외/포화 시 조용히 무시
  - 권고: AsyncConfigurer 구현 + CallerRunsPolicy + keepAlive 60s
- [POLICY/HIGH] BR-8 permitAll 범위 — SecurityConfig:41
  - 근거: /ws/chat/** 와일드카드 과도(핸드셰이크는 /ws/chat 단일, SockJS 미사용)
  - 권고: /ws/chat로 축소(핸드셰이크 경로 검증 후)

### MEDIUM (일부 자동 수정 / 일부 이월)
- [RISK/MEDIUM] text 최대 길이 미검증 — ChatV1Dto:21,25 → @Size(max) 추가(자동 수정, bot 2000/couple 1000)
- [RISK/MEDIUM] cursor 음수/0 방어 없음 — ChatV1Controller:86-93 → cursor<1 시 null 처리(자동 수정)
- [RISK/MEDIUM] setAllowedOriginPatterns("*") CSWSH — WebSocketStompConfig:31 → 네이티브 앱 전용 전제. 브라우저 WS 도입 시 Origin 화이트리스트. **이월(문서화)**
- [GAP/MEDIUM] SUBSCRIBE 멤버십 스냅샷 — 탈퇴 후 기존 구독 유지로 상대 메시지 수신 가능(StompAuthChannelInterceptor:92-97). 서버 발행 시 활성 멤버 재확인 또는 세션 종료 필요. **이월(P5/컷오버 연계, 단일 인스턴스 베타 위험 낮음)**
- [GAP/MEDIUM] V015 FK cascade 없음 — soft-delete 전용이라 현재 무해. **이월(물리 삭제 시나리오 전)**
- [ASSUMPTION/MEDIUM] CONNECT 후 토큰 만료 미재검증 — 기존 세션 유지. PRD 정책과 비충돌. **이월(문서화)**
- [ASSUMPTION/MEDIUM] INSTAGRAM_URL 리터럴 중복(BotChatProcessor↔MessageClassifier) — 향후 발산 위험. **이월(리팩터 시 상수 추출)**

### QA QUESTION
- appendBotSystem senderType=BOT+kind=SYSTEM (SenderType.SYSTEM 미사용) → [사용자 Q2]
- Bearer 대소문자 무시(P1 일관성) → 유지(RFC 7235 준수)
- doProcess trim guard, POST 응답에 USER 메시지 id 미포함 → 설계 의도(유지)

### FR-7 [Should] 30초 타임아웃
- 명시적 30초 인터럽트 대신 외부 HTTP read timeout + ChatbotContext 데드라인으로 시간 상한. 무한 hang 불가 검증됨. PlaceProperties.syncDeadlineMs/HTTP timeout 설정값 문서화 권고. **이월**

---

## 통합 감사 (review) — PR-2 (APNs+devices)

요약: ZT CRITICAL 0 / HIGH 3 / MEDIUM 5 / LOW 2. QA 확정 Critical 0. 교차검증 FR-3/15~20·BR-9·AC-7~9 전부 정합. 판정: 보안 HIGH 일부 수정 권장.

### 자기점검 기 수정(완료)
- ApnsClientFactory destroyMethod NPE 제거 / ApnsPushSender .get(10s) / AC-9 DeviceService.removeByToken(@Transactional) / @Modifying flushAutomatically.

### HIGH
- [RISK/HIGH] 로그에 deviceToken 전체 노출 — ApnsPushSender log.warn/info `token={}`. → 토큰 마스킹(prefix 8자) 또는 deviceId. **자동 수정 대상**
- [POLICY/HIGH] DELETE /devices/{deviceToken} PathVariable 검증 미비 — @Validated + @Size/@NotBlank 없음. **자동 수정 대상**
- [ASSUMPTION/HIGH] .p8 파싱 실패 시 무음 no-op(부팅 성공, 푸시 미발송) — log.error만, 운영 가시성. **이월(health/알림 연동은 .p8 도입 시)**

### MEDIUM/LOW (이월)
- [RISK/MED] BR-9 reassign 토큰 탈취 벡터 — 단, bearer 토큰까지 필요(=계정침해 2차). 의도된 동일기기 재가입 정책. 모니터링 로그 존재. **이월(정책 문서화)**
- [ASSUMPTION/MED] afterCommit blocking(.get(10s)) 스레드 점유 — 2인 규모 무해, 스케일 시 @Async 분리. **이월**
- [ASSUMPTION/MED] 동시 동일토큰 등록 race — DataIntegrityViolation 폴백 처리됨. 정책 명시만. **이월**
- [GAP/MED] 410 Gone → rejection reason "Unregistered" 매핑 — pushy 0.15.x는 410을 "Unregistered" reason으로 전달(DEAD_TOKEN_REASONS 커버). 확인됨, 주석 권고. **이월(확인 완료)**
- [GAP/MED] 1인 활성 토큰 수 상한 없음 — fan-out 남용 방어. **이월(상한 5 권고)**
- [LOW] fan-out 부분 실패 가시성 / badge 정적 1(FR-18 명시값) — **이월**

---

## 통합 감사 (review) — PR-3 (계정삭제+재가입)

요약: ZT CRITICAL 0 / HIGH 3 / MEDIUM 5 / LOW 1. QA Critical(오탐 1). 교차검증 FR-21~24·BR-6·BR-8·AC-10~13 전부 정합. 재가입·로그인 회귀 0(인증 통합 테스트 40개 통과). 판정: 정합성 수정 후 머지, 구조적 HIGH 이월.

### 수정 완료
- [QA Critical/오탐→정정] UserModel `@Column(kakao_user_id, unique=true)` → unique 제거 + 주석 V017 정합. (ddl-auto=none이라 런타임 무영향, 통합 테스트 부팅 통과로 오탐 확인. 정합성 정정.)
- [GAP/HIGH] UserDeletionService leaveGroup TOCTOU race → try-catch(GROUP_NOT_MEMBER 흡수) + unlink 정확히 1회. 동시 그룹 변경 시 삭제 롤백 방지.
- [POLICY/MED] V017 `DROP CONSTRAINT IF EXISTS` 방어.
- [Warning 자기점검] unlink 이중 실행 정리, nullifySender deleted_at 필터.

### 이월 (구조적/정책)
- [RISK/HIGH] **탈퇴 후 STOMP 세션 미종료** — 기존 WebSocket 구독 잔존으로 상대 메시지 계속 수신 가능. **사용자 결정: 이월**(단일 인스턴스 베타, PR-1 구독 멤버십 스냅샷 갭과 동일 성격, 교차 관심사). 후속: 탈퇴 시 SimpUserRegistry 세션 종료.
- [RISK/HIGH] 탈퇴 후 기존 access JWT가 TTL 만료까지 유효(stateless 구조적 한계). access-ttl=env(JWT_ACCESS_TTL_SECONDS). **이월**: 배포 TTL 짧게(≤1h) 권고, 길면 블랙리스트 검토. refresh는 isActive() 가드로 차단됨.
- [POLICY/HIGH] Apple revoke 미수행 — App Store 5.1.1(v) 회색지대. Q5 best-effort 확정. **이월**: .p8 client_secret + refresh token 저장 인프라 구축 후 실제 revoke(P3+). 심사 제출 전 확인.
- [RISK/MED] 탈퇴 사용자 chat_message payload 보존(sender만 null) — PRD 명시(메시지 보존). **이월(개인정보 법무 검토)**.
- [GAP/MED] 동시 삭제+refresh race — isActive() 가드가 최종 차단. **이월**.
- [ASSUMPTION/HIGH] uq_users_kakao_user_id partial은 Apple(NULL) 무관 — Apple 중복은 uq_users_oauth 단독 처리. 설계 문서화. **이월(문서)**.
- [Info] UserDeletionService 통합 테스트 부재 — **이월(후속 테스트 권고)**. save(user) 중복, ApiSpec 서술 정리.

### AC 충족 (인수 + 통합 테스트)
AC-10(DELETE /me 마킹+정리)·AC-11(마지막1인 그룹 삭제)·AC-12(Apple revoke 시도/스킵+마킹)·AC-13(재가입 신규계정, 통합 테스트 3경로 검증) 전부 충족. 로그인 회귀 0(인증 테스트 40개).

