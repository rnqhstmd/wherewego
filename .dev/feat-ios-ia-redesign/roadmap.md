# iOS IA 재설계 — 이어개발 로드맵 (feat/ios-ia-redesign)

> 새 세션(clear 후) 이어개발용 마스터 문서. 이 파일 + 같은 폴더의 design.md/prd.md/codemap.md/state.md를 함께 읽을 것.

## 현재 상태 (2026-06-08)
- 브랜치 **`feat/ios-ia-redesign`** (base develop `b6509bb`)
- **A 내비 골격 ✅ 커밋 `9b70fa1`, PR #106** — Mac DoD-B 미검증(집에서 빌드/테스트 예정)
- 묶음 전략: **같은 브랜치에 C/DM/D/IC-2 누적**, 단계별 커밋 + (점검 시) PR + Mac. 머지는 리뷰어.

## develop 기반 자산 (전부 머지됨)
- 백엔드: `GET /groups`(GM-1, iOS `GroupAPI.listMyGroups` 소비 중) / `GET /chat/bot/rooms` + `POST·GET /chat/bot/{groupId}/messages`(GM-2 B #105, **iOS 미소비 — DM 단계서**) / 초대 IC-1(#101 V019 재사용 코드)
- iOS(골격 완료분): `GroupContext`(그룹목록·currentGroupId·lastGroupId persist), `GroupListView`, 4탭 `MainTabView`, 지도 2레벨, `MapView` 상단 오버레이+어디가지 FAB, `BotChatViewModel`(릴스 위저드 #104 — savePlaceCards 위시/발견+메모+instagramUrl)

## 남은 단계 (각각 /gx-dev NORMAL 권장)

### C — 맵/필터 정리 (가장 작음, 골격 직후 권장)
- **필터/범례 좌하단 → 상단 이동**: 현 `MapView`의 `TagFilterButton`/`TagLegendButton`(좌하단). 어디가지 FAB(좌하단, 골격서 추가)와 자리 충돌 해소 위해 필터는 상단으로.
- **맵 로딩 "로딩 척"**: 앱 시작 1회만 Mapbox 로딩. 그룹 전환/목록→선택 시 전체 재로딩 X → 줌아웃→내 위치 줌인 연출 + 핀만 그룹별 교체.
- 파일: `Features/Map/MapView.swift`, `Features/Map/MapViewModel.swift`(`switchTo`가 이미 줌아웃→핀 교체 비슷 — 내 위치 줌인 + 1회 로딩 보장 보강), `Features/Map/TagFilterBar.swift`.

### DM — 그룹별 봇방 목록 (#105 소비, 핵심)
- DM 탭을 단일 `BotChatView` → **그룹별 봇방 목록**(신규 `DMListView`).
- 목록: `GET /chat/bot/rooms` → `BotRoomSummary[]`(roomId·groupId·groupName·lastPreview·lastSenderType·unread·lastAt). **인스타식 읽음**(unread면 굵게).
- 방 진입: 그룹별 봇 채팅. `ChatAPI`를 `POST·GET /chat/bot/{groupId}/messages`로 (현 deprecated `/chat/bot/messages` 대체·제거). `BotChatViewModel`에 groupId 주입.
- **릴스 저장 그룹 = 그 방 groupId** (savePlaceCards가 그 groupId로 — #104 위저드와 통합).
- 파일: 신규 `DMListView`/`DMListViewModel`, `Features/Chat/ChatAPI.swift`(botRooms() + 그룹별 botMessages/sendBotMessage), `Features/Chat/ChatMessageModels.swift`(BotRoomSummary), `BotChatViewModel`(groupId), `MainTabView`(DM 탭 = 목록).
- 주의: deprecated `/chat/bot/messages`는 이 단계서 제거. 백엔드는 이미 그룹별 API 제공(#105).

### D — 알림 상세 / 내정보 축소 / 그룹관리 ⋯
- **알림 상세**: "어느 그룹에 / 누가 / 어떤 핀" 상세. ⚠️ **백엔드 알림 응답에 그룹명·작성자 닉네임 있는지 확인 필요** — 없으면 백엔드 소폭(NotificationService/DTO). `NotificationInboxView` 표시 보강.
- **내정보 축소**: 그룹관리 항목 제거, 내 정보 수정만. `MyInfoView`.
- **⋯ 그룹관리**(지도 상단 ⋯ → 신규 `GroupManageView`): 그룹 이름 수정·그룹원 목록/관리·그룹 삭제·탈퇴. ⚠️ **백엔드 그룹 관리 API(이름수정/삭제/그룹원 조회) 존재 확인 필요** — `GroupAPI.leaveGroup`은 있음.
- 파일: `Features/Notification/*`, `MyInfoView`, 신규 `GroupManageView`, `GroupAPI`(관리 메서드).

### IC-2 — 초대 코드 가입/공유 (#102 흡수)
- 초대 코드 **발급/복사 공유** + **코드 입력 가입**.
- develop에 `InviteCodeView`·`GroupCreateView`·`GroupStartView` 존재. #102 브랜치(`feat/ios-invite-code-entry`, CLOSED·보존)의 커밋 `56aa535` 참조.
- 진입: `GroupListView`(빈 상태/추가 행) + `GroupManageView`.
- 백엔드 IC-1(#101, V019 재사용 초대코드) 머지됨. `GroupAPI.issueInviteLink`/`acceptInvite` 존재.

## 운영 메모 (이어개발 필수 — 실수 방지)
- **oh-my-gx 서브에이전트(product-owner/architect/qa-manager) 산출물 미반환** → PRD/설계/리뷰/인수검증을 **오케스트레이터가 직접** 작성. coder는 정상 작동.
- **gh 활성계정이 자주 bs-koo로 돌아감** → push/PR 전 `gh auth switch --hostname github.com --user rnqhstmd` 필수.
- 백엔드 빌드 게이트 = `compileJava compileTestJava` (전체 gradle test는 선행 실패 다수라 부적합).
- **iOS = Windows 빌드 불가** → 커밋은 Windows, 빌드·시뮬·단위테스트 검증은 **Mac(DoD-B)**. 시그니처/enum/로직 정합은 직접 검토로 보장.
- 푸시 계정 rnqhstmd.

## 이어개발 절차 (새 세션 clear 후)
1. 이 `roadmap.md` + `design.md`/`prd.md`/`codemap.md`/`state.md` 읽기.
2. `git checkout feat/ios-ia-redesign` (PR #106 브랜치, 골격 위에 누적).
3. 단계 택1 → `/gx-dev "C 맵/필터 정리 구현"`(또는 DM/D/IC-2). NORMAL, 직접 PRD/설계.
4. 단계별 커밋. 점검 필요 시 PR + Mac 빌드.
5. 권장 순서: **C → DM → D → IC-2** (C가 가장 작고 골격 직결, DM이 #105 소비 핵심).

## 머지 전략 (2026-06-08 확정)
- **묶음 한 번 머지**: `feat/ios-ia-redesign`에 C/DM/D/IC-2를 단계별 커밋으로 누적(PR #106 갱신) → **집에서 Mac 전체 빌드/시뮬/단위테스트 검증** → 통과 후 develop에 **한 번 머지**.
- **단계별 순차 머지는 안 함**. 근거: iOS는 Mac 검증이 병목 — 단계마다 머지하려면 매번 Mac 필요(비현실적)이고, Mac 미검증 골격을 develop에 일찍 머지하면 빌드 오염 위험. IA 재설계(골격+C+DM+D+IC-2)는 응집된 한 단위라 묶음 머지가 적합.
- **주의**: 묶음 기간 동안 develop과 벌어지지 않게 **주기적 `git pull origin develop` 병합**(특히 chat/group/map 도메인 충돌 주시 — #104/#105가 그 영역).
- 지금 #106을 develop에 머지하지 **않는다**(Mac 미검증).
