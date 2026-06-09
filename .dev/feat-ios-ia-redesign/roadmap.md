# iOS IA 재설계 — 이어개발 로드맵 (feat/ios-ia-redesign)

> 새 세션(clear 후) 이어개발용 마스터 문서. 이 파일 + 같은 폴더의 design.md/prd.md/codemap.md/state.md를 함께 읽을 것.

## 현재 상태 (2026-06-08 갱신)
- 브랜치 **`feat/ios-ia-redesign`** (base develop)
- **A 내비 골격 ✅ PR #106 → develop 머지 완료** (merge `11afd42`, 2026-06-08 05:41). Mac DoD-B는 머지 후 별도.
- **C 맵/필터 정리 ✅ PR #107 → develop 머지 완료** (merge `deb546f`).
- **DM 그룹별 봇방 목록 ✅ 커밋 `216b876`, PR #108 (base develop)** — 머지/Mac DoD-B는 리뷰어.
- ⚠️ **묶음 머지 전략 폐기**: A가 #106으로 단독 머지되어 "한 브랜치에 누적 후 한 번 머지"는 무효. **이후 D/IC-2도 단계별 PR**(같은 브랜치에서 develop 기준 커밋 1~N개씩). 각 PR 머지 전 Mac DoD-B는 리뷰어.
- **다음 단계: D(알림 상세/내정보 축소/그룹관리 ⋯) → IC-2(초대 코드).**

## develop 기반 자산 (전부 머지됨)
- 백엔드: `GET /groups`(GM-1, iOS `GroupAPI.listMyGroups` 소비 중) / `GET /chat/bot/rooms` + `POST·GET /chat/bot/{groupId}/messages`(GM-2 B #105, **iOS 미소비 — DM 단계서**) / 초대 IC-1(#101 V019 재사용 코드)
- iOS(골격 완료분): `GroupContext`(그룹목록·currentGroupId·lastGroupId persist), `GroupListView`, 4탭 `MainTabView`, 지도 2레벨, `MapView` 상단 오버레이+어디가지 FAB, `BotChatViewModel`(릴스 위저드 #104 — savePlaceCards 위시/발견+메모+instagramUrl)

## 남은 단계 (각각 /gx-dev NORMAL 권장)

### C — 맵/필터 정리 ✅ 완료 (커밋 e2578ba, PR #107)
- **필터/범례 좌하단 → 상단 이동** ✅: `TagLegendButton`/`TagFilterButton`을 MapView 상단 행(그룹 오버레이 아래 우측)으로. 좌하단은 어디가지 FAB 단독. TagFilterBar 팝업 아래·trailing 전환.
- **맵 로딩 "로딩 척"** ✅(B안): 구조 변경 없이 `switchTo` 줌아웃→줌인 연출 + 핀 원자 교체 + 전면 스피너 제거. 위치 거부 시 핀 bounds 줌인. 줌아웃 소비 보장 120ms.
  - ⚠️ **진짜 1회 로딩(Mapbox 상시 마운트, 목록→선택 재로딩 제거)은 후속 분리** — MapView 상시 마운트 + enterGroup→switchTo 통합 구조 변경 필요(리스크로 C에서 제외, B안 선택).
- 파일: `MapView.swift`, `MapViewModel.swift`, `TagFilterBar.swift` + `MapViewModelTests.swift`(switchTo 5건).

### DM — 그룹별 봇방 목록 ✅ 완료 (커밋 216b876, PR #108)
- DM 탭을 단일 `BotChatView` → **그룹별 봇방 목록**(신규 `DMListView`/`DMListViewModel`). 인스타식 읽음(unread 굵게+강조점), 방별 VM 인스타식 재생성, DM 탭 미읽음 배지.
- `ChatAPI` 그룹별 전환(`botRooms()` + `botMessages/sendBotMessage(groupId:)`, 구 비그룹 호출 제거). `BotChatViewModel` groupId 주입(groupAPI 제거→릴스 저장=그 방 그룹). 읽음 갱신=방복귀+포그라운드 refresh(백엔드 GET시 읽음처리). 백엔드 무변경(#105 머지분 소비). Mac DoD-B 잔여.
- (구 계획 메모) DM 탭을 단일 `BotChatView` → **그룹별 봇방 목록**(신규 `DMListView`).
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

## 머지 전략 (2026-06-08 개정 — 묶음 폐기)
- ⚠️ **묶음 전략 폐기**: A가 PR #106으로 develop에 **이미 단독 머지**됨(merge `11afd42`). "한 브랜치 누적 후 한 번 머지" 전제가 깨짐.
- **현행: 단계별 PR**. 같은 `feat/ios-ia-redesign` 브랜치에서 develop 기준으로 단계별(C=#107, 이후 DM/D/IC-2) PR을 올린다. 머지·Mac DoD-B 검증은 리뷰어.
- **주의**: develop과 벌어지지 않게 단계 시작 전 **`git pull origin develop` 병합**(chat/group/map 도메인 충돌 주시 — #104/#105/#106이 그 영역). C 작업 시점엔 origin/develop과 동기였음.
- 각 PR은 Mac 미검증 상태로 올라가며(Windows 빌드 불가), 머지 전 Mac 빌드/시뮬/단위테스트(DoD-B)는 리뷰어가 수행.
