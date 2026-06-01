## 코드 맵: P3 — iOS 골격 + 인증 + 온보딩

### 핵심 파일
- ios/WhereWeGo/Core/Networking/APIClient.swift:25 → Bearer 자동부착 + 401→refresh→1회재시도 actor. `TokenStore` 프로토콜(P3에서 Keychain 구현으로 교체) 정의
- ios/WhereWeGo/Core/DesignSystem/Theme.swift:31 → 디자인 토큰 WGColor/WGFont 1:1 이식. 폰트 파일 번들 + Info.plist UIAppFonts 등록 필요
- ios/README.md → Xcode 프로젝트 생성·SPM(Mapbox/Kakao)·폰트·xcconfig(API_BASE_URL)·entitlements 절차
- .dev/feat-ios-native-swiftui/roadmap.md:37 → P3 범위/완료기준/의존성(P1). 열린 결정: XcodeGen/Tuist
- .dev/feat-ios-native-swiftui/prerequisites.md:107 → P3 사용자 발급물(§7): Mac+Xcode, Kakao iOS 플랫폼+Native key+URL scheme, Sign in with Apple capability, Mapbox secret(.netrc), 폰트

### 참조 파일
- .dev/feat-ios-native-p1-auth/design.md → P1 인증 API 계약(iOS 소비): POST /auth/kakao/native, /auth/apple/native, /auth/refresh. 응답 {accessToken,refreshToken,expiresIn}
- context/auth/architecture.md:22 → JWT Bearer/쿠키 병행, AT 1h/RT 14d TTL, refresh body 계약
- frontend/src/app/login/LoginClient.tsx → 로그인 화면 포팅 레퍼런스(Kakao+Apple)
- frontend/src/app/onboarding/welcome/WelcomeWizardClient.tsx → 온보딩 위저드(welcome) 플로우 레퍼런스
- frontend/src/app/onboarding/nickname/NicknameClient.tsx → 닉네임 설정 단계
- frontend/src/app/onboarding/invite-code/InviteCodeClient.tsx → 초대코드 입력 단계
- frontend/src/app/onboarding/group-start/GroupStartClient.tsx → 그룹 시작 단계(라우트 가드 종착)
- frontend/src/lib/validation/nickname.ts → 닉네임 규칙 원천(한글/영문/숫자, 2~12자) Swift 이식 기준
- frontend/src/lib/storage/local-flags.ts → UserDefaults 키 대응 원천(maygo:location-asked/nickname-set/notif-asked)
- frontend/src/app/login/callback/page.tsx → 라우트 가드 분기 로직 원천(105-133행: 그룹유무→nicknameSet→location 순)
- backend/.../auth/AuthV1Controller.java:63,72,81 → kakao/native·apple/native·refresh, 모두 ApiResponse<TokenResponse>
- backend/.../auth/AuthV1Dto.java:21-43 → AppleNativeLoginRequest + TokenResponse(accessToken,refreshToken,expiresIn:long)
- backend/.../user/UserV1Controller.java:30 → 닉네임 저장 **PUT /api/v1/users/me** {nickname}→UserResponse (PATCH 아님!)
- backend/.../user/UserV1Dto.java:10-15 → @Pattern(^[가-힣a-zA-Z0-9]+$) @Size(2,12) — 웹/iOS 닉네임 규칙 일치
- backend/.../group/GroupV1Controller.java:38,51,82 → invite-links 발급(POST /groups/{groupId}/invite-links 201)·수락(POST /groups/invite-links/{token}/accept)·활성그룹(GET /groups/me, 없으면 data=null)
- backend/.../group/GroupV1Dto.java:32,73 → InviteLinkResponse{token,slug,expiresAt,shareUrl}, ActiveGroupResponse{groupId,name,createdAt,memberCount}
- frontend/.../welcome/_steps/Step2Invite.tsx:47-79 → 초대 링크 자동 발급 + shareUrl 폴백 패턴

### 설정
- ios/.gitignore → iOS/Xcode 빌드 아티팩트 무시(스캐폴드)
- ios/WhereWeGo/Resources/Fonts/.gitkeep → 폰트 번들 위치(Noto Serif KR/Gowun Batang/Pretendard/JetBrains Mono)
- .github/workflows/deploy.yml → paths: backend/** 필터(ios/ 변경은 백엔드 배포 무관)

### 환경 사실 (setup 수집)
- Xcode 26.5 / Swift 6.3.2 / xcodegen 설치됨(/opt/homebrew/bin/xcodegen) / tuist 없음
- 프로젝트 타입(config): java-spring+node. **iOS(Swift) 빌드 타입은 config 미정의** → mechanical gate는 xcodebuild/xcodegen 기반 커스텀 필요
- frontend 온보딩 6단계 라우트 전부 확인(welcome/nickname/location/invite-code/notification/group-start)
