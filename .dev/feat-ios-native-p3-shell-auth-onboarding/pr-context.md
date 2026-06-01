# PR 컨텍스트 — P3 iOS 골격 + 인증 + 온보딩

## 비즈니스 맥락

WhereWeGo를 앱스토어 출시용 SwiftUI 네이티브 iOS 앱으로 전환하는 첫 클라이언트 Phase(P3). P1(백엔드 인증 확장: Bearer/Kakao·Apple native/refresh) 완료 위에서, iOS 앱의 기반 구조를 확립하고 **로그인 → 온보딩 → 그룹 진입**까지 네이티브로 동작시킨다.

P3 완료 기준의 성격: 사용자 발급물(Kakao Native Key·Apple Developer 계정·폰트·Mapbox secret) 미보유 상태이므로, 달성 대상은 **(a) 시뮬레이터 빌드 성공, (b) UI·플로우·Keychain·라우트가드 로직 완성, (c) 키 주입 시 즉시 동작하는 배선 구조**다. 실기기 로그인 실행·폰트 렌더링은 발급물 확보 후로 분리.

## 주요 변경

- **XcodeGen 프로젝트 구성**: `project.yml`(iOS 17 타깃), Kakao iOS SDK(SPM exactVersion), xcconfig(API_BASE_URL Debug/Release, KAKAO_NATIVE_APP_KEY placeholder), Info.plist(UIAppFonts 4종, URL Scheme, 위치 권한). 생성 `.xcodeproj`는 gitignore.
- **인증 코어**: `KeychainTokenStore`(actor, SecItem, 401→refresh→재시도, inFlight 직렬화, 네트워크오류 토큰 보존), 카카오(앱 우선/계정 폴백)·Apple(rawNonce SHA256, capability 방어) 네이티브 로그인. P1 엔드포인트(`/auth/kakao|apple/native`, `/auth/refresh`) 소비. Apple nonce 평문 전달 계약(BR-2) 준수.
- **온보딩 + 라우트 가드**: RootView/OnboardingRouter 상태머신(토큰→locationAsked→nicknameSet→groups/me→groupStart/groups), 온보딩 6화면(위치/닉네임/그룹시작/초대코드/알림/Welcome 위저드 2스텝, 챗봇 스텝 제거), GroupsView/GroupCreateView stub(P4).
- **빌드 안전성(키 미보유 방어)**: Kakao placeholder→SDK init 생략·graceful 실패, Apple capability 없음→ASAuthorizationError catch, 폰트 부재→시스템 폰트 폴백, groups/me data null→nil 정규화.
- **테스트**: XCTest 60종(Nickname/Nonce/Keychain/AppConfig/ActiveGroupDecoding/RouteGuard + 네트워크오류·inFlight). xcodebuild 시뮬레이터(iOS 26.5) BUILD SUCCEEDED + TEST SUCCEEDED.

## 영향 범위

`ios/` 단독. backend/frontend 무변경. `.github/workflows/deploy.yml`(paths: backend/**) 미트리거 — 백엔드 배포 영향 없음. 기존 웹 쿠키 인증과 별개 엔드포인트 소비.

## Audit Summary

- 총 14건 (CRITICAL: 0, HIGH: 4→전부 수정, MEDIUM: 8, LOW: 2)
- [HIGH 수정완료] SecItemAdd 반환값 검증(토큰 저장 실패 무음 방지), NonceGenerator fatalError 제거(크래시 방지), LogoutHandlerBox로 logout 전파 경쟁 해소, inFlight 직렬화 테스트 추가
- [MEDIUM 수정완료] performRefresh 네트워크오류 시 토큰 보존(과잉 로그아웃 방지)
- [이월] kSecAttrAccessible 상향(P4), 카카오 취소 케이스 실기검증, 재설치 Keychain 잔존(P4), 접근성(P6), APIError 강타입, ATS/HTTPS·Release 키주입 정책 — 자세한 내용은 trust-ledger.md 참조

## 인수 검증

ACCEPT — [Must] 수용 기준 AC-1~21 전부 충족. AC-6실행/AC-8실행/AC-22는 발급물(키·계정·폰트) 확보 후 검증 예정.

## 후속 (P4 이월)

- 기존 사용자(활성 그룹 보유) 재진입 시 Welcome 위저드 경유 → `wizardShown` 플래그로 GroupsView 직행 검토
- Mapbox SDK SPM 추가(secret 토큰 발급 후), 지도·핀·사진·방문감지
