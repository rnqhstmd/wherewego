# chat 아키텍처 (To-Be — 그룹 채팅 전환 설계)

> 봇 티키타카(모아보기)를 그룹 채팅방으로 전환하는 설계. 2026-06-10 분석 확정, **GC-1 백엔드 구현 완료([PR #118](https://github.com/rnqhstmd/wherewego/pull/118))**.
> As-Is(봇 채팅) 구조는 [[chatbot]] status.md "봇 채팅 STOMP→이벤트 전환" 항목 참조.

## 시스템 구조 (목표)

```
[인스타그램 앱] ──공유──▶ [ShareExtension] ──POST REEL_LINK──▶ [그룹 채팅방(chat_room type=GROUP)]
                                                                      │
[그룹원들] ◀──TEXT 단체 채팅──▶ (멤버 간, 봇 없음)                     │
                                                                      ▼
                                            REEL_LINK 메시지 버블 + 하단 버튼(3상태)
                                                                      │
        발신자: 「장소 등록하기」 ──▶ 온디맨드 추출 API ──▶ 팝업(추출중… → 위시 체크 → 메모)
                                          │                            │
                                          │     체크=WISH / 미체크=REEL(발견) 핀 저장
                                          ▼                            ▼
                              (스크래핑→Gemini→Kakao Local)   pins(instagram_url 기록)
                                                                      │
        전원: 「장소가 등록되었어요. 구경하실래요?」 ◀── registered 파생 ──┘
                      │
                      ▼
        .reelFocus(groupId, instagramUrl) 딥링크 → 그룹 전환 + 해당 릴스 핀만 필터(fitBounds)
```

## 핵심 설계 결정

1. **registered 는 파생 상태(컬럼 없음)** — `registered = EXISTS(pins WHERE group_id = ? AND instagram_url = ? AND deleted_at IS NULL)`. 메시지 페이지 응답에서 페이지 내 REEL_LINK URL 들을 모아 IN 쿼리 1방으로 배치 계산해 프레임에 `registered: Bool` 로 내린다. 상태 전파 문제가 설계 차원에서 사라지고(어떤 재조회로도 자기치유), 같은 릴스 재공유 시 두 번째 메시지가 자동 "등록됨"(`UNIQUE(group_id, instagram_url)` 정합). 트레이드오프: 그 릴스 핀 전부 삭제 시 "등록전" 회귀(재등록 가능 — 자연스러운 동작으로 수용).
2. **WebSocket 재도입 안 함 — DB+이벤트 유지.** STOMP 는 PR [#94](https://github.com/rnqhstmd/wherewego/pull/94)에서 장애(재연결 배너)로 제거된 이력이 있고, [[notification]] 옵션 B(운영 단순성 우선) 정책·단일 인스턴스 인프라와 정합. 수신 경로 4종: ① 전송 직후 제한 폴링(2초×10, 기존 패턴 — 타인의 새 메시지를 수신하면 조기 종료하고 ④에 위임, PR [#122](https://github.com/rnqhstmd/wherewego/pull/122)) ② APNs — 백그라운드 알림 + **포그라운드 `willPresent` 에서 현재 방이면 배너 대신 재조회 트리거(신규 배선)** ③ scenePhase 복귀 재조회 ④ (선택) 채팅방 표시 중 5~10초 폴링. **커서 소유권**: 폴링(reconcile)은 메시지 병합만 수행하고 페이지네이션 커서(nextCursor/hasMore)는 load/loadMore 만 갱신한다 — reconcile 이 커서를 덮어쓰면 과거 페이지 탐색이 깨진다(PR #122 회귀 교훈). WS 재검토 트리거: 동시 활성 사용자 급증, 타이핑 인디케이터/프레즌스 요구.
3. **추출은 온디맨드 + deadline 완화.** 기존 `deadlineMs=4500` 은 카카오 웹훅 5초 SLA 제약([[place]])이었다. 팝업에는 "추출 중" 애니메이션이 있으므로 10~15초로 완화 → 추출 성공률·후보 수 개선, Google 폴백 동기 처리 가능.
4. **등록 권한 = REEL_LINK 발신자만.** 서버에서 `chat_message.sender_user_id == userId` 강제(클라이언트 비활성화만으론 부족). 발신자 탈퇴(sender_user_id NULL) 시 영구 "등록전" — MVP 수용(필요 시 후속에서 멤버 개방).
5. **썸네일은 별도 단계(GC-3).** 인스타 공유는 URL만 옴. og:image 스크래핑 + **S3 캐시 필수**(IG CDN 서명 URL 만료). 메시지 생성 시 비동기 best-effort, feature flag(`place.instagram.scraping-enabled` 선례)로 격리. MVP 는 링크 카드(도메인+｢Instagram 릴스｣ 라벨).
6. **봇 티키타카 전면 폐기(GC-3).** 인앱 BOT 방·PROCESSING/PLACE_CARDS/MEMO_PROMPT 흐름·deprecated `/chat/bot/*` 제거. 레거시 봇방은 V020 선례대로 soft delete(베타 규모 이력 손실 수용). **카카오 웹훅 챗봇([[chatbot]])은 무변경.** 기존 목업 백로그(봇 "우리" 정체성·봇 응답 문구 폴리시)는 본 설계가 대체·폐기.

## 재사용 부품 매핑 (As-Is → To-Be)

| To-Be 요소 | 재사용 As-Is | 변경 |
|---|---|---|
| 그룹 채팅 백엔드 | `CoupleChatService`(이미 N명 멤버 푸시 작동, `broadcastToOthersAfterCommit`) | type GROUP 일반화 + kind 분기 |
| 채팅 테이블 | `chat_room`/`chat_message(kind, payload_json JSONB)` (V015) | REEL_LINK kind·읽음 테이블 추가 |
| 인스타 공유 진입 | `ios/ShareExtension`(그룹 멀티선택→봇방 전송) | 전송 대상만 그룹챗 REEL_LINK 로 교체 |
| 추출 파이프라인 | `BotChatProcessor.extractHits`(스크래핑→Gemini→Kakao, stateless) | 메시지 append 없는 동기 API 로 노출 |
| 위시 선택 팝업 | `ReelSaveWizard`(체크=WISH/미체크=REEL/공통 메모) + `savePlaceCards`(409 흡수) | 앞에 "추출 중" 스텝 추가 |
| 릴스 핀 필터 | `.reelFocus` 딥링크 → `MapViewModel.focusReel`(instagram_url 필터+fitBounds) | **groupId 추가(그룹 전환)** |
| 핀↔릴스 연관 | `pins.instagram_url` + `UNIQUE(group_id, instagram_url)` | 무변경 — registered 파생의 기반 |
| 푸시 | `pushCoupleMessage`(APNs, 발신자 제외 전 멤버) | 명칭/문구 일반화 + REEL_LINK 분기 |

## 데이터 모델 변경 (V021 — GC-1 확정 반영)

- `chat_room.type` **COUPLE→GROUP 전면 리네임 확정**(V021 UPDATE + `uq_chat_room_group_group` 부분 UNIQUE 재정의). COUPLE 방은 구조적으로 이미 그룹 공용 방(groupId만 보유)이었고 클라 미사용이라 리네임으로 일반화. couple 엔드포인트(`/chat/couple/*`)·`CoupleMessageRequest`·`coupleMessage` 푸시는 dead surface 라 **즉시 제거**(GC-3 아닌 GC-1에서)
- 방 생성: **그룹 생성 시 자동 생성 + 기존 활성 그룹 V021 백필** + 전송 시 get-or-create 안전망(idempotent)
- `chat_room_reads (room_id FK, user_id, last_read_message_id, UNIQUE(room_id, user_id))` — 멤버별 읽음(조회 시 전진·역행 방지). 기존 `chat_room.last_read_message_id`(V020)는 봇방 owner 전용 단일 포인터라 그룹방에 부족
- `MessageKind.REEL_LINK` — payload `{"url": "...", "thumbnailKey": null}`. URL 은 `https://` + 인스타 패턴 + **2000자 상한**(감사 반영) 검증
- 상태 컬럼 없음 — registered 는 파생(§핵심 설계 결정 1, 페이지당 IN 쿼리 1회 배치)
- 추출 deadline 확정: `place.search.extract-deadline-ms=15000`(신설 노브 — 기존 `sync-deadline-ms=4500` 무변경). 추출 파이프라인은 `ReelPlaceExtractor`(domain/place) 로 분리 — `BotChatProcessor` 는 위임(동작 무변경), GC-3 봇 제거 후에도 보존되는 경로
- GC-2 의존 계약: 푸시 type `GROUP_MESSAGE`(roomId 포함) / `GroupChatMessageFrame(messageId, senderUserId, senderNickname, kind, payload, registered, createdAt)` / extract 응답 = `PlaceCardsPayload` 모양(`ReelSaveWizard` 디코더 호환)

## Phase 분할 (구현 단위 — gx-dev 파이프라인 1회 = 1 Phase)

| Phase | 범위 | 대상 | 비고 |
|-------|------|------|------|
| **GC-1** | **백엔드: 그룹 채팅 + 릴스 등록 기반** — GROUP 방 일반화, REEL_LINK kind, 멤버별 읽음, registered 파생, 온디맨드 추출 API, 방 목록 API, 푸시 일반화, V021 | backend | 봇 흐름 무변경(병행 운영) — 블라스트 반경 최소화 |
| **GC-2** | **iOS: 그룹 채팅 UI + 장소 등록 플로우** — DM탭→그룹 채팅 목록, 멀티유저 채팅방, REEL_LINK 버블+3상태 버튼, 추출 팝업(위저드 확장), 딥링크 groupId 확장, ShareExtension 전환, 수신 배선 | ios | GC-1 머지 후. 이 시점부터 사용자는 그룹챗만 봄 |
| **GC-3** | **레거시 정리 + 썸네일** — 봇 티키타카 제거(봇방 soft delete·deprecated API·iOS Bot 화면), og:image→S3 썸네일(feature flag) | backend+ios | GC-2 안정화 후. 썸네일은 독립 PR 분리 가능 |

요구사항(FR) 상세는 [status.md](status.md).

## 관련 도메인

- 입력: [[place]] 추출 파이프라인 재사용, [[group]] 멤버십 검증, [[pin]] 저장·instagram_url 연관
- 출력: [[map]] 릴스 핀 필터(.reelFocus), [[notification]] 핀 등록 알림(기존 MANUAL_PIN 경로 그대로)
- 대체: [[chatbot]] 인앱 봇 채팅(폐기 예정 — 카카오 웹훅은 무변경)
