# PRD — GC-1: 백엔드 그룹 채팅 + 릴스 등록 기반

> 확정: 2026-06-10. 소스: `context/chat/status.md` GC-1 FR + `context/chat/architecture.md` 설계 결정.
> Q&A 확정: Q1 방 생성=그룹 생성 시 자동+V021 백필+get-or-create 안전망 / Q2 추출 deadline=15초 / Q3 발신자 탈퇴=영구 등록전(MVP) / Q4 채팅 알림=APNs만(알림함 미적재)

## 1. 배경

모아보기(봇 티키타카)를 그룹원 간 단체 채팅방으로 전환한다. 릴스 링크는 REEL_LINK 메시지로 채팅에 쌓이고, 발신자가 「장소 등록하기」 시점에 온디맨드 추출→위시/발견핀 저장하면 전 멤버의 버튼이 「구경하실래요?」로 바뀐다. GC-1은 이 전환의 백엔드 기반 전체이며, **봇 흐름 무변경(병행 운영)** 으로 머지해도 기존 앱 동작 변화가 없다.

## 2. 목표 / 비목표

| 목표 | 비목표 (다른 Phase) |
|---|---|
| 그룹 채팅방 전송/조회/목록/읽음/푸시 REST API | iOS UI 전부 (GC-2) |
| REEL_LINK 메시지 + `registered` 파생 플래그 | 썸네일 og:image→S3 (GC-3) |
| 온디맨드 추출 API (동기, deadline 15초) | 봇 코드 제거·soft delete (GC-3) |
| V021 (`chat_room_reads` + GROUP 방 백필) | WebSocket 재도입 / 채팅의 알림함 적재 |

## 3. 요구사항

### [Must]

| ID | 요구사항 |
|----|----------|
| FR-GC1-1 | **그룹 채팅방** — 그룹당 활성 1개의 멤버 공용 방(GROUP). **그룹 생성 시 자동 생성 + 기존 그룹 V021 백필 + 전송/조회 시 get-or-create 안전망(idempotent)**. 모든 작업에 활성 멤버십 강제(403 `GROUP_NOT_MEMBER`). `CoupleChatService` 패턴 재사용 |
| FR-GC1-2 | **멤버별 읽음** — `chat_room_reads(room_id, user_id, last_read_message_id)`, `UNIQUE(room_id, user_id)`. 포인터는 전진만(역행 요청 무시). 방 목록 unread(boolean, 인스타식)의 기반 |
| FR-GC1-3 | **REEL_LINK kind** — payload `{url, thumbnailKey:null}`. 전송 API kind 분기(TEXT/REEL_LINK, 클라이언트 지정). REEL_LINK URL = `https://`+인스타 패턴 검증(`INSTAGRAM_URL`/`Pin.validateInstagramUrl` 선례), TEXT = 기존 2000자 가드 |
| FR-GC1-4 | **registered 파생** — 페이지 응답에서 REEL_LINK URL 배치 IN 쿼리 1회로 `registered: Bool` 계산(`EXISTS pins WHERE group_id+instagram_url AND deleted_at IS NULL`). 상태 컬럼 금지 |
| FR-GC1-5 | **온디맨드 추출 API** — `POST /api/v1/chat/groups/{groupId}/messages/{messageId}/extract`. `BotChatProcessor.extractHits` 파이프라인 재사용(채팅 메시지 append 없음), 동기 카드 목록 반환, **deadline 15초** |
| FR-GC1-6 | **추출 권한 = 발신자만** — `chat_message.sender_user_id == 요청자` 서버 강제, 위반 403. **발신자 탈퇴(NULL) = 영구 등록전(확정)** |
| FR-GC1-7 | **방 목록 API** — 활성 그룹별 방 요약 + 멤버별 `hasUnread`(boolean) + 마지막 메시지 preview(TEXT=본문, REEL_LINK=「릴스 링크」). 백필로 전 그룹에 방 존재 보장, 누락 시 get-or-create |
| FR-GC1-8 | **푸시 일반화** — `pushGroupMessage`: 발신자 제외 전 활성 멤버 APNs, afterCommit best-effort(실패해도 전송 성공), 1인 그룹 생략, TEXT/REEL_LINK 문구 분기. **알림함 미적재(확정)** |
| BR-GC1-1 | **봇 흐름 무변경** — BOT 방·`/chat/bot/*`·카카오 웹훅 코드 경로 수정 금지(병행 운영, 제거는 GC-3) |

### [Should]
- 추출 결과 0곳 = **200 + 빈 목록** / 파이프라인 예외·타임아웃 = **명시적 에러 코드**(클라 재시도 가능) — 응답으로 구분

### [Could]
- 동일 메시지 추출 중복 호출 서버 가드(추출은 read-only라 무해 — 클라 버튼 비활성 1차 방어, 서버 가드 선택)

## 4. 정책 상세 (확정)

- 장소 **저장**은 GC-1 신규 API 없음 — iOS가 기존 핀 생성 API(`savePlaceCards`, 409 `PLC_DUPLICATE_PIN` 흡수) 재사용. 핀 등록 알림 = 기존 MANUAL_PIN 경로 → registered는 pins에서 자동 파생
- 같은 릴스 재공유 → 등록 후 두 메시지 모두 registered=true (`UNIQUE(group_id, instagram_url)` 정합)
- 릴스 핀 전부 삭제 → registered=false 회귀(발신자 재등록 가능, 자연스러운 동작으로 수용)
- 방 생성: 그룹 생성 훅 + V021 백필 + get-or-create 안전망 (가입 시 no-op — 방은 그룹당 1개)
- unread 표현: 인스타식 boolean(`hasUnread`) — DM 봇방 목록(PR #108) 패턴 동일

## 5. 엣지 케이스

| 케이스 | 동작 |
|---|---|
| 1인 그룹 전송 | 정상 저장, 푸시 생략 |
| 비멤버 전송/조회/추출/읽음 | 403 `GROUP_NOT_MEMBER` |
| 타인 메시지·탈퇴 발신자(NULL) 메시지 추출 | 403 |
| REEL_LINK 아닌 메시지에 추출 호출 | 400 |
| `http://`·비인스타 URL REEL_LINK | 400 |
| TEXT 2000자 초과 | 400 |
| 읽음 포인터 역행 | 무시(현재 값 유지, 에러 아님) |
| 추출 0곳 / 파이프라인 실패 | 200+빈 목록 / 에러 코드(재시도 가능) |
| 탈퇴·삭제 그룹 접근 | 기존 그룹 검증 규칙(403/404) |

## 6. 수용 기준 (AC)

1. 멤버 TEXT 전송 → 타 멤버 페이지 조회 수신, 비멤버 403
2. REEL_LINK 전송 → 프레임 `kind=REEL_LINK`+`registered=false`
3. 해당 URL 핀 저장(기존 핀 API) 후 재조회 → `registered=true`, 같은 URL 타 메시지도 true
4. 릴스 핀 전부 삭제 → `registered=false` 회귀
5. 발신자 추출 호출 → 동기 카드 목록(메시지 append 없음); 타 멤버 403
6. 멤버 A 읽음 갱신 ≠ 멤버 B unread (chat_room_reads 독립)
7. 방 목록: 전 활성 그룹 노출 + 마지막 메시지 preview kind 규칙 준수
8. 2인+ 그룹 푸시 발송 시도(실패해도 저장 성공), 1인 그룹 생략
9. 기존 봇 채팅 테스트 회귀 없음(선행 실패는 베이스 대조 — 메모리 local-test-baseline)
10. V021 기존 데이터 무손실 적용(GROUP 방 백필 포함)
