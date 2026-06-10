# Cross-Review 결과

- advisor: claude (qa-manager+security-auditor cross-review 미션 직접 수행 — 에이전트 산출물 미반환 이슈) + PR #118 gemini-code-assist 리뷰 통합 검증
- 브랜치: feat/group-chat-backend (base: develop)
- DEV_DIR: .dev/feat-group-chat-backend
- 실행 시각: 2026-06-10 (KST)

## AC 충족 매트릭스

| AC | 충족 | 근거 |
|----|------|------|
| AC-1 멤버 전송→타 멤버 수신, 비멤버 403 | O | GroupChatService.postMessage/getMessages `requireActiveMembership` + IT 2케이스 |
| AC-2 REEL_LINK→kind+registered=false | O | assemblePage REEL_LINK 분기 + IT |
| AC-3 핀 저장 후 true·같은 URL 동시 전이 | O | registeredUrlsOf IN 쿼리 + IT(양 메시지 검증) |
| AC-4 핀 전부 삭제→false 회귀 | O | EXISTS 파생(deleted_at IS NULL) + IT |
| AC-5 발신자 추출=동기 카드·타 멤버 403 | O | extractPlaces 검증 체인 + IT 2케이스 |
| AC-6 멤버별 읽음 독립 | **부분** | chat_room_reads 분리 + IT 통과. 단 read 행 최초 생성 race 시 rollback-only로 조회 실패(신규 위험 #2) — 수정 전까지 부분 |
| AC-7 방 목록 전 그룹+preview 규칙 | O | getRooms/previewOf + IT |
| AC-8 2인+ 푸시/1인 생략 | O | broadcastToOthersAfterCommit + IT |
| AC-9 봇 회귀 없음 | O | BotChatServiceGroupIT 통과 + develop 워크트리 대조(선행 21건 외 0) |
| AC-10 V021 무손실 적용 | O | Testcontainers Flyway 전체 적용 + 백필 멱등 |

[Must] 10건 중 9 충족 + 1 부분(AC-6 — 신규 위험 #2와 연동).

## 설계 범위 이탈

- `GroupMessagesPage.java` — 설계서 신규 9파일 목록 외 1개 추가. 프레임 페이지 합성용 record로 설계 D4의 자연 확장. 구현 보고에서 특이사항으로 이미 고지 — **정당**.
- `DeviceService.java` — javadoc 1줄(CoupleChatService→GroupChatService 참조 정합). 리네임 전파의 부수 — **정당**.
- 테스트 4곳(`PlaceSearchServiceTest` 등) — `PlaceProperties.Search` 생성자 인자 정합. 설계 "변경 범위"의 PlaceProperties 항목에 종속 — **정당**.

실질 이탈 없음.

## 신규 위험 (trust-ledger 미기재 항목만)

### Critical
- [RISK] GroupChatService.saveGroupRoomOnConflict — **catch 폴백이 작동 불능(트랜잭션 rollback-only)** *(PR #118 gemini HIGH ①, 직접 검증으로 타당 확인)*
  - 위치: `domain/chat/GroupChatService.java` saveGroupRoomOnConflict
  - 근거: BaseEntity IDENTITY 전략 → save() 즉시 INSERT. SimpleJpaRepository.save 의 참여 @Transactional 경계를 DataIntegrityViolationException 이 넘는 순간 글로벌 트랜잭션 rollback-only 마킹 → catch 후 재조회가 성공해도 커밋 시 UnexpectedRollbackException. 동시 방 생성 race 의 패자 요청이 전체 실패(메시지 미저장).
  - 권고: PostgreSQL `INSERT ... ON CONFLICT DO NOTHING`(native, 부분 UNIQUE 인덱스 술어 명시) + 재조회로 전환 — 예외 자체가 발생하지 않아 마킹 문제 원천 제거.
- [RISK] GroupChatService.saveReadRowOnConflict — 동일 구조 *(gemini HIGH ②, 타당 확인)*
  - 위치: `domain/chat/GroupChatService.java` saveReadRowOnConflict
  - 근거: 위와 동일. 두 디바이스가 같은 방을 동시에 처음 조회하면 패자의 getMessages 전체가 롤백되어 페이지 조회까지 실패.
  - 권고: `INSERT INTO chat_room_reads ... ON CONFLICT (room_id, user_id) DO NOTHING` + 재조회.

### Info
- [GAP] 동일 catch-폴백 패턴이 기존 코드에도 존재 — `BotChatService.saveBotRoomOnConflict` / `DeviceService.register` / `GroupMemberService` 폴백. BR-GC1-1(봇 무변경)·범위 최소화 원칙상 이번 PR 에서 수정하지 않음. **후속 하드닝 이슈 권고**(GC-3 또는 별도 PR).

### 중복 차단된 항목 (보고 제외)
- gemini MEDIUM getRooms N+1 → trust-ledger INFO 기재 항목과 동일(봇 목록 동형·베타 수용 문서화). 신규 아님 — PR 코멘트 회신용: "베타 수용 문서화 유지, 성장 시 batch 조회 개선" 입장.

## 총평
- 강점: registered 파생·권한 체인·읽음 독립이 IT 로 정밀 검증됨. 봇 무변경 격리가 일관됨(별도 프레임/엔드포인트).
- 합산: Critical 2, Warning 0, Info 1 (+중복 차단 1)
- 권고: Critical 2건은 ON CONFLICT DO NOTHING 전환으로 동시 수정(같은 패턴) — 기존 IT 그린 유지 + soft delete 후 재생성 케이스 1건 추가로 native 쿼리 경로 검증.

## 처리 결과 (사용자 선택: 전부 수정)

- 1번 [Critical] saveGroupRoomOnConflict → **수정됨**: `insertGroupRoomIfAbsent`(native ON CONFLICT, V021 부분 인덱스 술어 명시) + 재조회. catch 폴백 제거
- 2번 [Critical] saveReadRowOnConflict → **수정됨**: `chat_room_reads insertIfAbsent`(ON CONFLICT (room_id,user_id)) + 재조회
- 3번 [Info] 기존 코드 동일 패턴 → **함께 수정됨**(사용자가 범위 확대 선택):
  - `BotChatService.saveBotRoomOnConflict` → `insertBotRoomIfAbsent`(V020 부분 인덱스 술어)
  - `DeviceService.saveOnConflict` → `devices insertIfAbsent`(V016 부분 인덱스 술어)
  - `GroupMemberService.saveWithSlugRetry` → 사전 존재검사 `existsActiveSlug`(V019 unique 인덱스 술어와 동일 범위 — `findActiveBySlug`는 만료 필터가 있어 부적합) + catch 제거. 잔존 race(사실상 0)는 전역 INTERNAL_ERROR(기존 최종 실패와 동일 의미)
  - `GroupMemberService.acceptInviteLink`의 catch 는 **예외 종결 경로**(롤백이 의도된 결과 — GROUP_REJOIN_FORBIDDEN 변환)라 결함 아님 → 무수정
- (중복) getRooms N+1 → 기록 유지(베타 수용 문서화) — PR 코멘트 입장: 성장 시 batch 조회 개선
- 검증: 영향 IT 5클래스(GroupChat/BotChat/Device(신설 3케이스)/GroupMemberIT/GroupMemberTest) + 방 재생성 케이스(insertGroupRoomIfAbsent 경로 실검증) 추가
