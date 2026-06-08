# 자기점검 + 리뷰 결과 (오케스트레이터 직접 — oh-my-gx qa/security agent 미반환 대체)

## CERTAIN (자동 수정 대상)
- 0건.

## 검증 내역
### 컴파일
- `./gradlew compileJava compileTestJava` → BUILD SUCCESSFUL (exit 0). 봇방 시그니처 변경(createBotRoom(owner,group), findActiveBotRoom(owner,group), postMessage(userId,groupId,text))에 따른 호출부(DemoSeedRunner, 컨트롤러, 어댑터, 테스트) 전부 정합.

### 기능 정합 (설계/PRD 대비)
- **V020**: last_read_message_id 추가 + 레거시 BOT(group_id null) soft delete + BOT UNIQUE (owner,group) 재정의. group_id 컬럼 재사용(추가 안 함). 롤백 주석 포함. (AC-1/AC-8)
- **ChatRoom**: guard BOT "owner+group 필수"(불변식 전환), createBotRoom(owner,groupId), markRead 단조 전진(역행 방지). (FR-1/FR-5)
- **BotChatService**:
  - postMessage(userId,groupId,text): requireActiveMembership → ensureBotRoom(충돌 폴백) → append → afterCommit processAsync. (FR-3/AC-3)
  - getBotMessages(userId,groupId,cursor,limit): 멤버십 + 페이지 + markRoomRead(최신 id). @Transactional(쓰기). (FR-4/AC-4)
  - getBotRooms(userId): listMyGroups 순회 → 실제 방 summarize / 없으면 emptySummary(가상 항목). (FR-2/AC-7)
  - unread = 최신 BOT && (lastRead null || lastRead < latest.id). USER 마지막 → false. (FR-5/AC-5)
  - preview: TEXT/SYSTEM/MEMO_PROMPT 40자, PLACE_CARDS "장소 N곳", PROCESSING 안내. (FR-7)
  - 하위호환 래퍼: postMessage(userId,text)/getBotMessages(userId,cursor,limit) → 최신활성그룹 폴백(deprecated). (호환)
- **Controller/Dto/ApiSpec**: GET /chat/bot/rooms, POST·GET /chat/bot/{groupId}/messages 신설 + deprecated 2 유지. MessagesResponse groupId 오버로드(기존 from 보존). BotRoomSummaryResponse. (AC-2/AC-6)
- **BotChatProcessor/CoupleChatService/카카오 챗봇**: 무변경. (AC-9)

### 보안 (security 관점 직접)
- 모든 진입(전송/조회)에 requireActiveMembership → 타 그룹 봇방 접근 403(GROUP_NOT_MEMBER). (테스트 2건 커버)
- payload 파싱 try-catch + 빈 값 폴백(미리보기). JdbcTemplate(테스트)/JPA 파라미터 바인딩 — SQL 인젝션 없음.

### 테스트 (BotChatServiceGroupIT)
- 그룹별 방 격리, 같은 그룹 재사용, DM 목록(실제+가상 혼합), unread 전이(BOT→true / 조회 후 false / USER→false), 멤버십 403(전송·조회), 하위호환 폴백 — AC-1~9 커버.
- 실행 결과: **BUILD SUCCESSFUL (1m42s, 전 케이스 통과)** — PostgreSQL Testcontainer

## QUESTION (비차단)
- 없음. (그룹 전환 시 focus 유지 같은 iOS UX 이슈는 A단계 범위)
