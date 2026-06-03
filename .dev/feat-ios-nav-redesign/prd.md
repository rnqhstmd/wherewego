# PRD: P7 — iOS 내비게이션 재설계 (5탭 통일 + ＋통합추가 + 알림함/내정보 이식)

> 설계 확정 산출물 기반(`docs/superpowers/specs/2026-06-02-ios-nav-redesign-design.md`). PRD는 설계서 의도를 요구사항/수용기준으로 정형화한다.
> 확정 결정(2026-06-03): 알림 미읽음 배지 = **빨간 점만**(웹 정합) / 계정 삭제 = **포함**(설계서·App Store Guideline 5.1.1 필수).

## 배경

현재 iOS 앱은 하단에 **지도/봇/커플 3탭**이 있고, 지도 탭 안에 **검색·추가·룰렛 액션바**가 별도로 존재한다. 사용자는 지도 화면 하단에 두 줄의 바가 겹쳐 보이는 혼란을 경험한다. 릴스 링크로 장소를 저장하는 "봇방"이 커플 대화와 같은 채팅방 급으로 나란히 배치돼 성격이 어긋난다. 설정(내정보)·알림함 화면은 iOS에 없어 웹으로만 접근 가능하다.

**현재 상태(코드 확인):**
- MainTabView: 3탭(지도·봇·커플) + `.coupleChat` 딥링크 목적지 포함 (MainTabView.swift, DeepLinkRouter.swift)
- 지도: 하단 액션바(검색/여기에 추가/룰렛 3버튼 HStack), 우상단·우하단 플로팅 버튼 없음 (MapView.swift)
- 알림 REST: `GET /api/v1/notifications`, `POST /api/v1/notifications/read-all`, `GET /api/v1/notifications/{id}` 노출됨(NotificationV1Controller, 최신 50건)
- 설정 API: 닉네임 PUT, 그룹 탈퇴, 로그아웃, 계정 삭제 `DELETE /api/v1/users/me` 전부 기존 재사용

## 목표

- 하단 내비게이션을 **단일 5탭**으로 통일하여 "바 두 줄 혼란" 해소
- 장소 추가 진입점을 **가운데 ＋ 하나**로 통합(검색·지도 콕찍기 모두 수용)
- 릴스 저장 동선을 **채팅 탭 직행**으로 단축
- 알림함·내정보 화면을 iOS 네이티브로 신규 제공(웹 이식)
- iOS 17.0+ 전 버전에서 동일한 둥근 플로팅 바, iOS 26+에서 Liquid Glass 자동 적용
- 커플챗을 제거하여 제품 범위 정리

## 비목표

- 인스타 릴스 Share Extension — 후속 옵션, 범위 외
- 백엔드 커플방(chat_room COUPLE) 물리 삭제 — 컷오버 시점
- 시각 픽셀 QA, TestFlight 배포, 앱스토어 제출 — Mac 필요, DoD-B로 분리
- 백엔드 신규 개발 — 알림·설정 API 전부 기존 재사용

## 요구사항

### 기능 요구사항 — 5탭 IA

- [Must] FR-1: 하단 탭바를 `어디갈까 · 채팅 · ＋ · 알림 · 내정보` 5칸으로 재구성. 기존 커플 탭 제거.
- [Must] FR-2: ＋는 가운데, 주황(WGColor.cta) 동그라미. 탭 선택 상태로 머물지 않고 누르면 추가 시트 오픈. 바 안에 flush(튀어나오지 않음).
- [Must] FR-3: 탭 선택 표시 = SF Symbols 외곽선↔채움 쌍. 선택=채움+주황 틴트, 미선택=외곽선+회색. 알약 배경 없음.
  - 어디갈까 `map`/`map.fill` · 채팅 `bubble.left.and.bubble.right`/`.fill` · 알림 `bell`/`bell.fill` · 내정보 `person`/`person.fill`
- [Must] FR-4: 하단 바 = **둥근 플로팅 필 바**(바닥에서 띄움+그림자). iOS 26+ = `if #available(iOS 26, *)` Liquid Glass. iOS 17~25 = 솔리드 불투명 흰색 폴백(동일 둥근 필 형태). deploymentTarget iOS 17.0.

### 기능 요구사항 — 어디갈까(지도)

- [Must] FR-5: 지도 화면 하단 액션바(검색·여기에 추가·룰렛 3버튼) 제거.
- [Must] FR-6: 룰렛 진입 = 지도 **우상단 플로팅 버튼**(태그필터 바 아래).
- [Must] FR-7: 내 위치 찾기(locate-me) = 지도 **우하단 플로팅 버튼** 신규. 룰렛과 겹치지 않게.
- [Should] FR-8: 빈 핀 상태 카드(`EmptyMapCard`)의 "핀 추가" 진입 = ＋ 시트(AddPlaceSheet)와 동일 연결.

### 기능 요구사항 — 채팅(릴스 저장)

- [Must] FR-9: 채팅 탭 진입 = 기존 BotChatView(BotChatViewModel) 직행. 목록·선택 단계 없이 봇 릴스 저장방 직표시.
- [Must] FR-10: 라벨 "채팅", 말풍선 SF Symbols 쌍.
- [Must] FR-11: CoupleChatView, CoupleChatViewModel 삭제 + MainTabView에서 커플 탭 제거.

### 기능 요구사항 — ＋ 통합 추가 시트(AddPlaceSheet)

- [Must] FR-12: ＋ 버튼 → AddPlaceSheet 시트 오픈. 지도 + 상단 검색바 + 하단 확정 카드. 토글/탭 없이 검색·콕찍기 단일 시트 동작.
- [Must] FR-13: 검색바 입력 → 결과 → 선택 시 지도 핀 + 이름·주소 하단 카드 자동 채움. 무결과 시 "검색 결과가 없어요" 표시.
- [Must] FR-14: 지도 이동 → 검색 텍스트 초기화 + 중앙 핀 고정 + 주소 실시간 갱신. 역지오 = 온디바이스 `CLGeocoder.reverseGeocodeLocation`, 지도 정지 후 **디바운스 300ms**로 호출. 실패 시 "위도 XX.XXXX, 경도 YY.YYYY" 좌표 폴백.
- [Must] FR-15: 확정 후 태그(릴스/위시/추억) 선택 → "여기 등록" → 핀 생성(PinAPI.create 재사용).
- [Must] FR-16: 릴스 링크는 ＋ 시트에 미포함(검색박스 의미 불일치 회피 + 채팅 탭이 최단 동선).

### 기능 요구사항 — 알림함(NotificationInbox, 신규)

- [Must] FR-17: 알림함 신규 구현. 수신 알림 목록 최신순.
- [Must] FR-18: 행 = 종류 아이콘 + 문구 + 시간 + 읽음/안읽음.
  - `MANUAL_PIN`: 핀 아이콘, "파트너가 새 장소를 등록했어요"
  - `CHATBOT_PINS`: 채팅 아이콘, "릴스에서 N개 장소가 저장됐어요"
  - `VISIT_DETECTED`: 위치 아이콘, "방문 장소가 감지됐어요"
- [Must] FR-19: 진입 시 `GET /api/v1/notifications` 최신 50건 조회. 앱 포그라운드 복귀(scenePhase .active) 시 재조회.
- [Must] FR-20: 행 탭 → 해당 핀 지도 flyTo 딥링크. 핀 soft delete 시 flyTo 비활성, 행 "삭제된 장소: {이름}" 표시.
- [Must] FR-21: 진입 시 `POST /api/v1/notifications/read-all` 전체 읽음. 성공 전까지 미읽음 배지 유지.
- [Should] FR-22: 알림 탭 아이콘 미읽음 표시 = **빨간 점만**(웹 정합, 건수 미노출).

### 기능 요구사항 — 내정보(MyInfoView/Settings, 신규)

- [Must] FR-23: 내정보 신규 구현. 섹션 = 사용자 / 활성 그룹(보유 시) / 계정.
- [Must] FR-24: 사용자 = 아바타 + 닉네임 + 닉네임 수정(NicknameViewModel, PUT /users/me 재사용).
- [Must] FR-25: 활성 그룹 = 그룹명 + 인원수 + 그룹 탈퇴. 활성 그룹 없으면 섹션 전체 미표시. 탈퇴 시 확인 다이얼로그.
- [Must] FR-26: 계정 = 로그아웃(SessionStore 위임) + 계정 삭제(`DELETE /api/v1/users/me`, P2). 삭제 시 확인 다이얼로그.
- [Must] FR-27: 웹 SettingsClient "챗봇 연동" 섹션 이식 제외(컷오버 대상).

### 기능 요구사항 — 딥링크 정리

- [Must] FR-28: DeepLinkRouter에서 `.coupleChat` 제거. `COUPLE_MESSAGE` 푸시 타입 → `.chat`(채팅 탭) 재매핑. 기존 `.coupleChat` 딥링크 수신 시 채팅 탭 폴백.

### 비즈니스 규칙

- [Must] BR-1: ＋ 버튼은 탭 선택 상태 없음. 선택 인디케이터가 ＋에 표시되지 않음.
- [Must] BR-2: 커플챗 제거 = 제품 결정. CoupleChatView/VM 삭제 + MainTabView 의존성 완전 제거. 백엔드 커플방 데이터 잔존.
- [Must] BR-3: 역지오 디바운스 = 지도 움직임 종료 후 300ms 내 1회. 연속 드래그 중 중간 호출 차단(CLGeocoder 레이트리밋 방지).
- [Must] BR-4: 알림함 전체 읽음 = 탭 진입 시 1회. 실패 시 오류 미노출·무재시도(목록 조회 영향 없음).
- [Must] BR-5: 그룹 탈퇴·계정 삭제 = 확인 다이얼로그 수락 후 실행. 취소 시 무변경.
- [Should] BR-6: 알림 목록 조회 실패 시 빈 목록 대신 에러 메시지 + 재시도 버튼.

### 품질 기대

- [Should] QE-1: 5탭 전환이 기존 지도·채팅에 영향 없음. MapViewModel/BotChatViewModel 수명·딥링크 flyTo 유지.
- [Should] QE-2: AddPlaceSheet 검색·콕찍기가 동일 시트에서 충돌 없이 전환.

## 수용 기준

### 정적 검증 / 단위 테스트로 확인 가능(빌드 환경 불문)

- AC-1: MainTabView Tab 열거형이 `map, chat, notification, myInfo` 4개 목적지만 존재, `couple` 없음. [FR-1, FR-11, BR-2]
- AC-2: ＋ 버튼 핸들러가 `selection`을 변경하지 않고 추가 시트 상태만 세팅(단위 테스트). [FR-2, BR-1]
- AC-3: DeepLinkDestination에 `.coupleChat` 없음. `COUPLE_MESSAGE` → `.chat` 매핑. [FR-28]
- AC-4: DeepLinkRouter 단위 테스트: `COUPLE_MESSAGE` 푸시 타입 → `.chat`. [FR-28]
- AC-5: CLGeocoder 디바운스 단위 테스트: 300ms 내 연속 호출 시 마지막 1회만 실행. [BR-3, FR-14]
- AC-6: CoupleChatView.swift, CoupleChatViewModel.swift 파일이 프로젝트에 없음. [FR-11, BR-2]
- AC-7: NotificationAPI가 3엔드포인트(`GET /notifications`, `POST /notifications/read-all`, `GET /notifications/{id}`)를 올바른 경로로 호출(단위 테스트). [FR-19, FR-21]
- AC-8: AddPlaceSheet 검색 텍스트 비어있지 않은 상태에서 지도 드래그 시 검색 텍스트 초기화(ViewModel 단위 테스트). [FR-14]
- AC-9: CLGeocoder 실패 시 주소 필드 "위도 {lat}, 경도 {lng}" 폴백(단위 테스트). [FR-14]
- AC-10: 그룹 미보유(activeGroup == nil) 시 MyInfoView 활성 그룹 섹션 미렌더. [FR-25]
- AC-11: MyInfoView에 챗봇 연동 섹션 UI 없음. [FR-27]

### Mac 시각검증 필요(DoD-B 이연)

- AC-V1: 5탭 바가 하단 둥근 플로팅 필 형태. iOS26 Liquid Glass / iOS17 솔리드 폴백. [FR-3, FR-4]
- AC-V2: ＋ 주황 동그라미 flush 배치. [FR-2]
- AC-V3: 선택=채움+주황, 미선택=외곽선+회색 전환. [FR-3]
- AC-V4: 지도 하단 액션바 사라짐 + 우상단 룰렛·우하단 내위치 플로팅. [FR-5, FR-6, FR-7]
- AC-V5: ＋ → AddPlaceSheet 검색·콕찍기 단일 시트 동작. [FR-12, FR-13]
- AC-V6: 채팅 탭 진입 시 BotChatView 직행. [FR-9]
- AC-V7: 알림함 목록·진입 읽음·행 탭 flyTo 동작. [FR-17~21]
- AC-V8: 내정보 닉네임수정·그룹탈퇴·로그아웃·계정삭제 동작 + 탈퇴/삭제 확인 다이얼로그. [FR-23~26]
- AC-V9: 알림 빈/로딩/실패(재시도) 상태. [BR-6]
- AC-V10: 검색 무결과 "검색 결과가 없어요". [FR-13]

## 엣지케이스

- 알림 빈 목록: "아직 알림이 없어요". 로딩: ProgressView. 실패: 에러+재시도.
- CLGeocoder 레이트리밋: 디바운스 300ms 차단, 실패 시 좌표 폴백(그 상태로 등록 가능).
- 검색 무결과: "검색 결과가 없어요".
- 그룹 미보유 내정보: 활성 그룹 섹션 미표시(사용자/계정 섹션만).
- `.coupleChat` 딥링크(기존 앱 버전): 채팅 탭 폴백.
- ＋ 시트 검색 후 지도 드래그: 검색 텍스트 초기화 → 콕찍기 모드.
- 포그라운드 복귀: NotificationInbox 마운트 상태면 재조회.

## 영향 범위 / 하위 호환

- MainTabView 전면 재구성(CoupleChatViewModel 의존성 제거). MapView 액션바 제거+플로팅 추가(기존 지도·핀·방문감지 영향 없음). DeepLinkRouter `.coupleChat` 제거+재매핑. CoupleChatView/VM 파일 삭제.
- 기존 사용자: 커플챗 상실(제품 결정), 나머지 기능 유지. 기존 `.coupleChat` 푸시 딥링크 → 채팅 탭 리디렉션.

## 제외 범위

- 인스타 Share Extension / 백엔드 커플방 물리삭제 / 알림 실시간(SSE·WS, 진입+포그라운드 fetch 유지) / 알림 개별 읽음(read-all 일괄만) / TestFlight·제출.
