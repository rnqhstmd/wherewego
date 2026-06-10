# chat 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다. Phase 정의는 [architecture.md](architecture.md) §Phase 분할.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현

## GC-1 — 백엔드: 그룹 채팅 + 릴스 등록 기반

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-GC1-1 | 그룹 채팅방 — `chat_room` type `GROUP`(COUPLE 일반화, 그룹당 활성 1방 부분 UNIQUE 유지). 전송/조회는 활성 멤버십 강제(`GROUP_NOT_MEMBER` 403), `CoupleChatService` 패턴 재사용 | ✅ | [#118](https://github.com/rnqhstmd/wherewego/pull/118) |
| FR-GC1-2 | 멤버별 읽음 — `chat_room_reads(room_id, user_id, last_read_message_id)` V021 + 조회 시 읽음 포인터 전진(역행 방지). 방 목록 unread 판정의 기반 | ✅ | [#118](https://github.com/rnqhstmd/wherewego/pull/118) |
| FR-GC1-3 | `MessageKind.REEL_LINK` — payload `{url, thumbnailKey:null}`. 전송 API 가 kind 분기(TEXT/REEL_LINK), URL 은 `https://`+인스타 패턴 검증, 2000자 가드 기존과 동일 | ✅ | [#118](https://github.com/rnqhstmd/wherewego/pull/118) |
| FR-GC1-4 | registered 파생 — 메시지 페이지 응답에서 REEL_LINK URL 배치 IN 쿼리로 `registered: Bool` 계산(`EXISTS pins WHERE group_id+instagram_url AND deleted_at IS NULL`). 상태 컬럼 금지 | ✅ | [#118](https://github.com/rnqhstmd/wherewego/pull/118) |
| FR-GC1-5 | 온디맨드 추출 API — `POST /api/v1/chat/groups/{groupId}/messages/{messageId}/extract`. `BotChatProcessor.extractHits` 파이프라인 재사용(메시지 append 없음), 동기 카드 목록 반환. deadline 10~15초로 완화(카카오 5초 SLA 비적용) | ✅ | [#118](https://github.com/rnqhstmd/wherewego/pull/118) |
| FR-GC1-6 | 추출/등록 권한 — REEL_LINK 발신자만(`sender_user_id == userId`, 위반 403). 발신자 탈퇴(NULL) 시 영구 등록전(MVP 정책) | ✅ | [#118](https://github.com/rnqhstmd/wherewego/pull/118) |
| FR-GC1-7 | 그룹 채팅방 목록 API — 활성 그룹별 방 요약(미생성 그룹은 가상항목, `BotRoomSummary` AC-7 선례) + 멤버별 unread + 마지막 메시지 preview(REEL_LINK → "릴스 링크" 등 kind 별 규칙) | ✅ | [#118](https://github.com/rnqhstmd/wherewego/pull/118), 버그픽스 [#121](https://github.com/rnqhstmd/wherewego/pull/121)(목록 최근 메시지 순 정렬) |
| FR-GC1-8 | 푸시 일반화 — `pushGroupMessage`(발신자 제외 전 활성 멤버, afterCommit best-effort, 1인 그룹 생략). TEXT/REEL_LINK 문구 분기 | ✅ | [#118](https://github.com/rnqhstmd/wherewego/pull/118) |
| BR-GC1-1 | 봇 흐름 무변경 — 기존 BOT 방·`/chat/bot/*`·카카오 웹훅 챗봇은 본 Phase에서 건드리지 않음(병행 운영, 제거는 GC-3) | ✅ | [#118](https://github.com/rnqhstmd/wherewego/pull/118) |

## GC-2 — iOS: 그룹 채팅 UI + 장소 등록 플로우

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-GC2-1 | DM 탭 → 그룹 채팅방 목록 — 그룹별 1방, 멤버별 unread(인스타식 읽음 표시 유지), `DMListView` 구조 재사용 | ✅ | [#119](https://github.com/rnqhstmd/wherewego/pull/119) |
| FR-GC2-2 | 멀티유저 채팅방 — TEXT 송수신, 발신자 구분(내/남 정렬 + 닉네임 표시), 기존 `ChatScrollContainer`/커서 페이징 재사용 | ✅ | [#119](https://github.com/rnqhstmd/wherewego/pull/119), 버그픽스 [#122](https://github.com/rnqhstmd/wherewego/pull/122)(loadMore 스크롤 튕김·커서 덮어쓰기) |
| FR-GC2-3 | REEL_LINK 버블 + 3상태 버튼 — ①내 메시지+미등록=「장소 등록하기」 활성 ②남 메시지+미등록=「장소 등록전이에요」 비활성 ③등록됨(전원)=「장소가 등록되었어요. 구경하실래요?」. 상태는 서버 `registered` 플래그만 신뢰 | ✅ | [#119](https://github.com/rnqhstmd/wherewego/pull/119) |
| FR-GC2-4 | 장소 등록 팝업 — 「장소 등록하기」→ "장소 추출 중…" 애니메이션(추출 API 호출) → `ReelSaveWizard`(위시 체크→메모) → 저장(`savePlaceCards` 재사용, 409 흡수) → 저장 안내 배너(시트 자동 닫힘). 취소=전체 취소(핀 0개, 상태 유지). 추출 0곳/좌표 없음은 안내 후 닫기(재시도 가능) | ✅ | [#119](https://github.com/rnqhstmd/wherewego/pull/119) |
| FR-GC2-5 | 딥링크 확장 — `.reelFocus(groupId:instagramUrl:)`. 「구경하실래요?」→ 지도 탭 + **해당 그룹 전환** + `focusReel` 필터/fitBounds | ✅ | [#119](https://github.com/rnqhstmd/wherewego/pull/119) |
| FR-GC2-6 | 수신 배선 — 전송 직후 제한 폴링(기존 2초×10 재사용) + APNs 포그라운드 `willPresent` 시 현재 방이면 배너 억제·재조회 트리거(신규, ChatPushSignal) + scenePhase 복귀 재조회 + 방 표시 중 8초 폴링 | ✅ | [#119](https://github.com/rnqhstmd/wherewego/pull/119), 버그픽스 [#122](https://github.com/rnqhstmd/wherewego/pull/122)(send-poll 조기 종료) |
| FR-GC2-7 | ShareExtension 전환 — 봇방 전송 → 그룹챗 REEL_LINK 전송(그룹 멀티선택 UI 유지, 엔드포인트만 교체) | ✅ | [#119](https://github.com/rnqhstmd/wherewego/pull/119) |
| FR-GC2-8 | 인앱 URL 감지 — 채팅 입력창에 인스타 URL **단독** 전송 시 REEL_LINK 로 전송(혼합 텍스트는 TEXT, `InstagramURL` 백엔드 정규식 동치) | ✅ | [#119](https://github.com/rnqhstmd/wherewego/pull/119) |
| FR-GC2-9 | (셸 정리) 봇 전제 문구 교체 — 빈 상태·입력 placeholder·헤더 부제를 그룹챗 맥락으로 | ✅ | [#119](https://github.com/rnqhstmd/wherewego/pull/119) |
| FR-GC2-10 | (셸 정리) 봇 흔적 제거 — 봇 아이콘·명칭·정체성 문구 제거(멤버 간 대화 맥락) | ✅ | [#119](https://github.com/rnqhstmd/wherewego/pull/119) |
| FR-GC2-11 | (셸 정리) 탭명 변경 — 하단 탭 라벨 + DMListView 헤더 "DM" → "채팅"(`.chat` 케이스/딥링크 유지) | ✅ | [#119](https://github.com/rnqhstmd/wherewego/pull/119) |

## GC-3 — 레거시 정리 + 썸네일

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-GC3-1 | 봇 티키타카 제거 — 레거시 BOT 방 soft delete 마이그레이션(V020 선례), deprecated `/chat/bot/*` 제거, `BotChatService`/`BotChatProcessor` 채팅 결합부 정리(추출 파이프라인은 FR-GC1-5 가 사용하므로 보존), iOS `BotChatView`/`PlaceCardsBubble` 제거. **카카오 웹훅 챗봇([[chatbot]]) 무변경** | ⬜ | |
| FR-GC3-2 | 릴스 썸네일 — 메시지 생성 시 og:image 비동기 스크래핑 → S3 캐시(`thumbnailKey` 기록, IG CDN URL 직참조 금지) → 버블에 썸네일 표시. feature flag 격리, 실패 시 링크 카드 폴백 | ✅ | [#120](https://github.com/rnqhstmd/wherewego/pull/120)(백엔드 파이프라인 + iOS 표시) |
| FR-GC3-3 | PLACE_CARDS/PROCESSING/MEMO_PROMPT kind 레거시 처리 — 과거 메시지 렌더 폴백(또는 봇방 soft delete 로 자연 소멸 확인) | ⬜ | 부분: 그룹방 렌더 폴백(EmptyView)은 [#119](https://github.com/rnqhstmd/wherewego/pull/119) 구현. soft delete 자연 소멸 확인은 FR-GC3-1 대기 |

## 확정 정책 (2026-06-10, GC-1 PRD Q&A 확정 — [PR #118](https://github.com/rnqhstmd/wherewego/pull/118))

- ✅ 발신자 탈퇴 시 등록 권한: **영구 등록전(MVP 확정)** — `sender_user_id` NULL 거부(403 `CHAT_EXTRACT_FORBIDDEN`). 사용량 보고 멤버 개방 여부는 후속 재평가
- ✅ 채팅 메시지 알림([[notification]]): **APNs 만, 알림함 미적재(확정)** — 핀 등록 알림(MANUAL_PIN)은 기존 경로 그대로 적재 유지
- ✅ 방 생성 시점: 그룹 생성 시 자동 생성 + V021 백필 + 전송 시 get-or-create 안전망
- ✅ 추출 deadline: 15초(`place.search.extract-deadline-ms`)
