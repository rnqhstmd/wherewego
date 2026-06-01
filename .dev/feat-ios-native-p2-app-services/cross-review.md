# Cross-Review 결과

- advisor: claude (qa-manager + security-auditor) — codex는 402 deactivated_workspace로 실패하여 전환
- 브랜치: feat/ios-native-p2-account-deletion (base: develop)
- DEV_DIR: .dev/feat-ios-native-p2-app-services
- 범위: P2 3개 스택 PR 누적(PR-1 채팅+STOMP / PR-2 APNs+devices / PR-3 계정삭제+재가입)

## AC 충족 매트릭스

| AC | 충족 | 근거 |
|----|------|------|
| AC-1 PROCESSING 즉시 응답+저장 | O | BotChatService.postMessage TX-A |
| AC-2 봇 결과 /topic/chat/bot/{userId} 발행 | O | BotChatProcessor.publishSafely afterCommit |
| AC-3 cursor 조회/빈 방 | O | getBotMessages + ChatMessagePageResult.of + normalizeCursor |
| AC-4 커플 저장+브로드캐스트 | O | CoupleChatService.postCoupleMessage + broadcastToOthersAfterCommit |
| AC-5 비멤버 403 | O | requireActiveMembership → GROUP_NOT_MEMBER |
| AC-6 STOMP 무토큰 거부 | O | StompAuthChannelInterceptor.authenticateConnect |
| AC-7 device upsert updated_at | O | DeviceService.upsert touch + V016 부분 UNIQUE |
| AC-8 핀 저장 파트너 APNs | O | PinV1Controller.pushPinSaved → fan-out |
| AC-9 BadDeviceToken 정리 | O | ApnsPushSender.handleRejection → removeByToken(@Transactional) |
| AC-10 DELETE /me 마킹+정리 | O | UserDeletionService(refresh/device/chat/group/delete) |
| AC-11 마지막1인 그룹 삭제 | O | leaveGroup 재사용 |
| AC-12 Apple revoke 시도/스킵+마킹 | O | AppleTokenRevoker afterCommit, deleted_at 선커밋 |
| AC-13 재가입 신규계정 | O | V017 partial unique + 활성 조회 + 통합 테스트 3경로 |
| AC-14 webhook 회귀0 | O | ChatbotWebhookService/체인 무변경(diff 미수정) |
| AC-15 멤버1명 저장만 | O | broadcastToOthers isEmpty early return |

**[Must] AC-1~15 전체 충족(15/15).** 로그인 회귀 0(인증 테스트 40개 통과).

## 설계 범위 이탈

**이탈 없음.** 범위 외 수정 2건 모두 정당:
- ApiControllerAdvice ConstraintViolationException 핸들러 — PR-2 PathVariable 검증의 필수 수반(400 매핑). Trust Ledger 기록됨.
- UserModel @Column(unique=true) 제거 — V017 partial unique 정합 보정. Trust Ledger 기록됨.

## 신규 위험 (trust-ledger·self-check 중복 제외 — 3 PR 결합 관점)

### Warning (실질 HIGH — 결합 위험)
- [GAP] **커플 방(COUPLE chat_room) soft-delete 누락** — UserDeletionService.softDeleteByOwner는 BOT 방만 정리. 마지막 1인 탈퇴로 그룹이 soft-delete돼도 해당 groupId의 COUPLE chat_room은 `deleted_at` NULL(활성) 잔존.
  - 위치: UserDeletionService(softDeleteByOwner BOT 한정) + GroupMemberService.leaveGroup(커플 방 정리 없음)
  - 실제 격리: 재가입은 신규 userId·신규 groupId(BIGSERIAL 미재사용)라 이전 커플 방 접근 불가(requireActiveMembership도 탈퇴 그룹 차단). **AC-13 실질 위반 아님** — 고아 활성 방 데이터 위생 문제.
  - 권고: leaveGroup 또는 UserDeletionService에서 그룹 soft-delete 시 해당 groupId COUPLE 방도 soft-delete. (ChatRoomRepository.softDeleteByGroup 추가)

### Warning (결합 race)
- [RISK] **봇 @Async race** — 삭제 트랜잭션이 봇 방 soft-delete 후, 진행 중이던 processAsync가 soft-deleted 방에 결과 append + STOMP 발행 가능(탈퇴 STOMP 세션 잔존과 결합).
  - 위치: BotChatProcessor.processAsync (방 활성 검증 없음)
  - 권고: processAsync 진입/append 전 chatRoomRepository.findById(roomId).filter(isActive) 확인, 비활성이면 스킵.

### Info (이월)
- [GAP] 커플 메시지 afterCommit 푸시가 동시 탈퇴 파트너에게 1회 발송 가능 — best-effort 범위, 이월.
- [Info] ApnsClientFactory @Bean null 반환 Spring 6.x deprecated — ObjectProvider 방어로 런타임 무해, 이월.
- [Info] CoupleChatService/PushNotificationService가 GroupMemberRepository 직접 의존 — 프로젝트 일관 패턴, 이월.

### 확인됨(안전)
- unlink @Transactional(MANDATORY) + deleteAccount @Transactional → 안전.
- V017 partial index — 통합 테스트 실 PostgreSQL(Testcontainers) 통과로 동작 확인.
- 재가입 봇 방 격리 — 신규 userId라 코드 격리 충족.

## 총평
- 강점: (1) 트랜잭션 경계/afterCommit best-effort 일관성, (2) 재가입 정책 end-to-end 완결(통합 테스트 검증).
- 합산: 신규 Critical 0, Warning 2(결합 위험), Info 3.
- 권고: 커플 방 soft-delete 누락(데이터 위생)과 봇 @Async race 가드를 후속 처리. AC-13 실질 격리는 충족.
