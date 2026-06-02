# iOS 내비게이션 재설계 — 설계 문서

- 날짜: 2026-06-02
- 대상: iOS 네이티브 앱(`ios/WhereWeGo`)
- 상태: 설계 확정(브레인스토밍 산출). 구현 계획 전 단계.

## 배경 / 문제

현재 iOS는 **하단 탭바(지도/봇/커플) 3탭** 위에 **지도 화면 안 별도 액션바(검색·추가·룰렛)** 가 겹쳐, 지도 탭에서 하단에 "가로 바 두 줄"이 보인다. 사용자가 이를 "네비게이션이 메뉴마다 바뀌는 듯한" 혼란으로 인지했다(실제로는 탭바=고정 네비, 액션바=지도 도구가 공존). 또한:

- **봇방**은 사람 간 대화가 아니라 "릴스 링크 → 장소 저장" **도구**인데, 커플방과 같은 "채팅방" 급으로 나란히 놓여 성격이 어긋난다.
- 장소 추가가 검색/좌표(크로스헤어)로 분산돼 있다.
- 설정/내정보 화면, 알림함 화면이 iOS에 **아직 없다**(웹에는 있음).

## 목표

- 하단 네비게이션을 **단일 5탭**으로 통일하고, 지도 안 별도 액션바를 제거한다.
- "장소 추가"를 가운데 **＋(센터 액션 버튼)** 하나로 모은다(검색 + 지도 콕찍기 통합).
- 릴스 저장을 **최단 동선**(채팅 탭 → 붙여넣기 → 전송)으로 만든다.
- 알림함·내정보 화면을 신규 구현한다.
- iOS 버전별로 일관된 모던 외형(둥근 플로팅 바, 26+ 글래스)을 제공한다.

## 비목표 (Out of scope)

- 1:1 커플 채팅(앱 내 사람 간 대화)은 **제거**한다(아래 결정 참조).
- 인스타 공유 시트(Share Extension)로 릴스 입력 — **후속 옵션**으로 남긴다.
- 백엔드 커플방(chat_room COUPLE) 물리 삭제 — 컷오버 시 정리(지금은 잔존).

## 확정 내비게이션 IA

### 하단 바 (공통 형태)
- **둥근 플로팅 필 바**(바닥에서 살짝 띄움, 그림자). 모양은 모든 버전 동일.
- **재질**: iOS 26+ = Liquid Glass(유리질 반투명, `if #available(iOS 26, *)`). iOS 17~25 = **솔리드 불투명 흰색**(글래스/플로팅 효과 없이 같은 둥근 필 형태). deploymentTarget = iOS 17.0.
- **5칸 구성·순서**: `📍 어디갈까 · 💬 채팅 · ＋ · 🔔 알림 · 👤 내정보`
- **＋**: 가운데, 주황(WGColor.cta) 동그라미, **바 안에 flush**(튀어나오지 않음). **목적지 탭이 아니라 액션**(누르면 추가 시트 오픈, 선택 상태로 머물지 않음).
- **선택 표시**: SF Symbols 외곽선↔채움 쌍 + 주황 틴트(알약 배경 없음). 예: `map`/`map.fill`, `bubble.left.and.bubble.right`/`.fill`, `bell`/`bell.fill`, `person`/`person.fill`. 미선택은 회색 외곽선.

### 탭별 화면

#### 1) 📍 어디갈까 (지도/홈) — 기존 MapView 개편
- 지도·핀(릴스/위시/추억)·방문감지·핀상세는 기존 유지.
- **지도 내 하단 액션바(검색·추가·룰렛) 제거.** 검색·추가는 ＋로 이관, 룰렛은 분리.
- **🎲 룰렛**: 지도 **우상단 플로팅 버튼**(태그필터 아래). 내 위치 버튼(우하단)과 겹치지 않게.
- **📍 내 위치 찾기**: 지도 **우하단 플로팅 버튼**(locate-me). (현재 명시 버튼 없음 → 추가)

#### 2) 💬 채팅 — 릴스 저장방 직행 (기존 BotChatView 재사용)
- 탭 진입 = **봇/릴스 저장방 직행**(목록 단계 없음).
- 입력바에 **릴스 링크 붙여넣기 → 전송(↑)** → "처리중" → 장소 카드 → 다중 선택 → "N곳 저장". (현재 봇방 동작 그대로)
- 라벨 = **"채팅"**, 말풍선 아이콘.
- **커플챗 제거**: `CoupleChatView`/`CoupleChatViewModel` 삭제, MainTabView에서 커플 탭 제거.

#### 3) ＋ 통합 추가 (신규 시트 — SearchPinSheet + CrosshairAddView 통합)
- ＋ 누르면 **추가 시트**(지도 + 상단 검색바 + 하단 확정 카드). **토글/탭 없음.**
- **검색**: 검색바에 장소·주소 입력 → 결과 → 선택 시 지도에 핀 + 이름·주소 자동 채움.
- **지도 콕찍기**: 지도를 움직이면 검색 텍스트 초기화, 중앙 핀 고정, **주소 실시간 갱신**.
  - 역지오코딩 = **온디바이스 `CLGeocoder.reverseGeocodeLocation`** (백엔드 불필요), 지도 정지 시 디바운스 호출, 실패 시 좌표 표시 폴백.
- 둘 다 마지막에 **태그(릴스/위시/추억) 선택 → "여기 등록"**.
- **릴스 링크는 ＋에 포함하지 않는다**(검색박스 의미 불일치 회피 + 릴스는 채팅 탭이 최단 동선).

#### 4) 🔔 알림 — 알림함 (**웹 이식**)
- 웹 `frontend/src/app/map/_components/notifications/`(NotificationPanel/Item/PinList/Toast) + `frontend/src/lib/notifications/api.ts`를 SwiftUI로 이식. 디자인·동작 레퍼런스 존재.
- 수신 알림 목록(최신순). 종류 3가지(`NotificationType`):
  - `MANUAL_PIN` — 파트너가 핀 추가
  - `CHATBOT_PINS` — 봇이 릴스에서 장소 저장(결과 도착)
  - `VISIT_DETECTED` — 방문 감지
- 행: 종류 아이콘 + 문구 + 시간 + 읽음/안읽음.
- 탭 → 딥링크(핀 → 지도 flyTo 등). 진입 시 읽음 처리.
- 데이터: **REST 확인됨** — `NotificationV1Controller` (`/api/v1/notifications`, 목록/읽음) 노출, 웹이 사용 중. **백엔드 추가 작업 없음.** iOS는 얇은 `NotificationAPI` 클라이언트(GET 목록 / 읽음 POST)만 추가.

#### 5) 👤 내정보 — 설정 (**웹 이식**, 우측 끝)
- 웹 `frontend/src/app/settings/SettingsClient.tsx`를 SwiftUI로 이식(레거시 "챗봇 연결 코드"는 제외 — 컷오버 대상).
- 구성: **사용자**(아바타 + 닉네임 + 닉네임 수정) / **활성 그룹**(그룹명·인원수·그룹 탈퇴, 보유 시) / **계정**(로그아웃, 계정 삭제).
- API 전부 기존 재사용: 닉네임 PUT `/users/me`, 그룹 탈퇴, 로그아웃(SessionStore), 계정 삭제 `DELETE /api/v1/users/me`(P2). **백엔드 추가 없음.**

## 제거 / 이관 요약

| 항목 | 처리 |
|------|------|
| 하단 탭바 지도/봇/커플 3탭 | → 5탭(어디갈까·채팅·＋·알림·내정보)으로 재구성 |
| 지도 내 액션바(검색/추가/룰렛) | 검색·추가 → ＋ 시트 / 룰렛 → 지도 우상단 플로팅 |
| 봇 탭 | → "채팅" 탭(릴스 저장방 직행)으로 재라벨·직행 |
| 커플 탭(CoupleChatView) | **제거** (백엔드 커플방은 잔존, 컷오버 시 정리) |

## 신규 / 재사용

**iOS 뷰 신규 작성** (디자인·API·백엔드는 기존 — 순수 SwiftUI 작업)
- `MainTabView` 재구성(5탭 + 센터 ＋ FAB + 둥근 필 바 + 버전별 재질 + 외곽선↔채움 선택). — iOS 고유
- ＋ 통합 추가 시트(검색+지도 통합, CLGeocoder 역지오코딩). — 기존 SearchPinSheet/CrosshairAddView 합침 + 역지오 추가
- 알림함 화면 + 얇은 `NotificationAPI` 클라이언트. — **웹 `notifications/` 이식**(백엔드 `NotificationV1Controller` 그대로)
- 내정보(설정) 화면. — **웹 `SettingsClient` 이식**(API 전부 기존)
- 지도 룰렛 우상단 플로팅 버튼 + 내 위치 우하단 버튼. — 기존 RouletteSheet/위치서비스 재배치

> 정리: 알림함·내정보는 **밑바닥 신규가 아니라 웹→SwiftUI 이식**(P3~P5와 동일 성격). 디자인 레퍼런스·REST·백엔드 모두 완비.

**재사용**
- `BotChatView`/`BotChatViewModel` → 채팅(릴스) 탭.
- `SearchPinSheet`/`SearchPinViewModel`, `CrosshairAddView` → ＋ 통합 시트로 합침.
- `PinAPI`/`PlaceAPI`/`GroupAPI`, `MapView`/`MapViewModel`, `PinDetailSheet` 등 유지.
- 닉네임 수정: 기존 `NicknameViewModel`(PUT /users/me) 재사용.
- 계정 삭제: P2 `DELETE /api/v1/users/me` 재사용.

## 컴포넌트 경계

- **RootShell(MainTabView)**: 5탭 + ＋ 액션 + 딥링크 소비. 각 탭 화면은 독립.
- **AddPlaceSheet**: 입력(검색|지도) → `{coordinate, name, address?, tag}` → PinAPI.create. 검색/역지오코딩은 내부 서비스.
- **NotificationInbox**: NotificationAPI(목록/읽음) → 행 → 딥링크 라우팅.
- **MyInfo(Settings)**: 프로필/그룹/계정 섹션. 각 액션은 기존 API 위임.
- 각 단위는 "무엇을 하는가/어떻게 쓰는가/무엇에 의존하는가"가 명확하도록 화면 단위로 분리.

## 백엔드 영향 / 의존성

- **백엔드 추가 개발 없음**: 알림 목록/읽음 REST = `NotificationV1Controller`(`/api/v1/notifications`) **노출 확인됨**(웹 사용 중). 설정 API(닉네임/그룹탈퇴/계정삭제) 모두 P1/P2 기존. ＋ 역지오코딩은 온디바이스 `CLGeocoder`(백엔드 불필요).
- **잔존**: 커플방(chat_room COUPLE + 메시지 REST/STOMP)은 앱에서 미사용. 삭제는 컷오버에서.

## iOS 버전 처리

- deploymentTarget iOS 17.0 유지.
- Liquid Glass는 `if #available(iOS 26, *)` 분기. 미만 버전은 솔리드 둥근 필(폴백)로 동일 형태 렌더.
- SF Symbols 외곽선/채움 쌍·`CLGeocoder`·플로팅 버튼 모두 iOS 17+ 지원.

## 리스크 / 메모

- **커플챗 제거 = 제품 결정**: 짝꿍 간 인앱 대화 상실, 공유는 지도/핀으로. 사용자 확정.
- 알림함·내정보는 웹 이식이라 디자인·API 리스크 낮음(REST `NotificationV1Controller` 확인됨). 주 작업은 SwiftUI 뷰.
- ＋ 역지오코딩은 `CLGeocoder` 레이트리밋 → 지도 정지 디바운스 + 좌표 폴백 필수.
- iOS는 Windows에서 빌드 불가 → 정적 구현 후 Mac에서 시각·동작 검증(별도 DoD).

## 후속 (Future)
- 인스타 릴스 **Share Extension**(공유 → "어디가지")로 입력 간소화.
- 필요 시 1:1 채팅 재도입(백엔드 잔존분 활용).
