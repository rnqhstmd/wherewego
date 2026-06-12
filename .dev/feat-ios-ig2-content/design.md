# 설계: IG-2 인스타 리디자인 — 채팅방·프로필·알림

> 2026-06-12 승인. 설계 규모: 중형 (수정 ~16 + 신규 2 + 테스트). 백엔드 DB 마이그레이션 없음.
> PRD: .dev/feat-ios-ig2-content/prd.md · SSOT: context/ig-redesign-plan.md

## §0 설계 원칙

- WGColor/WGFont 토큰만 사용, cta `#C4622D`. IG-1 부품(`InstaNavBar`, 플랫 행)·GP-1 부품(`AvatarView`/`GroupAvatarView`) 재사용.
- 백엔드 신규 필드는 전부 추가형(기존 필드 불변) + iOS는 `decodeIfPresent` 옵셔널(구서버 호환 — develop→main 배포 시차 관례).
- 죽은 코드는 삭제(주석 보존 금지).

## §1 채팅방 인스타 DM화 (FR-1) — GroupMessageRow · GroupChatView

**버블 (GroupMessageRow)**
- 수신: `WGColor.panel` 필 + hairline, `UnevenRoundedRectangle(topLeading 20, bottomLeading 6, bottomTrailing 20, topTrailing 20)` — 좌하단 꼬리 6r.
- 발신: `WGColor.cta` 필, 라운드 20 균일, 텍스트 `WGColor.panel`. 기존 radius 16 → 20.
- 아바타: 32 → 28pt, 표시 위치를 묶음 첫 메시지(showsSender) → 묶음 마지막 메시지(showsTime)로 이동 + 하단 정렬(인스타 문법). 닉네임 라벨은 묶음 첫 메시지 유지(그룹챗 필수). 자리 폭 32→28 조정.
- 시각 라벨: 현행 유지(묶음 마지막에만, 버블 옆 하단).
- REEL_LINK: 인스타 게시물 공유 카드 — 썸네일 카드 상단 풀블리드(내부 패딩 제거, 카드 radius 20 상단 맞물림), 하단 라벨 영역(play 글리프+「Instagram 릴스」+도메인)+3상태 버튼. 버튼 로직·콜백 무변경(회귀 금지).

**헤더 (GroupChatView)**
- `navigationTitle(groupName)` → `.toolbar { ToolbarItem(placement: .principal) }` 커스텀 타이틀: `GroupAvatarView`(28) + VStack(그룹명 Pretendard Bold 16 / "멤버 N명" 11 inkSoft). 시스템 back 유지.
- 데이터: `DMListView`가 보유한 `groupContext`에서 `GroupSummary?`(imageUrl·members·memberCount)를 `GroupChatRoomView(room:group:…)`로 추가 전달. nil(미로딩)이면 이니셜 폴백 + 멤버 수 생략.

**입력바**: 이미 캡슐 필 + 필 안 원형 전송(IG 문법 선반영) — 구조 무변경, 미세 정합만.

## §2 진입 단순화 (FR-2) — GroupChatViewModel · GroupChatView

| 제거 | 위치 |
|---|---|
| `initialUnreadCount` 프로퍼티 + init 파라미터 | GroupChatViewModel (MainTabView 팩토리 호출부 동시 수정) |
| `serverLastReadId` 프로퍼티 + load()의 세팅 | GroupChatViewModel (DTO `lastReadMessageId` 필드는 계약 유지, 소비만 제거) |
| `initialUnreadAnchorId` 계산 프로퍼티 + `isMine` 헬퍼 | GroupChatViewModel |
| `didInitialScroll` @State + onAppear 앵커 분기 + 진입 시 필 노출 | GroupChatView — onAppear는 무조건 `scrollToBottom(animated: false)` |

- 유지: `isNearBottom`/`showNewMessagePill`(도착 배너), 읽음 처리(서버 방 GET 시 전진 — 무변경), 목록 미읽음.
- 테스트: GroupChatViewModelTests 앵커 테스트 삭제·단순화 반영. reconcileLatest 플래키 — 주입 sleeper 기반 시간 의존 제거 하드닝. BotChatViewModelTests 불변(GC-3 폐기 예정 영역).

## §3 내정보 프로필화 (FR-3) — MyInfoView · MyInfoViewModel · 신규 ProfileEditView

**MyInfoView 재구성** (고운바탕 "마이페이지" → 인스타 프로필)
- `InstaNavBar(title: "내 정보")` + 프로필 헤더: `AvatarView` 84pt + 우하단 카메라 배지(26pt 원, `camera.fill`, panel+hairline — 탭=기존 액션시트(앨범/제거)→피커→크롭 플로우 그대로) + 닉네임 + 통계 2종 HStack(숫자 Bold 17 + 라벨 12 inkSoft): 그룹 N · 핀 N.
  - 그룹 수 = `groupContext.groups.count` (MainTabView가 GroupContext 주입 — DMListView 선례). 핀 수 = `viewModel.pinCount`.
- 프로필 편집 풀폭 라이트 버튼 1개(bg 필, radius 10) → ProfileEditView 시트. (프로필 공유 없음 — PRD Q2)
- **설정 플랫 리스트 3행(확정)**: 알림 설정 · 로그아웃 · 계정 삭제(danger). '내 그룹 관리'(지도 탭 ⋯ 담당)·'약관'(범위 제외, 제출 시점 추가)은 넣지 않는다. 로그아웃/삭제 로직 무변경.
- 알림 설정 = `UIApplication.openSettingsURLString` 열기.

**ProfileEditView 신설** (인스타 프로필 편집 문법)
- 상단 아바타 84 + "사진 변경" 버튼(기존 액션시트/피커/크롭/업로드·제거 플로우 재사용) + 닉네임 텍스트필드(`Nickname` 검증 재사용) + 완료(`authAPI.updateNickname`). 기존 NicknameView(온보딩 공용) 무변경 존치.

**MyInfoViewModel**: `pinCount: Int?` @Published — `load()`의 `GET /users/me` 응답 반영. iOS `UserResponse`에 `pinCount: Int?`(decodeIfPresent).

## §4 알림 피드화 + 핀 딥링크 (FR-4) — NotificationInboxView/VM · DeepLinkRouter · MapViewModel · MainTabView

**행 디자인 (NotificationRow 교체)** — 카드 → 플랫 행
- `AvatarView(40, 행위자 프사·이니셜 폴백)` + 인라인 Bold 문구(Text 연결: **닉네임** Bold + 행위) + 상대시각 inline + 우측 36pt 썸네일(`pinThumbnailUrl`, radius 8, AsyncImage — nil 생략) + 미읽음 = 옅은 cta 배경 유지.
- 섹션: 오늘 / 이번 주 / 이전 — createdAt 그룹핑.

**탭 동작 = 핀 딥링크** (알림 상세 화면 폐기 확정 — 설계 Q2)
1. 행 탭 → 기존 `detail(id:)` API로 핀 목록 확보(대표 pinId 목록 응답 추가 불필요 — PRD FR-5 축소).
2. 유효 핀(deleted 아님 + 좌표 존재) 0개 → 토스트 "삭제된 장소예요".
3. 그룹 접근 불가(groupContext.groups에 groupId 없음) → 토스트 "더 이상 함께하지 않는 그룹이에요".
4. 유효 → `deepLinkRouter.pending = .pinFocus(groupId:, pinIds:)`.

**DeepLinkDestination**: `.pinFocus(groupId: Int, pinIds: [Int])` 추가(`.reelFocus` 선례).
**MainTabView.consumePending**: `.pinFocus` → `selection = .map` + `groupContext.enterGroup(groupId)` + `mapViewModel.focusPins(groupId:pinIds:)`.
**MapViewModel.focusPins(groupId:pinIds:)** 신설 — focusReel 미러:
- 그룹 다르면 `switchTo`, 같으면 `reloadPinsAppendOnly()` → targets = pins.filter(ids).
- 1개: `cameraCommand`(zoom: pinFocusZoom) + `selectedPinId = id`(말풍선 자동 오픈). N개: `fitBounds(markers:)`. 0개: no-op.
- `focusedInstagramUrl` 미설정(릴스 배너 미발화).

**NotificationInboxViewModel**: GroupContext 주입(MainTabView 팩토리) + `infoMessage: String?`(토스트) + selectItem 동작 교체 + activeDetail/clearDetail/flyToPin/상세 화면 코드 제거.
**iOS NotificationItem 디코더**: `registeredByProfileImageUrl`·`groupId`·`thumbnailUrl` 옵셔널 추가(decodeIfPresent, 구서버 호환).

## §5 백엔드 소규모 (FR-5) — 마이그레이션 없음

**GET /users/me** — `UserV1Dto.UserResponse`에 `pinCount: long` 추가.
- `UserService.me()`: `pinRepository.countByCreatedBy(userId)` 신설(`countByCreatedByAndDeletedAtIsNull` JPA 파생 — `Pin.createdBy` 확인 완료). AuthV1 로그인 응답 무변경.

**GET /notifications** — `NotificationV1Dto.NotificationItem` 3필드 추가:
- `registeredByProfileImageUrl` — listRecent의 `findNicknamesByIds` → `findProfilesByIds`(GP-1 유효 프사 resolver) 교체.
- `groupId` — `Notification.getGroupId` 노출(이미 로드됨).
- `thumbnailUrl` — 첫 핀(links[0], 이미 batch 로드)의 `photoThumbnailKey` → public URL(`PinService.toSummary`와 동일 키→URL 조합기 재사용). 사진 없으면 null.
- `NotificationItemResult` 동반 확장. 상세 API 무변경.

**테스트**: UserV1ControllerIntegrationTest(pinCount)·NotificationV1ControllerIntegrationTest(3필드) 보강. Docker 기동 필수·선행 실패는 develop 워크트리 대조.

## §6 구현 순서 (4단계 순차 — MainTabView 공유로 병렬 배치 불가)

1. **B1 백엔드**: §5 + 통합 테스트 → compileJava/compileTestJava + 관련 테스트
2. **B2 채팅방**: §1 + §2 + 단위 테스트 정리(앵커 삭제·플래키 하드닝)
3. **B3 내정보**: §3 (ProfileEditView 신설 포함)
4. **B4 알림·딥링크**: §4 (NotificationAPI 디코더 → VM → Row → DeepLink → MapViewModel.focusPins + 단위 테스트)

검증: 백엔드 = ./gradlew 컴파일+관련 테스트(cmd /c 금지·출력 직접 확인), iOS = CI(GitHub Actions, Windows 로컬 빌드 불가). 시각 QA = Mac DoD-B(비범위).

## §7 리스크

- GroupChatView onAppear 앵커 제거 시 키보드/배너 회귀 주의 — isNearBottom 초기값 true 유지로 진입 직후 자동 스크롤 정상.
- 알림 탭이 detail API 왕복 → 수백ms 지연 — isDetailLoading 인디케이터 재사용(행 비활성)으로 흡수.
- focusPins 1개 케이스: selectedPinId 즉시 세팅(markerTapped 선례, 화면좌표 자가 추적 갱신) 수용.

## 설계 Q&A 확정 이력

- Q1 내 그룹 관리 → 내정보에서 제거(지도 탭 ⋯ 담당)
- Q2 알림 상세 화면 → 폐기(탭=딥링크 완전 대체)
- Q3 약관 → 범위 제외(제출 시점 추가)
