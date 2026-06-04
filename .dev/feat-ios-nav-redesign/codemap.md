## 코드 맵: iOS 내비게이션 재설계 (P7 — 5탭 통일 + ＋통합추가 + 알림함/내정보 이식)

> 설계서: `docs/superpowers/specs/2026-06-02-ios-nav-redesign-design.md`
> 성격: 순수 SwiftUI 작업(백엔드 추가 0). 알림함·내정보 = 웹→SwiftUI 이식.

### 핵심 파일
- `ios/WhereWeGo/App/MainTabView.swift:13` → 현재 **3탭(지도/봇/커플) TabView + 딥링크 소비**. P7 핵심: 5탭(어디갈까·채팅·＋·알림·내정보) + 센터 ＋ FAB + 둥근 필 바 + 버전별 재질로 재구성
- `ios/WhereWeGo/Features/Map/SearchPinSheet.swift` → 장소 검색→핀 추가 시트. ＋ 통합 추가 시트로 병합(검색측)
- `ios/WhereWeGo/Features/Map/CrosshairAddView.swift` → 지도 콕찍기 추가 뷰. ＋ 시트로 병합(콕찍기측) + 온디바이스 CLGeocoder 역지오코딩 신규
- `ios/WhereWeGo/Features/Map/MapView.swift` → 지도 홈. 하단 액션바(검색·추가·룰렛) 제거 + 룰렛(우상단)·내위치(우하단) 플로팅 재배치
- `ios/WhereWeGo/Features/Chat/Bot/BotChatView.swift` → 봇/릴스 저장방. "채팅" 탭 직행으로 재사용(동작 유지)

### 참조 파일
- `ios/WhereWeGo/App/DeepLinkRouter.swift` → 딥링크 목적지(.botChat/.coupleChat/.pin/.map/.invite). 커플 제거 반영 필요
- `ios/WhereWeGo/Features/Chat/Couple/CoupleChatView.swift` + `CoupleChatViewModel.swift` → **삭제 대상**(커플챗 제거, 백엔드 잔존)
- `ios/WhereWeGo/Features/Map/RouletteSheet.swift` / `RouletteViewModel.swift` → 우상단 플로팅 버튼으로 재배치
- `ios/WhereWeGo/Features/Onboarding/NicknameViewModel.swift` → 내정보 닉네임 수정 재사용(PUT /users/me)
- `ios/WhereWeGo/Core/Auth/SessionStore.swift` → 내정보 로그아웃 위임
- `frontend/src/app/map/_components/notifications/NotificationPanel.tsx` (+Item/PinList/Toast) → 알림함 SwiftUI 이식 레퍼런스
- `frontend/src/app/settings/SettingsClient.tsx` → 내정보(설정) SwiftUI 이식 레퍼런스

### 설정
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/notification/NotificationV1Controller.java` → `/api/v1/notifications` 목록/읽음(백엔드 추가 없음, iOS 얇은 NotificationAPI 클라만 신규)
- `frontend/src/lib/notifications/api.ts` → 알림 REST 계약 레퍼런스
- `ios/WhereWeGo/Features/Pin/PinAPI.swift` · `Features/Place/PlaceAPI.swift` → ＋ 시트 핀 생성·장소 검색 재사용

## 추가 탐색 (design 단계)
- `backend/.../notification/NotificationV1Dto.java` → 알림 DTO 필드(좌표 BigDecimal→**String** 직렬화 — iOS Codable `String?` 근거)
- `backend/.../group/GroupV1Controller.java` → `DELETE /groups/{groupId}/members/me` 그룹 탈퇴(200, success())
- `backend/.../user/UserV1Controller.java` → `DELETE /users/me` 계정 삭제
- `ios/WhereWeGo/Core/Location/CoreLocationService.swift` → `requestOneShot()` 내위치 버튼(FR-7) 참조
- `ios/WhereWeGo/Features/Map/EmptyMapCard.swift` → `onAddPin` 콜백 = ＋ 시트 연결 지점(FR-8)
- `ios/WhereWeGo/Core/Auth/AuthAPI.swift` → users/me path 패턴(닉네임 PUT·계정삭제 DELETE 동일) + `me()`
- `ios/WhereWeGo/Features/Group/GroupAPI.swift` → `myActiveGroup()`(ActiveGroup{groupId,name,memberCount}) + leaveGroup 추가 지점
- `ios/WhereWeGoTests/StubURLProtocol`·`PinAPITests`·`DeepLinkRouterTests` → 테스트 더블/수정 대상 패턴
- `frontend/src/app/map/_components/notifications/NotificationItem.tsx` → `formatTime` 시간 포맷 이식 참조
