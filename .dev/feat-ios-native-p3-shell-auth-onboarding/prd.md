# PRD: P3 — iOS 골격 + 인증 + 온보딩

## 배경

WhereWeGo는 현재 Next.js 웹 앱으로만 서비스 중이며, 앱스토어 출시를 위해 SwiftUI 네이티브 iOS 앱으로 전환한다. P1(백엔드 인증 확장)이 완료된 상태로, P3는 iOS 앱의 기반 구조를 확립하고 사용자가 로그인부터 그룹 진입까지 완료할 수 있는 상태를 만드는 Phase다.

현재 상태:
- `ios/WhereWeGo/` 스캐폴드 디렉토리 존재. `Theme.swift`(디자인 토큰), `APIClient.swift`(Bearer+401 refresh actor, `TokenStore` 프로토콜 스켈레톤) 작성됨.
- `.xcodeproj` 없음. 폰트 파일 없음. Keychain 구현 없음.
- P1 백엔드에 `POST /api/v1/auth/kakao/native`, `POST /api/v1/auth/apple/native`, `POST /api/v1/auth/refresh` 엔드포인트 가동 중.
- 웹 온보딩 플로우(location → nickname → group-start → invite-code → notification → welcome 위저드)가 포팅 레퍼런스로 존재.

**사용자 발급물 보유 현황 (P3 착수 시점 기준):**

| 발급물 | 보유 여부 | P3 영향 |
|--------|----------|---------|
| Kakao Native App Key | 없음 | 실 카카오 로그인 검증 불가. 키 주입 후 동작하는 배선 구조만 완성 |
| Apple Developer 계정 / Bundle ID / capability | 없음 | 코드 서명 및 Sign in with Apple 런타임 실행 불가. 버튼 UI만 구현 |
| Mapbox secret 토큰(`~/.netrc`) | 없음 | Mapbox SDK P4로 연기 |
| Mapbox public 토큰(`pk.`) | 있음(`frontend/.env.local`) | xcconfig에 배선 구조 준비만. 실제 사용은 P4 |
| 폰트 파일 4종 | 없음 | `Resources/Fonts/` 구조 + UIAppFonts 배선 준비. 파일 확보 후 폰트 활성화 |

P3 완료 기준의 의미: P3에서 달성하는 것은 (a) 시뮬레이터 컴파일/빌드 성공, (b) UI·플로우·Keychain·라우트 가드 로직 완성, (c) 키/계정을 xcconfig·Info.plist에 주입하면 즉시 동작하는 배선 구조. 실기기 로그인 실행 검증은 키·계정 발급 후로 연기된다.

---

## 목표

- iOS 앱을 Xcode에서 빌드·시뮬레이터 실행할 수 있는 상태 확립 (XcodeGen `project.yml` 기반)
- 카카오 및 Apple 네이티브 로그인 플로우 배선 완성 — 키 주입 즉시 실동작 가능
- Keychain 기반 JWT 저장·복원 구현
- 웹과 동일한 온보딩 플로우(위치→닉네임→그룹시작→알림→welcome 2스텝 위저드)를 SwiftUI로 구현
- 인증·온보딩 완료 상태 기반 라우트 가드 구현
- P4(지도·핀) 착수를 위한 기반 확립

성공 지표:
- `xcodegen generate` 후 시뮬레이터 빌드 성공 및 앱 실행
- 시뮬레이터에서 전체 온보딩 플로우(닉네임·그룹시작·위치·알림·welcome 위저드) UI 확인
- 앱 재실행 시 Keychain 토큰 복원으로 로그인 상태 유지
- 카카오 실로그인 검증: Kakao Native Key 발급 후
- Apple 실로그인 검증: Apple Developer 계정·Bundle ID·capability 발급 후

---

## 범위

### In-scope

- XcodeGen `project.yml` 기반 Xcode 프로젝트 구성. 생성된 `.xcodeproj`는 `ios/.gitignore`에 추가.
- xcconfig: `API_BASE_URL` Debug/Release 분리, `KAKAO_NATIVE_APP_KEY` placeholder 변수 배선
- SPM 의존성: **Kakao iOS SDK**(`https://github.com/kakao/kakao-ios-sdk`). Apple은 내장 `AuthenticationServices`. Mapbox는 P4.
- `Resources/Fonts/` 디렉토리 구조 + `Info.plist UIAppFonts` 배열 준비 (파일은 발급물 확보 후 추가). `Theme.swift` PostScript 명 보정은 파일 확보 후.
- `KeychainTokenStore` 구현 (`TokenStore` 프로토콜 충족)
- `LoginView`: 카카오 + Apple 로그인 버튼, 오류 메시지, 브랜드 레이아웃
- 카카오 인증 플로우 배선: Kakao SDK → `POST /api/v1/auth/kakao/native` → JWT Keychain 저장
- Apple 인증 플로우 배선: `AuthenticationServices` → `POST /api/v1/auth/apple/native` → JWT Keychain 저장 (capability 없을 때 런타임 크래시 방어)
- Refresh 플로우: 401 → `POST /api/v1/auth/refresh` → 재시도, 실패 시 로그아웃
- 온보딩 View 6종: `LocationPermView`, `NicknameView`, `GroupStartView`, `InviteCodeView`, `NotificationView`, `WelcomeWizardView`(2스텝: 그룹 + 초대 링크)
- 라우트 가드: Keychain 토큰 + UserDefaults 플래그 기반 진입 제어
- `Info.plist` 권한 문구: `NSLocationWhenInUseUsageDescription`
- deployment target: **iOS 17**

### Out-of-scope

- Mapbox SDK SPM 등록 및 지도 렌더링 (P4 — secret 토큰 미보유)
- 지도·핀·사진·룰렛·방문감지 (P4)
- 채팅(봇 방·커플방)·APNs 푸시 UI (P5)
- Push Notifications capability, Associated Domains (P5)
- 계정 삭제 (P5 — Apple 토큰 revoke 포함)
- 디자인 정합성 최종 QA (P6)
- 앱 아이콘·스플래시·App Store Connect 등록 (P5/P6)
- Privacy Manifest (P5)
- Welcome 위저드 스텝 3 챗봇 연동 (iOS 앱에서 미사용, 컷오버 시 폐기)
- 그룹 생성·목록 실구현 (P4)

---

## 요구사항

### 기능 요구사항

**[Must] FR-1: XcodeGen 기반 Xcode 프로젝트 구성**
`ios/project.yml`을 작성하고 `xcodegen generate`로 `.xcodeproj`를 생성한다. 생성된 `.xcodeproj`는 `ios/.gitignore`에 추가하여 버전 관리에서 제외한다. 기존 스캐폴드 파일(`Theme.swift`, `APIClient.swift`)이 타깃에 포함되고 deployment target은 iOS 17로 설정한다. 팀원은 `xcodegen generate` 1회로 프로젝트를 재생성할 수 있다.

**[Must] FR-2: xcconfig 환경 분리**
`ios/Config/Debug.xcconfig`에 `API_BASE_URL = http://localhost:8080`, `ios/Config/Release.xcconfig`에 운영 URL을 설정한다. `KAKAO_NATIVE_APP_KEY`를 xcconfig 변수로 선언하고, 미설정 시 placeholder 값(`KAKAO_APP_KEY_NOT_SET`)으로 빌드가 통과되도록 한다. `APIClient`가 `API_BASE_URL`을 주입받아 초기화된다.

**[Must] FR-3: SPM 의존성 등록**
Kakao iOS SDK(`https://github.com/kakao/kakao-ios-sdk`, 최신 릴리즈)를 SPM으로 등록한다. Apple 로그인에는 내장 `AuthenticationServices` 프레임워크를 사용한다. Mapbox Maps iOS는 P4에서 추가한다.

**[Must] FR-4: 폰트 번들 구조 준비**
`ios/WhereWeGo/Resources/Fonts/` 디렉토리를 생성하고 `Info.plist`의 `UIAppFonts` 배열에 4종 파일명(Noto Serif KR, Gowun Batang, Pretendard, JetBrains Mono)의 placeholder를 등록한다. 실제 파일은 발급물 확보 후 추가하며, 파일 부재 시 시스템 폰트로 폴백하여 크래시 없이 동작한다. PostScript 명 보정은 파일 확보 후 수행한다.

**[Must] FR-5: Keychain TokenStore 구현**
`KeychainTokenStore`가 `TokenStore` 프로토콜을 충족한다. accessToken 읽기·저장·삭제, refreshToken 읽기·저장·삭제, `refresh()` 메서드(`POST /api/v1/auth/refresh` body 기반 호출 → 토큰 갱신, 실패 시 throw)를 구현한다. 앱 재시작 후에도 저장된 토큰이 Keychain에서 복원된다. `APIClient(baseURL:tokens:)`의 `tokens` 인자로 주입된다.

**[Must] FR-6: 로그인 화면 (LoginView)**
카카오 로그인 버튼(`WGColor.kakao` 배경 + `WGColor.kakaoInk` 텍스트)과 Apple 로그인 버튼을 표시한다. 웹 `LoginClient`의 브랜드 워드마크("우리가 갈 지도", `WGFont.emo`), 태그라인("우리의 장소를 지도 위에 아카이빙해요"), GlobeBg에 대응하는 배경 장식을 SwiftUI로 구현한다. 로그인 실패·취소 시 화면 내 오류 메시지를 표시한다. 로그인 진행 중 버튼을 비활성화한다.

**[Must] FR-7: 카카오 네이티브 로그인 배선**
Kakao iOS SDK로 로그인을 수행하고, 발급된 accessToken을 `POST /api/v1/auth/kakao/native`에 전달한다. 요청 body: `{ "kakaoAccessToken": "<token>" }`. 응답 `{ "accessToken": "...", "refreshToken": "...", "expiresIn": <seconds> }`를 Keychain에 저장한다. `KAKAO_NATIVE_APP_KEY`가 placeholder인 경우 Kakao SDK 초기화가 graceful하게 실패하고 앱이 크래시하지 않는다. 로그인 취소 시 LoginView 복귀 + 오류 메시지 표시.

**[Must] FR-8: Apple 네이티브 로그인 배선**
`ASAuthorizationAppleIDProvider`로 Sign in with Apple을 수행한다. 클라이언트가 cryptographically random rawNonce를 생성하고, `POST /api/v1/auth/apple/native`에 다음을 전달한다:

```
요청 body:
{
  "identityToken": "<Apple identityToken>",
  "nonce": "<rawNonce 평문>",          // 서버가 SHA-256 소문자 hex로 해시하여 claims.nonce와 비교
  "authorizationCode": "<code>",
  "fullName": { "givenName": "...", "familyName": "..." },  // 최초 1회만 Apple이 제공, 이후 null
  "email": "<email>"                   // 최초 1회만 Apple이 제공, 이후 null
}

응답:
{
  "accessToken": "...",
  "refreshToken": "...",
  "expiresIn": <seconds>
}
```

응답 JWT를 Keychain에 저장한다. Sign in with Apple capability 미설정 시 버튼 탭에서 런타임 크래시 없이 오류 처리한다 (`ASAuthorization` 오류를 catch하여 오류 메시지 표시). capability 발급 후 실동작.

**[Must] FR-9: 토큰 갱신 및 로그아웃**
`APIClient`의 401 → `tokens.refresh()` → 재시도 흐름이 `KeychainTokenStore`로 동작한다. `refresh()` 구현:
- `POST /api/v1/auth/refresh` body: `{ "refreshToken": "<token>" }`
- 응답 `{ "accessToken": "...", "refreshToken": "...", "expiresIn": <seconds> }` → Keychain 갱신
- 응답 401 또는 Keychain에 refreshToken 없음 → throw → `APIClient`가 로그아웃 트리거
- 로그아웃: Keychain accessToken + refreshToken 삭제 + 앱을 `LoginView`로 전환

**[Must] FR-10: 라우트 가드**
앱 진입(`@main`) 시 다음 순서로 초기 화면을 결정한다:
1. Keychain에 accessToken 없음 → `LoginView`
2. accessToken 있음 + UserDefaults `locationAsked = false` → `LocationPermView`
3. accessToken 있음 + `locationAsked = true` + `nicknameSet = false` → `NicknameView`
4. accessToken 있음 + 두 플래그 모두 true + `GET /api/v1/groups/me` → 그룹 없음(null) → `GroupStartView`
5. accessToken 있음 + 두 플래그 모두 true + 활성 그룹 있음 → `GroupsView`(stub)

단계 4의 API 호출 중 로딩 화면을 표시한다. 401 수신 시 FR-9 refresh 흐름을 거친다.

**[Must] FR-11: 위치 권한 요청 화면 (LocationPermView)**
아이콘(`cta` 색상 위치 아이콘), 제목("위치를 알려주세요"), 설명("근처에 어떤 핀이 있는지\n랜덤 뽑기에서 활용할 거예요"), "위치 사용 허용" / "나중에" 버튼을 표시한다. "위치 사용 허용" 탭 시 `CLLocationManager.requestWhenInUseAuthorization()` 호출. 어느 버튼을 눌러도 `locationAsked = true` UserDefaults 저장 후 다음 화면(NicknameView 또는 FR-10 분기 결과)으로 전환한다. 시스템이 이미 권한을 결정한 상태이면 prompt 없이 즉시 다음 화면으로 전환한다.

**[Must] FR-12: 닉네임 설정 화면 (NicknameView)**
제목("반가워요\n이름을 알려주세요", `WGFont.emo` 32pt), 부제("함께하는 사람에게 보여질 이름이에요"), 텍스트 필드(하단 `cta` 색 언더라인, `WGFont.emo` 24pt), 힌트("한글, 영문, 숫자 2~12자"), "다음" 버튼을 표시한다. 입력 규칙(BR-1)에 따라 실시간 sanitize. 유효하지 않으면 버튼 비활성화. `PATCH /api/v1/users/me` 또는 `PUT /api/v1/users/me/nickname`으로 저장. 성공 시 `nicknameSet = true` UserDefaults 저장 후 `GroupStartView`로 이동. API 실패 시 인라인 오류 메시지 표시("저장에 실패했어요. 잠시 후 다시 시도해 주세요").

**[Must] FR-13: 그룹 시작 화면 (GroupStartView)**
제목("어떻게 시작할까요"), 부제("혼자서도, 함께서도 괜찮아요"), "새 그룹 만들기" 카드 버튼(`cta` 테두리), "초대 코드로 합류" 카드 버튼(`hairline` 테두리)을 표시한다. "새 그룹 만들기" → `GroupCreateView`(stub, P3). "초대 코드로 합류" → `InviteCodeView`.

**[Must] FR-14: 초대 코드 입력 화면 (InviteCodeView)**
제목("초대 코드를 받았나요?"), 부제("친구가 보낸 링크의 코드를 입력해요"), 텍스트 필드(placeholder "초대 코드"). 공백 trim 후 길이 > 0일 때 "합류하기" 버튼 활성화. `POST /api/v1/groups/invite-links/{trimmed_token}/accept` 호출. 성공 시 `GroupsView`(stub)로 이동. 실패 시 인라인 오류 메시지("잘못된 코드이거나 만료되었어요"), 재입력 가능. "취소" 버튼으로 이전 화면 복귀.

**[Must] FR-15: 알림 권한 요청 화면 (NotificationView)**
아이콘(`cta` 색상 벨 아이콘), 제목("알림 받아볼래요?"), 설명("함께하는 사람이 핀을 추가하면\n알려드려요"), "알림 허용" / "다음에" 버튼을 표시한다. "알림 허용" 탭 시 `UNUserNotificationCenter.requestAuthorization(options: [.alert, .badge, .sound])` 호출. `UNAuthorizationStatus.denied` 상태에서 "알림 허용" 탭 시 `UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString))`. 어느 경우든 완료 후 다음 화면으로 이동.

**[Must] FR-16: Welcome 위저드 — 2스텝 (WelcomeWizardView)**
스텝 1(그룹): "함께 갈 곳을 모아봐요" — "새 그룹 만들기" / "초대 코드로 합류" / "다음에 할게요". 스텝 2(초대 링크): "짝꿍에게 링크를 보내요" — 초대 링크 자동 발급·표시·복사 / "다음 단계" / "다음에 할게요". 상단에 2/2 진행 인디케이터 표시. 각 스텝에 건너뛰기 허용. 마지막 스텝 완료/건너뛰기 시 `GroupsView`(stub)로 이동. 이미 활성 그룹이 있으면 스텝 1 자동 스킵하여 스텝 2부터 표시. 챗봇 연동 스텝(구 스텝 3)은 포함하지 않는다.

**[Should] FR-17: GroupsView stub**
"그룹 화면 — P4에서 구현" 텍스트를 표시하는 빈 화면. 라우트 가드의 최종 목적지로서 네비게이션이 연결됨을 확인할 수 있어야 한다.

**[Should] FR-18: GroupCreateView stub**
"그룹 생성 — P4에서 구현" 텍스트를 표시하는 빈 화면. GroupStartView 및 WelcomeWizardView 스텝 1에서 진입 가능해야 한다.

---

### 비즈니스 규칙

**[Must] BR-1: 닉네임 규칙**
한글/영문/숫자만 허용, 최소 2자, 최대 12자. 웹 `validateNickname`/`sanitizeNickname` 로직과 동일. 허용 문자 외 입력은 즉시 제거(실시간 sanitize). 2자 미만이거나 허용 문자 외 입력만 있는 경우 저장 버튼 비활성화.

**[Must] BR-2: Apple nonce 계약**
클라이언트가 생성한 rawNonce를 평문으로 `nonce` 필드에 전달한다. 서버가 SHA-256 소문자 hex로 해시하여 identityToken의 nonce 클레임과 비교한다. 클라이언트는 해시를 수행하지 않는다. nonce 불일치 시 서버 401 → 로그인 실패 처리.

**[Must] BR-3: 토큰 저장 위치**
accessToken과 refreshToken은 iOS Keychain에 저장한다. UserDefaults에 저장하지 않는다.

**[Must] BR-4: 온보딩 플래그 UserDefaults 키**
웹 localStorage 키 대응: `maygo:location-asked` → UserDefaults key `locationAsked`, `maygo:nickname-set` → `nicknameSet`. 앱 재설치 시 UserDefaults가 초기화되어 온보딩이 재진행될 수 있으며 이는 허용된다.

**[Must] BR-5: refresh 토큰 만료 처리**
`POST /api/v1/auth/refresh` 응답 401 또는 Keychain에 refreshToken 없음 → Keychain의 accessToken과 refreshToken 모두 삭제 → `LoginView`로 전환.

**[Must] BR-6: 위치 권한 화면 재진입 없음**
`locationAsked = true`이면 앱 진입 시 `LocationPermView`를 다시 표시하지 않는다. 시스템 권한 상태(거부 포함)와 무관하게 플래그 기준으로 판단한다.

**[Must] BR-7: 카카오 로그인 취소**
사용자가 카카오 로그인 화면에서 취소하면 `LoginView`로 복귀하고 오류 메시지("로그인에 실패했어요. 다시 시도해 주세요")를 표시한다.

**[Must] BR-8: 초대 코드 진입 전제**
초대 코드 합류 API는 인증된 사용자만 호출 가능하다. `InviteCodeView`는 로그인 후에만 진입 가능하다(라우트 가드가 보장).

**[Must] BR-9: Welcome 위저드 챗봇 스텝 제거**
카카오 챗봇 연동(구 스텝 3)은 iOS 앱에서 제공하지 않는다. 위저드는 2스텝(그룹 + 초대 링크)으로 구성한다.

**[Should] BR-10: 알림 권한 거부 시 시스템 설정 유도**
`UNAuthorizationStatus.denied` 상태에서 "알림 허용"을 탭하면 `UIApplication.openSettingsURLString`으로 시스템 설정 앱의 앱 권한 화면을 연다.

---

### 품질 기대

**[Should] QE-1: 폰트 파일 부재 시 크래시 없음**
`UIAppFonts`에 등록된 폰트 파일이 존재하지 않을 때 앱이 크래시하지 않고 시스템 폰트로 폴백한다. P3 빌드에서 파일 부재 상태를 기본으로 가정한다.

**[Should] QE-2: 로그인 중복 탭 방지**
카카오/Apple 로그인 진행 중 버튼이 비활성화되어 중복 탭이 방지된다.

**[Should] QE-3: Kakao Key placeholder 빌드 안전성**
`KAKAO_NATIVE_APP_KEY`가 placeholder(`KAKAO_APP_KEY_NOT_SET`)인 상태에서 Kakao SDK 초기화가 graceful하게 실패하고, 로그인 버튼 탭 시 크래시 없이 오류 메시지를 표시한다.

**[Should] QE-4: Apple capability 미설정 빌드 안전성**
Sign in with Apple capability가 없는 상태에서 앱이 빌드되고 실행된다. Apple 로그인 버튼 탭 시 `ASAuthorizationError`를 catch하여 오류 메시지를 표시하고 크래시하지 않는다.

---

## 사용자 시나리오

### 정상 흐름 A: 신규 사용자 카카오 로그인 (키 발급 후)
1. 앱 최초 실행 → `LoginView`
2. "카카오로 시작하기" 탭 → Kakao SDK 로그인 화면
3. 카카오 인증 완료 → `POST /api/v1/auth/kakao/native` → JWT 수신 → Keychain 저장
4. `locationAsked = false` → `LocationPermView`
5. "위치 사용 허용" → `CLLocationManager.requestWhenInUseAuthorization()` → `locationAsked = true`
6. `nicknameSet = false` → `NicknameView`
7. 닉네임 입력(2~12자, 한글/영문/숫자) → "다음" → API 저장 → `nicknameSet = true`
8. `GroupStartView` → "새 그룹 만들기" → `GroupCreateView`(stub)
9. 그룹 생성 완료(P4 이후) → `WelcomeWizardView` 스텝 1 → 스텝 2 → `GroupsView`

### 정상 흐름 B: 초대 코드로 합류
1. 로그인 → 위치 → 닉네임 → `GroupStartView`
2. "초대 코드로 합류" → `InviteCodeView`
3. 코드 입력 → "합류하기" → `POST /api/v1/groups/invite-links/{token}/accept` → `GroupsView`

### 정상 흐름 C: 기존 사용자 재진입
1. 앱 실행 → Keychain에 유효 accessToken
2. `locationAsked = true`, `nicknameSet = true`
3. `GET /api/v1/groups/me` → 그룹 있음 → `GroupsView` 직행

### 예외 흐름 D: API 호출 중 accessToken 만료
1. API 호출 → 401 수신
2. `KeychainTokenStore.refresh()` → `POST /api/v1/auth/refresh` → 새 JWT Keychain 갱신
3. 원래 API 재시도 → 성공

### 예외 흐름 E: refreshToken도 만료
1. `POST /api/v1/auth/refresh` → 401
2. Keychain 토큰 전부 삭제 → `LoginView` 전환

### 엣지 케이스
- **닉네임 1자 입력**: 저장 버튼 비활성화, API 미호출
- **닉네임 특수문자 입력**: 실시간 제거(sanitize), 필드에 반영됨
- **위치 권한 "나중에" 선택**: `locationAsked = true` 저장, 다음 진입 시 위치 화면 미표시
- **초대 코드 만료/오류**: "잘못된 코드이거나 만료되었어요" 표시, 재입력 가능
- **카카오 로그인 취소**: `LoginView` 복귀 + 오류 메시지, 버튼 재활성화
- **Apple 로그인 재시도(2회 이후)**: fullName/email이 nil로 전달됨, 서버가 최초 저장값 유지
- **Welcome 위저드에서 이미 그룹 보유 사용자 진입**: 스텝 1 자동 스킵, 스텝 2(초대 링크)부터 시작
- **초대 링크 발급 API 오류(스텝 2)**: "초대 링크를 만들지 못했어요" 오류 표시, "다음에 할게요"로 건너뛰기 가능
- **앱 재설치 후 진입**: UserDefaults 초기화로 위치·닉네임 화면 재진행 (Keychain 토큰은 재설치 후에도 유지될 수 있음 — OS 버전에 따라 다름)
- **Kakao Key placeholder 상태에서 로그인 시도**: 오류 메시지 표시, 크래시 없음
- **Apple capability 미설정 상태에서 Apple 로그인 시도**: ASAuthorizationError catch → 오류 메시지, 크래시 없음

---

## 영향 범위

- **백엔드**: P1 완료 전제. `POST /api/v1/auth/kakao/native`, `POST /api/v1/auth/apple/native`, `POST /api/v1/auth/refresh` 엔드포인트 소비. 기존 웹 쿠키 인증 무영향.
- **웹**: 변경 없음.
- **기존 사용자**: iOS 앱은 별도 배포 채널(앱스토어)이므로 기존 웹 사용자에게 영향 없음.
- **github CI**: `ios/` 경로 변경은 `backend/**` 필터 기반 deploy.yml을 트리거하지 않음. 빌드 안전.

---

## 수용 기준

P3에서 PASS 판정 가능한 AC(시뮬레이터 기준)와 발급물 확보 후 검증 대상 AC를 구분한다.

### P3 내 PASS 가능 (시뮬레이터/빌드 기준)

| # | 수용 기준 | 연결 |
|---|----------|------|
| AC-1 | `ios/project.yml`이 존재하고 `xcodegen generate` 실행 시 오류 없이 `.xcodeproj`가 생성됨. 시뮬레이터 빌드 성공. | FR-1 |
| AC-2 | `Debug.xcconfig`에 `API_BASE_URL`과 `KAKAO_NATIVE_APP_KEY` 변수가 존재. `APIClient`가 `API_BASE_URL`을 baseURL로 사용함. | FR-2 |
| AC-3 | Kakao iOS SDK가 SPM 의존성으로 등록됨. `AuthenticationServices`가 링크됨. `import KakaoSDKAuth` 컴파일 성공. | FR-3 |
| AC-4 | `ios/WhereWeGo/Resources/Fonts/` 디렉토리 존재. `Info.plist UIAppFonts` 배열에 4종 파일명 항목 등록. 폰트 파일 없는 상태에서 앱 실행 시 시스템 폰트로 폴백하고 크래시 없음. | FR-4, QE-1 |
| AC-5 | `KeychainTokenStore`가 `TokenStore` 프로토콜을 준수함. 시뮬레이터에서 accessToken 저장 → 앱 재시작 → accessToken 복원 동작 확인. | FR-5 |
| AC-6-배선 | 카카오 로그인 성공 시 `POST /api/v1/auth/kakao/native` 엔드포인트를 호출하는 코드 경로가 완성되어 있음. `KAKAO_NATIVE_APP_KEY` placeholder 상태에서 버튼 탭 시 오류 메시지 표시, 크래시 없음. | FR-7, QE-3 |
| AC-7 | 카카오 로그인 취소 시 `LoginView`로 복귀하고 오류 메시지 표시. | FR-7, BR-7 |
| AC-8-배선 | Apple 로그인 시 rawNonce 생성 → `identityToken` + `nonce(평문)` + `authorizationCode` + `fullName` + `email`을 `POST /api/v1/auth/apple/native`에 전달하는 코드 경로가 완성되어 있음. capability 미설정 상태에서 버튼 탭 시 `ASAuthorizationError` catch → 오류 메시지, 크래시 없음. | FR-8, QE-4 |
| AC-9 | 401 수신 시 `POST /api/v1/auth/refresh` body `{ "refreshToken": "..." }` 호출 후 원래 요청 1회 재시도하는 코드 경로 확인. | FR-9 |
| AC-10 | refreshToken 만료(`refresh()` throw) 시 Keychain 전체 삭제 후 `LoginView`로 전환하는 코드 경로 확인. | FR-9, BR-5 |
| AC-11 | `locationAsked = false` 상태로 앱 진입 시 `LocationPermView` 표시. | FR-10, FR-11 |
| AC-12 | `locationAsked = true`, `nicknameSet = false` 상태로 앱 진입 시 `NicknameView` 표시. | FR-10, FR-12 |
| AC-13 | 닉네임 1자 또는 허용 문자 외 입력만 있을 때 저장 버튼 비활성화. 특수문자 입력 즉시 필드에서 제거. | FR-12, BR-1 |
| AC-14 | 닉네임 저장 API 성공 응답 후 `nicknameSet = true` 저장 및 `GroupStartView`로 이동. API 실패 시 인라인 오류 메시지 표시. | FR-12 |
| AC-15 | `GroupStartView`에서 "초대 코드로 합류" 탭 시 `InviteCodeView`로 이동. | FR-13, FR-14 |
| AC-16 | `InviteCodeView`에서 빈 코드로 "합류하기" 버튼 비활성화. 서버 오류 응답 시 "잘못된 코드이거나 만료되었어요" 표시. | FR-14 |
| AC-17 | `NotificationView`에서 "알림 허용" / "다음에" 어느 버튼을 눌러도 다음 화면으로 이동. | FR-15 |
| AC-18 | `WelcomeWizardView`가 2스텝(그룹, 초대링크)으로 구성됨. 챗봇 스텝 없음. 상단에 2/2 진행 인디케이터 표시. 각 스텝 "다음에 할게요" 건너뛰기 후 마지막 스텝 완료 시 `GroupsView`로 이동. | FR-16, BR-9 |
| AC-19 | 활성 그룹이 있는 사용자가 `WelcomeWizardView`에 진입하면 스텝 1 자동 스킵하여 스텝 2 표시. | FR-16 |
| AC-20 | Keychain에 accessToken 없는 상태로 앱 진입 시 `LoginView` 표시. | FR-10 |
| AC-21 | Keychain 토큰 있음 + `locationAsked = true` + `nicknameSet = true` + 활성 그룹 있음 상태로 앱 진입 시 `GroupsView` 표시. | FR-10, FR-17 |

### 발급물 확보 후 검증 대상

| # | 수용 기준 | 전제 조건 |
|---|----------|---------|
| AC-6-실행 | 실기기에서 카카오 로그인 완료 → JWT Keychain 저장 → 그룹 화면까지 동작 확인. | Kakao Native App Key 발급 + iOS 플랫폼 등록 |
| AC-8-실행 | 실기기에서 Apple 로그인 완료 → JWT Keychain 저장 → 그룹 화면까지 동작 확인. | Apple Developer 계정 + Bundle ID + Sign in with Apple capability |
| AC-22 | 4종 커스텀 폰트가 앱 내에서 렌더링됨 (`WGFont.emo`, `WGFont.sans`, `WGFont.serif`, `WGFont.mono` 정상 표시). | 폰트 파일 4종 `Resources/Fonts/` 추가 + PostScript 명 보정 |

---

## 의존성 및 전제

### 코드 의존성
- **P1 완료 필수**: `POST /api/v1/auth/kakao/native`, `POST /api/v1/auth/apple/native`, `POST /api/v1/auth/refresh` 엔드포인트 가동 중.
- `APIClient.swift`의 `TokenStore` 프로토콜 — `KeychainTokenStore` 구현의 계약 기준.
- deployment target: **iOS 17** (`NavigationStack`, `async/await` 제약 없음).

### 환경 전제
- macOS + Xcode 26.5, Swift 6.3.2
- `/opt/homebrew/bin/xcodegen` 설치 확인됨
- `ios/project.yml`을 버전 관리. `.xcodeproj`는 `ios/.gitignore`에 추가하여 재생성 방식으로 운영.

### 사용자 발급물 의존성 요약

| 발급물 | P3 내 처리 | 실검증 시점 |
|--------|-----------|-----------|
| Kakao Native App Key | xcconfig placeholder로 배선 구조 완성 | 키 발급 후 즉시 |
| Kakao iOS 플랫폼 + URL Scheme | Info.plist URL Scheme 구조 준비 | 키 발급 시 함께 |
| Apple Developer 계정 + Bundle ID + capability | 배선 코드 완성, 오류 catch 방어 | 계정 발급 후 |
| Mapbox secret 토큰 | P4로 연기 | P4 착수 시 |
| 폰트 파일 4종 | 디렉토리·UIAppFonts 배열 구조 준비 | 파일 확보 후 추가 |

---

## 리스크

| 리스크 | 발생 가능성 | 영향 | 대응 |
|--------|-----------|------|------|
| Kakao Native Key 발급 지연으로 실로그인 검증 지연 | 높음 | AC-6-실행 미달성, P3 실검증 연기 | P3에서 배선 완성 → 키 발급 즉시 검증으로 분리 |
| Apple Developer 계정 미가입 또는 심사 지연(최대 수일) | 중간 | Apple 로그인 실기 검증 불가 | P3 완료 기준에서 분리, 별도 "발급 후 검증" 항목으로 추적 |
| `project.yml` 작성 오류로 빌드 실패 | 낮음 | P3 착수 즉시 막힘 | `xcodegen` 생성 후 시뮬레이터 빌드로 사전 검증 후 commit |
| Kakao SDK 버전 Swift 6 호환성 문제 | 낮음 | SPM 빌드 오류 | 릴리즈 노트에서 Swift 6/Xcode 26 호환 버전 확인 후 고정 |
| Apple 로그인 nonce 계약 불일치 | 낮음 | 로그인 401 실패 | rawNonce 평문 전달(서버가 SHA-256 비교) 계약 준수 — P1 design.md 명세 기준 |
| 폰트 PostScript 명 불일치 | 낮음 | 커스텀 폰트 미렌더링(AC-22 미달) | 파일 확보 후 Font Book에서 PostScript 명 확인 후 `Theme.swift` 보정 |
| 앱 재설치 후 Keychain 토큰 유지 | 낮음 | 재설치 후 자동 로그인 — UX 혼란 가능 | 재설치 감지(첫 실행 플래그) 또는 Keychain 정책은 P4 이후 검토 |

---

## 탐색 추가 항목

- `ios/WhereWeGo/Core/Networking/APIClient.swift` → `TokenStore` 프로토콜 정의. `KeychainTokenStore`가 이 프로토콜을 충족해야 함. `actor APIClient` 구조로 `tokens: TokenStore` 주입.
- `ios/WhereWeGo/Core/DesignSystem/Theme.swift` → `WGColor`/`WGFont` 토큰. 폰트 PostScript 명 보정 필요(파일 확보 후).
- `frontend/src/lib/validation/nickname.ts` → 닉네임 규칙 원천(한글/영문/숫자, 2~12자, sanitize 로직). Swift 이식 기준.
- `frontend/src/lib/storage/local-flags.ts` → UserDefaults 키 대응 원천: `maygo:location-asked`, `maygo:nickname-set`, `maygo:notif-asked`.
- `frontend/src/app/login/callback/page.tsx` → 로그인 후 라우트 분기 로직 원천. locationAsked → nicknameSet → 그룹 유무 순서 확인.
- `frontend/src/app/onboarding/welcome/WelcomeWizardClient.tsx` → 위저드 자동 스텝 스킵 로직. 그룹 보유·멤버 수 조건 기반 분기.
- `.dev/feat-ios-native-p1-auth/design.md` → Apple nonce 계약(rawNonce 평문 전달, 서버 SHA-256 hex 비교), TokenResponse 스키마, refresh body 계약.
- `context/auth/architecture.md` → AT 1h / RT 14d TTL, refresh 실패 정책.
