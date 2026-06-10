# PRD: GC-2 — iOS 그룹 채팅 UI + 장소 등록 플로우

> Phase: GC-2 (iOS) · Base: develop · 선행: GC-1 백엔드(PR #118)
> SSOT: context/chat/status.md FR-GC2-1~8 / context/chat/architecture.md §GC-2 의존 계약

## 1. 배경 / 목적
GC-1(백엔드)에서 `chat_room`이 `GROUP` 방으로 일반화되고 `REEL_LINK` kind·멤버별 읽음·`registered` 파생·온디맨드 추출 API·`GROUP_MESSAGE` 푸시가 완비되었다. iOS는 아직 As-Is **봇 채팅**(`/chat/bot`·`/chat/couple`, `ChatFrame(senderType: USER/BOT/SYSTEM)`, 봇 자동 추출)을 소비한다.
GC-2는 iOS를 GC-1 계약으로 재배선하여 **봇 없는 멤버 간 그룹 채팅 + 발신자 주도 장소 등록**으로 전환한다. 봇방은 사라지고, 봇이 하던 "릴스→장소 추출/저장" 기능은 **그룹채팅 안의 팝업**으로 편입된다. 이 시점부터 사용자는 그룹챗만 본다(봇 코드 물리 삭제는 GC-3).

## 2. 사용자 시나리오
1. 그룹원 A가 인스타 릴스를 그룹 채팅방에 공유(ShareExtension 또는 입력창 붙여넣기) → REEL_LINK 메시지 버블.
2. 멤버들은 그 방에서 TEXT로 자유 대화(발신자 닉네임·내/남 정렬).
3. 발신자 A는 자기 REEL_LINK 버블의 「장소 등록하기」 → "추출 중…" → 위시 체크/메모 → 저장.
4. 저장 후 전원의 버블이 「장소가 등록되었어요. 구경하실래요?」로 바뀌고, 탭하면 지도 탭으로 이동 + 해당 그룹 전환 + 그 릴스 핀만 필터.

## 3. 요구사항 (Must — FR-GC2-1~8)
| ID | 요구사항 |
|----|----------|
| FR-GC2-1 | **그룹 채팅 목록** — DM 탭을 그룹별 그룹 채팅방 목록으로. 그룹당 1방, 멤버별 unread(인스타식 읽음 점), `DMListView`/`DMListViewModel` 구조 재사용. 목록 API를 `GroupRoomSummary` 계약으로 교체 |
| FR-GC2-2 | **멀티유저 채팅방** — 신규 `GroupChatView`/`GroupChatViewModel`. TEXT 송수신, 발신자 구분(내 우측/타인 좌측 + 닉네임), `ChatScrollContainer`/커서 페이징/입력바 재사용. 봇/PLACE_CARDS/PROCESSING 분기 제거 |
| FR-GC2-3 | **REEL_LINK 버블 + 3상태 버튼** — ①내 메시지+미등록=「장소 등록하기」(활성) ②타인+미등록=「장소 등록전이에요」(비활성) ③등록됨(전원)=「장소가 등록되었어요. 구경하실래요?」. 상태는 서버 `registered` 플래그만 신뢰(클라 추정 금지) |
| FR-GC2-4 | **장소 등록 팝업** — 「장소 등록하기」→ "장소 추출 중…" 애니메이션(extract API, 15s) → `ReelSaveWizard`(위시 체크→메모) → `savePlaceCards` 재사용(409 흡수) → "저장됐어요! 채팅으로 돌아가기". 취소=전체 취소(핀 0개·상태 불변). 추출 0곳/좌표 없음 = 안내 후 닫기(재시도 가능) |
| FR-GC2-5 | **딥링크 확장** — `.reelFocus(url)` → `.reelFocus(groupId:instagramUrl:)`. 「구경하실래요?」→ 지도 탭 + **해당 그룹 전환**(`GroupContext.enterGroup`) + `MapViewModel.focusReel` 필터/fitBounds |
| FR-GC2-6 | **수신 배선** — 전송 직후 제한 폴링(2초×10 재사용) + APNs 포그라운드 `willPresent` 시 현재 방이면 재조회 트리거(신규) + scenePhase 복귀 재조회 + **방 표시 중 8초 주기 폴링** |
| FR-GC2-7 | **ShareExtension 전환** — 봇방 전송 → 그룹챗 REEL_LINK 전송(그룹 멀티선택 UI 유지, 엔드포인트만 교체) |
| FR-GC2-8 | **인앱 URL 감지** — 채팅 입력창에 **메시지 전체(trim)가 인스타 URL 1개**일 때만 REEL_LINK로 전송, 그 외는 TEXT(ShareExtension 없이 동일 흐름) |

## 4. 셸 정리 (Should — 전환에 수반)
| ID | 요구사항 |
|----|----------|
| FR-GC2-9 | **봇 전제 문구 교체** — 빈 상태·입력 placeholder·헤더 부제를 그룹챗 맥락으로(예: "관심 있는 릴스 링크를 붙여넣어 보세요" → 그룹 대화 명안) |
| FR-GC2-10 | **봇 흔적 제거** — 봇 아이콘/🤖·"봇" 명칭·봇 정체성 문구 제거(멤버 간 대화 맥락) |
| FR-GC2-11 | **DM 탭명 변경** — 하단 탭 라벨 "DM" + DMListView 헤더 "DM" → **"채팅"**으로(`.chat` 케이스/딥링크는 유지, 표시 라벨만) |

## 5. 확정 정책 (GC-1 환류)
- 등록 권한 = REEL_LINK 발신자만(서버 강제, `sender_user_id == userId`). 발신자 탈퇴 시 영구 등록전.
- 채팅 알림 = APNs만, 알림함 미적재.
- `registered` = 서버 파생(재조회로 자가치유). 클라 상태 컬럼 없음.
- 의존 계약(architecture §GC-2): 푸시 `GROUP_MESSAGE`(roomId) / `GroupChatMessageFrame(messageId, senderUserId, senderNickname, kind, payload, registered, createdAt)` / extract 응답 = `PlaceCardsPayload` 모양(`ReelSaveWizard` 디코더 호환).

## 6. 수용 기준 (AC)
- AC-1: 봇방/봇 메시지(BOT senderType, PLACE_CARDS/PROCESSING/MEMO_PROMPT)가 그룹챗 UI에 노출되지 않는다.
- AC-2: 같은 릴스를 재공유하면 2번째 버블이 자동 「등록됨」으로 표시된다(서버 정합).
- AC-3: 타인의 REEL_LINK 버블에서는 「장소 등록하기」가 비활성이고 등록 시도가 불가하다(서버 403 방어).
- AC-4: 저장 완료 후 방 재조회로 발신자·타인 모두의 버블이 ③상태로 갱신된다.
- AC-5: 「구경하실래요?」 탭 시 다른 그룹의 릴스여도 그 그룹으로 전환된 지도에서 핀이 필터된다.
- AC-6: 빈 방(메시지 0)·그룹 0개·추출 실패·네트워크 오류 각각 안내 상태가 정의된다.
- AC-7: 발신자 구분 — 내 메시지/타인 메시지가 정렬·닉네임으로 구분된다(senderUserId == 현재 사용자 판정).
- AC-8: 입력창에 인스타 URL 단독 전송 시 REEL_LINK, 일반 텍스트(또는 URL+텍스트 혼합)는 TEXT로 전송된다.

## 7. 범위 외 (Out of scope)
- 봇 코드(`BotChatView`/`BotChatViewModel`/PLACE_CARDS 버블) 물리 삭제, 봇방 soft delete → **GC-3**
- 릴스 썸네일(og:image→S3) → **GC-3**
- WebSocket/STOMP 재도입(폴링+APNs 유지)
- 대규모 리브랜딩·봇 "우리" 정체성·지도 필터 패널 등 목업 백로그(별도 PR)

## 8. 제약
- iOS 빌드/단위테스트 = Windows 로컬 불가 → GitHub Actions CI 검증. 최종 시각/실기기 = Mac(DoD-B).
- 결정 기록: 전환=신규 GroupChatView 신설(BotChatView dead code, GC-3 제거) / URL 감지=단독 URL만 / 수신=재조회+8초 폴링 / 셸 정리=탭명+문구+봇흔적 포함.
