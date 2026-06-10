# 설계: GC-2 — iOS 그룹 채팅 UI + 장소 등록 플로우

> Base: develop · 선행 계약: GC-1 백엔드(PR #118) · 규모: **대형**
> 원칙: 기존 채팅 패턴(이벤트 전환·폴링·DM 목록·ReelSaveWizard·focusReel 딥링크)을 최대 재사용. 봇 코드는 미연결 dead code로 GC-3 제거.

## 0. 백엔드 의존 계약 (코드 확인 완료)

| 메서드 | 경로 | 요청 | 응답 |
|--------|------|------|------|
| 그룹 방 목록 | `GET /api/v1/chat/groups` | — | `[GroupRoomSummaryResponse]` |
| 그룹 메시지 조회 | `GET /api/v1/chat/groups/{groupId}/messages?cursor=&limit=` | — | `GroupMessagesResponse{groupId, messages:[GroupChatMessageFrame], hasMore, nextCursor}` |
| 그룹 메시지 전송 | `POST /api/v1/chat/groups/{groupId}/messages` | `{kind, text?, url?}` | `SendMessageResponse{messageId, kind}` |
| 릴스 추출 | `POST /api/v1/chat/groups/{groupId}/messages/{messageId}/extract` | — | `PlaceCardsPayload{cards, sourceInstagramUrl}` |

- `GroupChatMessageFrame{messageId, roomId, senderUserId?, senderNickname?, kind, payload, registered?, createdAt}` — payload: TEXT=`{text}`, REEL_LINK=`{url, thumbnailKey:null}`. registered는 REEL_LINK만 Bool, 그 외 null.
- `GroupRoomSummaryResponse{roomId?, groupId, groupName, lastPreview?, lastSenderUserId?, hasUnread, lastAt?}` (봇 `BotRoomSummary` 대비 `lastSenderType→lastSenderUserId`, `unread→hasUnread`).
- 전송 검증/권한: TEXT 1~2000자/REEL_LINK https+인스타 패턴(400 `CHAT_*_INVALID`), 비멤버 403 `GROUP_NOT_MEMBER`, 추출은 발신자만(403 `CHAT_EXTRACT_FORBIDDEN`), 추출 0곳=200+빈 cards, 스크래핑 실패=502 `PLC_*`.
- 푸시 `PushPayload{type:"GROUP_MESSAGE", roomId, title, body}` — **roomId 포함**(현재 방 매칭용). (정확한 직렬화 키는 `ApnsPushSender` 확인 — `type`/`roomId` 추정.)

## 1. 변경 범위

### 신규 파일
| 파일 | 역할 |
|------|------|
| `Features/Chat/Group/GroupChatModels.swift` | `GroupChatFrame`(senderUserId/senderNickname/registered/REEL_LINK url 커스텀 디코딩), `GroupRoomSummary`, `GroupMessagesResponse` |
| `Features/Chat/Group/GroupChatViewModel.swift` | 멤버 채팅 VM — 로드/페이징/전송(TEXT·REEL_LINK 분기)/수신 폴링(전송직후+8초+재조회)/추출·저장/딥링크 |
| `Features/Chat/Group/GroupChatView.swift` | 채팅방 화면 — ChatScrollContainer 재사용 + 발신자 구분 + REEL_LINK 버블 + 입력바(URL 감지) + 추출 팝업 호스팅 |
| `Features/Chat/Group/GroupMessageRow.swift` | 메시지 행 — TEXT(내/남 정렬+닉네임), REEL_LINK 3상태 버블 |
| `Features/Chat/Group/ReelRegisterSheet.swift` | 추출 팝업 컨테이너 — "추출 중"→`ReelSaveWizard`(재사용)→결과/안내/에러 |
| `Core/Push/ChatPushSignal.swift` | 포그라운드 GROUP_MESSAGE 신호(현재 방 roomId 등록 + 수신 tick). willPresent↔GroupChatViewModel 약결합 |

### 수정 파일
| 파일 | 변경 |
|------|------|
| `Features/Chat/ChatAPI.swift` | `groupRooms()`, `groupMessages(groupId:cursor:limit:)`, `sendGroupMessage(groupId:kind:text:url:)`, `extractGroupReelPlaces(groupId:messageId:)` 추가(봇 메서드는 dead 잔존) |
| `Features/Chat/ChatMessageModels.swift` | `MessageKind`에 `REEL_LINK` 추가(그룹 모델은 신규 파일) |
| `Features/Chat/DMListView.swift` | `GroupRoomSummary` 소비 + 행 탭 → `GroupChatRoomView`(GroupChatViewModel 팩토리), 헤더 "DM"→"채팅", 빈 상태/미리보기 문구 그룹챗화, `lastSenderUserId==currentUser.id` 판정 |
| `Features/Chat/DMListViewModel.swift` | `groupRooms()` 소비 + `[GroupRoomSummary]`, `CurrentUser` 주입(내 메시지 미리보기 판정) |
| `App/MainTabView.swift` | 탭 라벨 "채팅", `makeRoomViewModel`→GroupChatViewModel, `.reelFocus(groupId:url:)` 딥링크 소비(그룹 전환+focusReel), ChatPushSignal 주입 |
| `App/DeepLinkRouter.swift` | `.reelFocus(instagramUrl:)`→`.reelFocus(groupId:instagramUrl:)`, GROUP_MESSAGE 푸시 type→`.chat` |
| `Features/Map/MapViewModel.swift` | `focusReel(groupId:instagramUrl:)` — groupId 전환 보장 후 필터/fitBounds |
| `App/AppNotificationDelegate.swift` | willPresent에서 GROUP_MESSAGE roomId 추출 → 현재 방이면 배너 억제+재조회 신호, 아니면 배너 |
| `App/AppDependencies.swift` | `ChatPushSignal` 조립 + notificationDelegate/MainTabView 주입 |
| `ShareExtension/Logic/ShareAPIClient.swift` | `botRooms()`→`groupRooms()`(`/chat/groups`), `sendBotMessage`→`sendReelLink(groupId:url:)`(`/chat/groups/{id}/messages` `{kind:REEL_LINK,url}`) |
| `ShareExtension/Logic/ShareViewModel.swift` | 전송을 REEL_LINK로(메시지/문구 정합), `ShareGroup` 디코딩을 GroupRoomSummary 형태로 |
| `ShareExtension/Logic/ShareDTO.swift` | `ShareGroup` 필드 정합(roomId?/groupId/groupName) |

### dead code (GC-3 제거 — 본 PR 미연결)
`Features/Chat/Bot/BotChatView.swift`, `Bot/BotChatViewModel.swift`, `PlaceCardsBubble.swift`, `ChatMessageRow.swift`의 봇 kind 분기, ChatAPI 봇 메서드. — DMListView가 GroupChatRoomView를 쓰면 자연 미참조.

## 2. 핵심 설계 결정

1. **그룹 프레임은 별도 모델(`GroupChatFrame`)** — 봇 `ChatFrame`을 건드리지 않음(백엔드 `GroupChatMessageFrame` 분리 정합, dead code 격리). REEL_LINK payload `{url}`·`registered`·`senderUserId/senderNickname` 커스텀 디코딩.
2. **내/남 판정 = `senderUserId == currentUser.id`** — `CurrentUser`를 GroupChatViewModel/DMListViewModel에 주입. senderUserId nil(탈퇴)=타인+닉네임 "(알 수 없음)".
3. **registered는 서버 진실만 신뢰 + 재조회로 갱신** — 메시지 불변이 아니라 **registered가 변하므로**, 수신 재조회는 단순 append가 아니라 **최신 페이지로 동일 messageId 프레임을 교체-병합**(registered false→true 반영). 저장 완료 직후 즉시 reconcile로 ③상태 전환(AC-4).
4. **REEL_LINK 3상태(`GroupMessageRow`)**:
   - `registered==true` → ③「장소가 등록되었어요. 구경하실래요?」(전원, 탭→`.reelFocus(groupId,url)`)
   - `registered!=true && isOutgoing` → ①「장소 등록하기」(활성, 탭→추출 팝업)
   - `registered!=true && !isOutgoing` → ②「장소 등록전이에요」(비활성)
5. **추출 팝업(`ReelRegisterSheet`)은 ReelSaveWizard를 감싸는 컨테이너** — ReelSaveWizard 무변경 재사용. 상태: `extracting`("장소 추출 중…" 애니메이션) → 성공 `wizard(cards)` → 저장 `savePlaceCards`(BotChatViewModel 로직 이식, 409 흡수) → `done`("저장됐어요! 채팅으로 돌아가기") → 닫기 시 방 reconcile. 추출 0곳/좌표0 → 안내 닫기(재시도). 502 → 에러+재시도. extracting 중 취소 = 핀 0·상태 불변.
6. **인앱 URL 감지(FR-GC2-8)** — 전송 시 `draft.trim`이 인스타 URL 1개면 `sendGroupMessage(kind:.REEL_LINK, url:)`, 아니면 `kind:.TEXT, text:`. 판정 헬퍼 `InstagramURL.isReelURL(_:)`(백엔드 `https`+인스타 릴스 패턴 동치, 순수·테스트 대상). ShareExtension 전송도 동일 REEL_LINK.
7. **수신 배선(FR-GC2-6)** — GroupChatViewModel:
   - 전송 직후 제한 폴링(2초×10, 기존 패턴 재사용 — 빠른 왕복 대화 보완)
   - **방 표시 중 8초 주기 폴링**(appear 시작·disappear 취소)
   - scenePhase `.active` 재조회(GroupChatView)
   - **포그라운드 willPresent**: GROUP_MESSAGE & roomId==현재 방 → 배너 억제 + `ChatPushSignal` tick → GroupChatViewModel reconcile. 현재 방 아니면 배너 표시(기존). `ChatPushSignal.currentRoomId`를 appear/disappear가 등록/해제.
   - 모든 폴링/재조회는 §결정3의 교체-병합 reconcile 단일 경로.
8. **딥링크 그룹 전환 시퀀싱(FR-GC2-5)** — `MapViewModel.focusReel(groupId:instagramUrl:)` 단일 진입점: `focusedInstagramUrl` 설정 → `self.groupId != groupId`면 그 그룹 핀 로드(`switchTo` 재사용, 재진입 가드 보유) else `reloadPinsAppendOnly`(방금 저장 핀) → URL 필터 핀 fitBounds. MainTabView는 `selection=.map` + `groupContext.enterGroup(groupId)`(UI 레벨1/배너 정합, onGroupChanged 미발화) + `focusReel(groupId:url:)` 호출. (레벨0→레벨1 마운트 load와 focusReel의 그룹 로드가 1회 중복될 수 있으나 idempotent — 카메라는 focusReel.fitBounds가 최종. 드문 경로라 수용.)
9. **셸 정리(FR-GC2-9~11)** — 탭 라벨/헤더 "채팅"(`.chat` 케이스·딥링크 유지), 빈 상태("아직 대화가 없어요, 첫 메시지를 보내보세요")·입력 placeholder("메시지를 입력하거나 릴스 링크를 붙여넣어 보세요")·봇 아이콘/명칭 제거. `.reelFocus`/`reelFocusBanner`는 그룹 맥락 유지.

## 3. 컴포넌트 설계 요지

### GroupChatViewModel (멤버 채팅, BotChatViewModel 구조 차용·봇 결과 교체 제거)
- 의존: groupId, roomId?(진입 시 `GroupRoomSummary.roomId` 전달 — 가상항목/빈 방은 nil, 첫 프레임에서 보강 확보), chatAPI, pinAPI, currentUser, deepLinkRouter, chatPushSignal, sleeper
- 게시: `messages:[GroupChatFrame]`, `draft`, `registerState`(추출 팝업: idle/extracting/wizard(cards,messageId,url)/done(result)/empty/error), `saveInfoMessage`
- 로드/페이징: `groupMessages` cursor, id DESC→오름차순. reconcile=최신 페이지로 교체-병합(신규 append + 기존 messageId 프레임 교체).
- 전송: trim→URL 판정→REEL_LINK/TEXT. 낙관 append(내 프레임) 후 응답 messageId 확정. 실패 시 입력 복원.
- 폴링: 전송직후 제한 + 8초 주기. roomId는 첫 로드 응답/프레임에서 확보→chatPushSignal.currentRoomId 등록.
- 추출: `register(messageId:url:)`→extracting→extractGroupReelPlaces→cards 분기. 저장=savePlaceCards(groupId, 체크=WISH/미체크=REEL, 메모, instagramUrl=sourceInstagramUrl, 409 흡수)→done→reconcile.
- 딥링크: `openReel(url:)`→`deepLinkRouter.pending = .reelFocus(groupId:self.groupId, instagramUrl:url)`.

### GroupMessageRow
- isOutgoing=`frame.senderUserId == currentUserId`. TEXT: 우/좌 정렬 + 타인은 닉네임 라벨(senderNickname ?? "(알 수 없음)"). REEL_LINK: 링크 카드(도메인+「Instagram 릴스」, 썸네일 GC-3) + 3상태 버튼(§결정4). 콜백 `onRegister(messageId,url)`, `onOpenReel(url)`.

### DMListView/DMListViewModel
- `groupRooms()`→`[GroupRoomSummary]`. 미리보기 "나: …"는 `lastSenderUserId==currentUser.id`. 행 탭→`GroupChatRoomView`(GroupChatViewModel 팩토리, .id(groupId)). 헤더/빈 상태 "채팅" 문구.

### ChatPushSignal (신규, ObservableObject @MainActor)
- `currentRoomId: Int?`(열린 방), `tick: Int`(수신 신호). `register(roomId:)`/`clear(roomId:)`/`notify(roomId:)`. willPresent가 roomId로 notify, GroupChatViewModel이 tick 관찰→reconcile.

## 4. 구현 순서
1. **모델/판정 기반** — MessageKind.REEL_LINK, GroupChatModels(프레임/요약/응답), InstagramURL.isReelURL. (+단위테스트: 디코딩·URL 판정)
2. **ChatAPI 그룹 메서드** — groupRooms/groupMessages/sendGroupMessage/extractGroupReelPlaces.
3. **GroupChatViewModel** — 로드/페이징/전송/reconcile 교체병합/폴링/추출·저장/딥링크. (+테스트: 발신자 구분·registered 갱신·URL 분기·409 흡수)
4. **GroupChatView/GroupMessageRow/ReelRegisterSheet** — 채팅방 UI + 3상태 버블 + 추출 팝업.
5. **DM 목록 전환** — DMListView/DMListViewModel GroupRoomSummary + CurrentUser + 문구.
6. **딥링크·지도** — DeepLinkRouter.reelFocus(groupId,url), MapViewModel.focusReel(groupId,url), MainTabView 소비+탭명+팩토리.
7. **수신 푸시 배선** — ChatPushSignal, AppNotificationDelegate willPresent, AppDependencies 조립.
8. **ShareExtension 전환** — ShareAPIClient/ShareViewModel/ShareDTO REEL_LINK.
9. **dead code 미연결 확인** + 셸 문구/빈상태 마감.

## 5. 테스트 계획 (CI=GitHub Actions, Windows 로컬 빌드 불가)
- 단위: GroupChatFrame 디코딩(TEXT/REEL_LINK/registered/탈퇴 발신자), InstagramURL.isReelURL, GroupChatViewModel(발신자 구분·reconcile registered 갱신·URL 분기 전송·savePlaceCards 409·추출 상태머신), DMListViewModel(groupRooms·내 미리보기), DeepLinkRouter.reelFocus(groupId) 매핑, MapViewModel.focusReel 그룹 전환.
- 기존 봇 테스트(BotChatViewModelTests 등)는 dead 대상이나 본 PR 유지(GC-3 정리). 신규 테스트는 Group* 네이밍.

## 6. 변경 범위 요약
- 신규 6 + 수정 12 (+테스트 신규). 구현 9단계.
- 위험: ①reconcile 교체-병합(registered 갱신) 정확성 ②딥링크 그룹 전환 race ③willPresent 배너 억제/신호 동시성(Swift6) — 모두 §2에서 단일 경로/가드로 통제.
