# PRD: 그룹별 봇 DM 백엔드 (GM-2 · B단계)

## 배경
현재 iOS 봇 채팅방은 `userId`당 1개(`chat_room` BOT, group_id 없음)이고, 릴스 저장 그룹은 "최신 활성 그룹 1개"로 때우는 `TODO(GM-2)` 상태다. GM-1로 1인 N그룹이 열렸으므로 "어느 그룹에 저장할지"가 모호하다. DM 탭을 그룹별 봇방 목록으로 만들어 "방 = 그룹"을 명확히 하고, 그 방에서 보낸 릴스를 그 그룹에 저장한다. (이 PR은 백엔드 계약만 — iOS는 A단계)

## 요구사항
### Must
- FR-1 봇방을 그룹별로: (owner_user_id, group_id) 활성 1개
- FR-2 DM 목록 API: 사용자의 활성 그룹별 봇방 목록 — groupId, groupName, 마지막 메시지 미리보기, lastSenderType, unread. 활성 그룹 전부 표시(아직 대화 없으면 빈 미리보기)
- FR-3 그룹별 봇방 메시지 전송 postMessage(userId, groupId, text) — 비멤버 403
- FR-4 그룹별 봇방 메시지 조회(cursor 페이징) + 조회 시 읽음 처리
- FR-5 읽음 추적: 마지막 메시지가 봇(BOT)이고 그 이후 미조회면 unread=true. 방 조회 시 last_read 갱신. 마지막이 내 메시지(USER)면 unread=false
- FR-6 목록·메시지 응답에 groupId 노출(iOS가 릴스 저장 그룹으로 사용)

### Should
- FR-7 미리보기 규칙: TEXT=본문 일부, PLACE_CARDS="장소 N곳", SYSTEM=안내문

### 비범위
- iOS UI(A) · 맵 최적화(C) · 그룹관리/알림(D) · 카카오 챗봇

## 수용 기준
- AC-1 chat_room BOT에 group_id + (owner_user_id, group_id) WHERE type=BOT AND deleted_at IS NULL 부분 UNIQUE
- AC-2 GET /chat/bot/rooms → 활성 그룹별 봇방 목록(위 필드)
- AC-3 POST /chat/bot/{groupId}/messages → 그 그룹 봇방 저장(비멤버 403)
- AC-4 GET /chat/bot/{groupId}/messages → 메시지 페이지 + last_read 갱신
- AC-5 마지막 BOT + 미조회 → unread=true, 조회 후 → false, 마지막 USER → false
- AC-6 목록/메시지 응답에 groupId 포함
- AC-7 봇방 없는 활성 그룹도 목록 표시(미리보기 null, unread=false)
- AC-8 기존 userId 단일 봇방 데이터 처리(베타 규모) — 설계에서 확정
- AC-9 카카오 챗봇·커플방 무영향

## 설계에서 확정할 결정
1. 봇방 생성 시점: 그룹 참여 시 자동 vs 첫 조회/메시지 시 lazy
2. 기존 BOT 방 마이그레이션: soft delete 후 재생성 vs group_id 귀속
3. 읽음 저장 위치: chat_room.last_read_message_id 단일 컬럼(봇방 owner 전용)
4. 기존 /chat/bot/messages 엔드포인트 호환(develop의 현 iOS 봇 호출 보호)
