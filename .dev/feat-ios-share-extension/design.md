# 설계 — 인스타 공유 → 우리 앱 → 그룹 DM 다중선택 전송 (iOS Share Extension)

설계 규모: **대형** (신규 app-extension 타겟 + 엔타이틀먼트/프로젝트 변경 + 메인 앱 토큰 저장 access group 추가).

## §0 핵심 전략
- **익스텐션 자족(self-contained)**: 메인 앱(WhereWeGo) 모듈을 import하지 않는다. 자체 경량 키체인 리더 + 네트워킹 + 최소 DTO를 둔다 → 메인 앱 결합/회귀 최소화, 익스텐션 메모리(~120MB) 절약.
- **토큰 공유 = App Group 겸용 키체인 access group**: `group.com.wherewego.app`을 App Group으로 두 타겟에 부여하고, 그 ID를 `kSecAttrAccessGroup`으로 사용(App Group ID는 팀ID 프리픽스 없이 keychain access group으로 유효 — 검증된 패턴).
- 백엔드 변경 0. 기존 `GET /chat/bot/rooms` + `POST /chat/bot/{groupId}/messages` 재사용.

## §1 변경/신규 범위

### 메인 앱 수정 (최소)
1. **ios/WhereWeGo/Core/Keychain/KeychainTokenStore.swift** — `baseQuery`에 `kSecAttrAccessGroup = AppGroup.identifier` 추가(저장/조회/삭제 공통). 미출시라 토큰 마이그레이션 불요.
2. **ios/WhereWeGo/WhereWeGo.entitlements** — `com.apple.security.application-groups`에 `group.com.wherewego.app` 추가.
3. **ios/project.yml** — `ShareExtension` 타겟 신규(type: app-extension) + 메인 앱 `dependencies`에 익스텐션 embed + 익스텐션 entitlements/Info 배선. 공유 상수(AppGroup id)용.

### 신규 — Share Extension 타겟 (ios/ShareExtension/)
4. **ShareViewController.swift** — `UIViewController`. `extensionContext.inputItems`에서 공유 URL 추출(public.url, 실패 시 public.text에서 URL 파싱) → `UIHostingController(ShareRootView)` 표시. 완료/취소 시 `extensionContext.completeRequest`/`cancelRequest`.
5. **ShareRootView.swift** — SwiftUI. 상태별 화면(로딩/그룹 체크박스 목록/빈 그룹/로그인 필요/전송 중/결과). 디자인 토큰은 익스텐션 자체 최소 색/폰트(앱 토큰 미import — 단순 팔레트 복제).
6. **ShareViewModel.swift** — `@MainActor ObservableObject`. `load()`(botRooms), `toggle(groupId)`, `send(url:)`(선택 그룹 동시 전송→결과 집계), 상태 enum.
7. **ShareAPIClient.swift** — 경량 네트워킹: `botRooms()` GET, `sendBotMessage(groupId,text)` POST. Bearer(공유 키체인 access token) 부착, 401 시 `refresh()` 1회 후 재시도.
8. **ShareKeychain.swift** — 공유 access group에서 accessToken/refreshToken read + refresh 후 write. `service="com.wherewego.tokens"`, `accessGroup=group.com.wherewego.app`(메인 앱과 동일).
9. **ShareDTO.swift** — 최소 Decodable: `ShareGroup{groupId,name,memberCount}`(botRooms 항목), `Envelope<T>`, `TokenResponse`, 전송 응답. (앱 모델 미공유 — 의도적 디커플)
10. **Info.plist** — `NSExtension`(point=com.apple.share-services, principal=ShareViewController, ActivationRule: `SUBQUERY(extensionItems,...) URL 1건`).
11. **ShareExtension.entitlements** — App Group `group.com.wherewego.app`.

### 신규 — 테스트
12. **ios/WhereWeGoTests/ShareViewModelTests.swift** — 선택 검증(0개→전송 불가), 다중 전송 호출 수(=선택 수), 부분 실패 집계, 토큰 없음→로그인 필요 상태. (Stub ShareAPIClient 프로토콜)

## §2 타겟/엔타이틀먼트 구성 (project.yml)
- `ShareExtension`: `type: application.app-extension`, platform iOS, deploymentTarget 17.0, sources `ShareExtension/`, info `ShareExtension/Info.plist`, entitlements `ShareExtension/ShareExtension.entitlements`, 공유 xcconfig(API_BASE_URL 주입) 상속.
- 메인 `WhereWeGo.dependencies`에 `- target: ShareExtension` (embed, copy into app extensions).
- 두 타겟 모두 App Group `group.com.wherewego.app`.
- ⚠️ CI(시뮬): App Group 엔타이틀먼트는 시뮬 개발빌드에서 portal 등록 없이 통과(예상). 기기/릴스는 Apple Developer portal App Group 컨테이너 등록 + provisioning 필요(Mac DoD-B).

## §3 인증(토큰 공유) 흐름
```
ShareKeychain.accessToken()  ← 공유 access group(group.com.wherewego.app), service=com.wherewego.tokens
  └ nil → 상태=.loginRequired ("앱에서 로그인 후 다시 시도해주세요") → 종료
ShareAPIClient.request(Bearer: accessToken)
  └ 401 → ShareKeychain.refreshToken() 로 POST /api/v1/auth/refresh
            ├ 성공 → 새 토큰 공유 키체인에 write → 원요청 1회 재시도
            └ 실패 → 상태=.loginRequired
```
- 메인 앱 `KeychainTokenStore`가 동일 access group에 저장하므로 익스텐션이 읽는다(§1-1).
- refresh는 메인 앱 `performRefresh`와 동일 계약(Bearer 불요, body={refreshToken}).

## §4 공유 URL 수신 → 전송 흐름
```
ShareViewController: extensionContext.inputItems → NSItemProvider
  loadItem(public.url) → URL  (실패 시 public.text → 정규식으로 첫 http(s) URL 추출)
  └ URL 없음 → 안내 후 종료
ShareViewModel.load(): botRooms() → [ShareGroup]  (그룹 0개 → 빈 상태)
사용자: 체크박스 멀티선택(기본 빈 선택, D2) → [보내기](≥1 활성)
ShareViewModel.send(url):
  상태=.sending
  선택 그룹 동시 전송(withTaskGroup): 각 sendBotMessage(groupId, text=url)
  결과 집계 {성공[], 실패[]}
  모든 전송 완료 대기(D1) → 결과 표시(성공 N, 실패 M)
  → onComplete → extensionContext.completeRequest  (전송 끝나기 전 닫힘 없음)
```

## §5 익스텐션 UI 상태 (ShareViewModel.State)
`.loading` → `.loaded(groups)` / `.empty`(그룹0) / `.loginRequired` / `.sending` / `.result(success,failed)` / `.error(msg)`.
- 목록: 행 = 체크박스 + 그룹명 + "멤버 N명". 하단 [보내기](선택 수 표시, 0이면 비활성) + [취소].
- 부분 실패: 결과에 "M개 그룹 전송 실패" 안내(성공분 유지). [닫기].

## §6 구현 순서
1. **B1 — 토큰 공유 기반**: KeychainTokenStore access group 추가 + 메인 앱 entitlements App Group. (메인 앱 단독 빌드 영향 최소 확인)
2. **B2 — 익스텐션 타겟 골격**: project.yml ShareExtension 타겟 + Info.plist(활성화 규칙) + entitlements + ShareViewController(URL 추출 + 빈 SwiftUI). 빌드 통과 우선.
3. **B3 — 익스텐션 네트워킹/키체인**: ShareKeychain + ShareAPIClient(botRooms/sendBotMessage/refresh) + ShareDTO.
4. **B4 — UI/VM**: ShareViewModel(상태머신) + ShareRootView(체크박스/보내기/상태).
5. **B5 — 테스트**: ShareViewModelTests(Stub 프로토콜).

의존 체인 순차(B2가 B1 access group, B4가 B3 클라이언트 사용). 배치 병렬 없음.

## §7 자기 비판 (design-critic 대행)
- **[리스크] CI 빌드 — App Group 엔타이틀먼트**: 시뮬 빌드는 통과 예상이나, CI 서명 설정이 새 엔타이틀먼트/익스텐션 타겟에 막힐 수 있음. → B1·B2를 먼저 분리 검증(push 후 `gh run watch`). 막히면 익스텐션 타겟만 우선 빈 골격으로 CI 통과 확인 후 진행.
- **[리스크] 인스타 공유 페이로드 불확실**: 인스타가 URL을 public.url로 줄지 text로 줄지 버전별 상이 → public.url + text 폴백 둘 다 처리. **실기기 검증 필수**(시뮬에 인스타 없음).
- **[해소] 코드 중복(DTO/네트워킹)**: 익스텐션 자족 위해 최소 DTO 복제 — 엔드포인트가 안정적이라 수용. 드리프트 위험은 테스트로 일부 방어.
- **[해소] 토큰 마이그레이션**: 미출시라 기존 토큰 없음 → access group 변경에 마이그레이션 불요(PRD 전제).
- **[확인] 익스텐션 메모리/수명**: 자족 경량 구성 + 전송 완료까지 대기(D1) — completeRequest를 전송 후 호출해 유실 방지.
- **[확인] 동시 전송**: withTaskGroup, 그룹 수 소수 → 메모리 안전. 그룹별 서버 멤버십 검증은 백엔드가 수행.

## §8 AC 매핑
AC1→§2 활성화 규칙(B2) / AC2→§5 체크박스·빈선택(B4) / AC3→§4 다중 sendBotMessage(B3·B4) / AC4→§3·§4 전송완료 대기·로그인 안내(B3·B4) / AC5→§4 부분 실패 집계(B4) / AC6→§1-1 access group(B1) / AC7→CI + ShareViewModelTests(B5).
