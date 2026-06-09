# 자기점검 — Share Extension 구현

## 변경/신규
**메인 앱 수정(최소)**: AppConfig(appGroupIdentifier 상수) · KeychainTokenStore(accessGroup 옵션 파라미터+baseQuery) · AppDependencies(accessGroup 주입) · WhereWeGo.entitlements(App Group) · project.yml(ShareExtension 타겟+embed, WhereWeGoTests에 Logic 포함).
**신규 익스텐션(ios/ShareExtension/)**: Logic/ShareDTO·ShareKeychain·ShareAPIClient·ShareViewModel + ShareRootView + ShareViewController + Info(xcodegen 생성) + entitlements.
**테스트**: WhereWeGoTests/ShareViewModelTests(6 케이스).

## 정적 검증 (Windows — iOS 빌드 불가, CI 위임)
- ✅ 자족 구조: 익스텐션이 앱 모듈 미import. Logic은 Foundation/SwiftUI/Security만 의존 → 테스트 타깃 dual-membership 컴파일 가능.
- ✅ 토큰 공유: 메인 앱 KeychainTokenStore(accessGroup=group.com.wherewego.app)와 익스텐션 ShareKeychain(동일 service+accessGroup) 일치. 미출시→마이그레이션 불요.
- ✅ 백엔드 0: 기존 GET /chat/bot/rooms + POST /chat/bot/{groupId}/messages 재사용.
- ✅ 번들 ID: com.wherewego.app.ShareExtension(앱 com.wherewego.app의 하위 — embed 유효).
- ✅ CI 안전: CODE_SIGNING_ALLOWED=NO → App Group 엔타이틀먼트 미강제. KeychainTokenStoreTests는 이미 -skip-testing(내 access group 변경 무영향).
- ✅ 다중전송: send()가 선택 그룹마다 sendBotMessage 호출(withTaskGroup), 모두 완료까지 대기 후 .result(D1). 부분 실패 그룹명 집계.
- ✅ 빈 선택 기본(D2): selected 초기 빈 Set, canSend는 ≥1 선택.
- ✅ State Equatable(연관값 모두 Equatable) → 테스트 비교 가능.

## CI에서 검증될 리스크 (push 후 watch)
- ⚠️ Swift 6 Sendable: ShareAPIClient(final class, 불변 Sendable 프로퍼티)가 Sendable 프로토콜 충족하는지. 실패 시 `@unchecked Sendable` 보강.
- ⚠️ NSItemProvider.loadItem(forTypeIdentifier:options:) async 시그니처 + `as? URL` 브리징.
- ⚠️ XcodeGen app-extension 타겟 생성 + embed + Info.plist NSExtension 생성 정합.
- → 메모리 교훈대로 iOS 컴파일 결손은 CI에서 순차 노출 → push 후 `gh run watch` 필수.

## 빌드 게이트
- iOS Windows 빌드 불가 → GitHub Actions(macOS 시뮬, WhereWeGo 스킴이 ShareExtension 의존성으로 함께 빌드)가 검증.
- **실제 인스타 공유 동선·App Group provisioning은 시뮬 불가 → 실기기(Mac DoD-B)**.

## AC 커버리지
AC1 활성화규칙(Info.plist) / AC2 체크박스·빈선택(VM·View) / AC3 다중 sendBotMessage(테스트 검증) / AC4 전송완료 대기·로그인 안내 / AC5 부분실패 집계(테스트) / AC6 access group(메인 앱) / AC7 CI+ShareViewModelTests.
