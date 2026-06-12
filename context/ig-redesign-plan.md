# 인스타그램 스타일 리디자인 (IG) — Phase 계획

> 2026-06-11 목업 합의 확정(v4). 시각 스펙 원본: [app-redesign-instagram.html](app-redesign-instagram.html) (폰 프레임 5종 + 주석 = 승인된 설계).
> 색·폰트 토큰(WGColor/WGFont)은 100% 유지, **레이아웃 문법만 인스타그램으로** 교체. 인스타의 파랑 자리는 전부 테라코타(cta `#C4622D`).
> 의존: **GP-1 [#123](https://github.com/rnqhstmd/wherewego/pull/123)** (그룹 대표 이미지·프사·콜라주) — 모든 화면의 아바타가 GP-1 컴포넌트(AvatarView/GroupAvatarView) 기반.
> 작업 브랜치 `feat/ios-instagram-redesign` 생성됨(base = feat/group-profile-images, **stacked** — #123 머지 후 develop 리타겟).

## 확정 스펙 (사용자 합의 이력)

- **전역**: 카드 → 플랫 행(구분선 없이 여백 분리) · 고운바탕 큰 제목(ScreenHeader) → 경량 상단바(Pretendard Bold 21 + 우측 아이콘, 48pt) · 고운바탕은 온보딩/빈 화면 등 브랜드 모먼트에만
- **탭바 4탭**(지도·채팅·알림·내정보=내 프사 원형) — 어디가지 룰렛은 **지도 화면 플로팅 버튼**으로 복귀(탭 승격 #115 롤백, 합의 사항)
- **그룹 목록**: 스토리 행 없음(검토 후 기각). 아바타 54pt 플랫 행 + 멤버 전원 일렬(GP-1). 우상단 **＋ 탭 → 메뉴 2항목**: "새 그룹 만들기" / "초대 코드로 들어가기"(IC-2 슬러그 입력 재사용). 하단 칩 행 제거
- **채팅 목록**: 발신자 프리픽스("지민: ") 제거 — 미읽음 = **"새 메시지 N개"(검정 ink Bold)** + 우측 cta 점 8pt(빨간 카운트 캡슐 제거), 읽음 = 마지막 메시지 내용 + · 시각. 우상단 편집(✏️) 버튼 없음
- **채팅방**: 수신 = 흰 필+헤어라인(좌하단 꼬리 6r) / 발신 = cta 단색 필(라운드 20), 프사 하단 정렬 28pt(연속 메시지 그루핑 유지). REEL_LINK = 인스타 DM 게시물 공유 카드 문법. 입력바 = 둥근 필 + 필 안 원형 전송만(**카메라 버튼 없음** — 사진 전송 미지원). 헤더 = 그룹 아바타+이름+멤버 수
- **채팅방 진입 = 무조건 최신 메시지(단순화 확정)**: 미읽음 앵커 진입·진입 시 "새 메시지" 필·serverLastReadId 스냅샷 보존 로직 **제거**. 위로 읽는 중 도착 배너("새 메시지가 있어요")와 채팅 목록 미읽음 표시는 **유지**(백엔드 읽음 테이블·unreadCount 존속)
- **내정보 = 인스타 프로필**: 아바타 84pt + 우하단 카메라 배지(GP-1 원형 크롭 진입) + **통계 2종만**(그룹 수·등록한 핀 수) + 풀폭 라이트 버튼(프로필 편집/프로필 공유=초대 연계) + 설정 플랫 리스트(알림 설정·내 그룹 관리·약관·로그아웃·계정 삭제)
- **알림 설정 = 신규**(현재 미구현 — 온보딩 푸시 권한만 존재): 1차는 시스템 설정 딥링크(`openSettingsURLString`), 유형별 on/off는 후속
- **알림 탭**: 행위자 프사(GP-1) + "누가 무엇을" 인라인 Bold + 상대시각 + 우측 36pt 컨텍스트 썸네일, 오늘/이번 주 섹션. **핀 알림 탭 → 지도탭 전환 → 해당 그룹 맵 전환 → 핀 카메라 이동 → 핀 상세 말풍선 자동 오픈**(신규 딥링크, `.reelFocus` 선례의 핀 버전 — 알림 응답에 `pinId` 이미 존재 확인)

## Phase 분할 (변경이 크고 서로 연관된 것끼리 2개)

### IG-1: 셸 + 목록 — "탐색 구조 전환" (ios)

서로 맞물리는 전역 골격: 상단바·탭바·플랫 행을 바꾸면 목록 2종이 함께 바뀌어야 일관됨.

1. 공통 컴포넌트: 경량 상단바(`InstaNavBar` — ScreenHeader 대체), 플랫 행 패턴, 탭바 내정보 프사 원형
2. **탭바 5→4탭**: 어디가지 탭 제거 → MapView 플로팅 룰렛 버튼 복귀(speed-dial #98 선례), MainTabView 재배선
3. **그룹 목록**: 카드 → 플랫 행(GroupAvatarView 54 + 멤버 일렬), ＋ 메뉴(새 그룹/초대 코드 — GroupEntrySheet 재배선), 하단 칩 제거
4. **채팅 목록**: 카드 → 플랫 행 56, 미리보기 규칙 교체("새 메시지 N개" ink Bold / 내용+시각), 카운트 캡슐 → 점, 편집 버튼 제거
- 대상: MainTabView·MapView(플로팅)·GroupListView·DMListView(+VM 미리보기 규칙)·DesignSystem(상단바)·기존 ScreenHeader 사용처 일괄
- 백엔드 무변경

### IG-2: 콘텐츠 화면 + 신규 기능 — "채팅방·프로필·알림" (ios + backend 소규모)

1. **채팅방 인스타 DM화**: 버블 스타일(수신 흰 필/발신 cta 필·라운드 20·꼬리), 입력바 필+원형 전송, 헤더(아바타+멤버 수), REEL_LINK 공유 카드 스타일
2. **진입 단순화**: `initialUnreadAnchorId`·`serverLastReadId` 앵커 용도·`didInitialScroll` 앵커 분기·진입 필 제거 → 항상 scrollToBottom. 도착 배너 유지. 관련 단위 테스트 정리(reconcileLatest 플래키 포함 재점검 기회)
3. **내정보 프로필화**: 프로필 헤더(아바타 84+카메라 배지+통계 2종+버튼 2) + 설정 플랫 리스트 + 알림 설정(시스템 설정 딥링크)
4. **알림 탭 피드화 + 핀 딥링크**: 행 디자인 교체 + 탭 시 지도탭→그룹 전환→핀 포커스→말풍선 오픈(MapViewModel 핀 포커스 경로 신설, `.reelFocus` 패턴)
5. **백엔드 소규모**: `/users/me` 응답에 등록 핀 수(또는 stats 엔드포인트), 알림 목록에 행위자 프사 URL(GP-1 유효 URL resolver 재사용 — 필요 범위 확인 후)
- 대상: GroupChatView/VM·GroupMessageRow·MyInfoView/VM·NotificationInboxView/VM·DeepLink 라우터·MapViewModel + backend(UserV1·NotificationV1 소폭)

## 진행 규칙

- 순서: IG-1 → IG-2 (IG-2의 화면들이 IG-1 셸 컴포넌트를 사용)
- 단계별 PR(stacked 관례): IG-1 PR → 머지/리뷰 후 IG-2. GP-1 #123 머지 시 base 리타겟 필수
- 검증: iOS CI(GitHub Actions — Windows 빌드 불가), 백엔드 compileJava+compileTestJava, 시각 확인은 Mac DoD-B
