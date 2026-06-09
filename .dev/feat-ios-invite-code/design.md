# 설계 — IC-2 iOS 초대 코드 (코드 입력 가입 + 발급/공유)

설계 규모: **중형** (8개 파일 수정 + 1개 신규 테스트, 프로토콜·콜백 시그니처 변경 리플).

## §0 핵심 전략
- 백엔드 계약 무변경. iOS만 수정. `accept`는 **token(UUID)** 기반 유지, 사용자 코드는 **slug**.
- 합류 = 2단계: `previewBySlug(slug) → token·groupName 확보 → 확인 → acceptInvite(token) → groupId`.
- `GroupAPIProtocol`에 메서드 추가 시 **기본 구현(extension, throwing)** 으로 기존 12개 테스트 스텁 무수정 보장.

## §1 변경 범위

### 수정 (8)
1. **Core/Auth/AuthServiceProtocols.swift** — `GroupAPIProtocol`에 `func previewBySlug(slug:) async throws -> InviteLinkPreview` 추가. **+ `extension GroupAPIProtocol` 기본 구현**(미구현 스텁 보호: `throw APIError(code:"UNSUPPORTED",status:0,message:...)`). InviteLinkPreview 타입 참조.
2. **Features/Group/GroupAPI.swift** — `InviteLinkPreview` struct(`token`, `groupName`, `inviterNickname?`, `expiresAt?`) + 실제 `previewBySlug` 구현(`GET /groups/invite-links/by-slug/{slug}`). 401 전파.
3. **Features/Onboarding/InviteCodeViewModel.swift** — 2단계 합류로 재작성. phase(input/joining), `pendingGroupName`/`pendingToken`, `preview()`(slug→token·name), `confirmJoin(onJoined:)`(accept→groupId), `cancelConfirm()`, 에러코드 매핑(`map`). `onJoined: (Int) -> Void`.
4. **Features/Onboarding/InviteCodeView.swift** — 합류 버튼 → `preview()`. `pendingGroupName != nil` 바인딩으로 `confirmationDialog`("'OO' 그룹에 합류할까요?" → 합류하기=confirmJoin / 취소=cancelConfirm). `onJoined: (Int) -> Void`.
5. **App/OnboardingRouter.swift** — `.inviteCode` 의 `onJoined: { _ in afterGroupResolved() }` (groupId 무시 — 온보딩은 재조회).
6. **App/MainTabView.swift** — InviteCodeView 2곳(딥링크 시트 line~200, groupEntry 시트 line~218)의 `onJoined: { gid in Task { await groupContext.refresh(); groupContext.enterGroup(gid) }; <sheet>=nil }`.
7. **Features/Group/GroupManageViewModel.swift** — 초대 발급 상태(`inviteCode: String?`=slug, `inviteShareUrl: String?`, `isIssuing`, `inviteCopied`) + `issueInvite()`(issueInviteLink→slug·shareUrl) + `copyInviteCode(_:)`.
8. **Features/Group/GroupManageView.swift** — "초대" 섹션 추가: 미발급 시 rowButton("초대 코드 만들기")→issueInvite; 발급 후 코드(mono) 표시 + 복사 버튼 + ShareLink(절대 URL이면 url, 아니면 코드 텍스트).

### 신규 (1)
9. **WhereWeGoTests/InviteCodeViewModelTests.swift** — slug 합류 성공(preview→confirm→accept), 에러코드별 매핑(이미멤버/정원초과/만료·없음), 빈코드 canSubmit=false, 확인 취소. 전용 StubInviteGroupAPI(previewBySlug/acceptInvite 제어).

### 수정 (테스트, 선택)
10. **WhereWeGoTests/GroupManageViewModelTests.swift** — `issueInvite()` 성공/실패 케이스 추가(StubGroupManageAPI에 issueInviteLink 동작 주입).

## §2 데이터 흐름

### 합류 (slug → 그룹 가입)
```
InviteCodeView(code 입력) → VM.preview()
  → groupAPI.previewBySlug(slug) → {token, groupName}
  → pendingGroupName 세팅 → confirmationDialog 노출
  → 사용자 "합류하기" → VM.confirmJoin()
  → groupAPI.acceptInvite(token) → {groupId}
  → onJoined(groupId)
       · 온보딩: afterGroupResolved() (위저드)
       · in-app: groupContext.refresh() + enterGroup(groupId) (지도 레벨1 진입) + 시트 close
```

### 발급/공유 (그룹 안 → 코드 배포)
```
GroupManageView "초대 코드 만들기" → VM.issueInvite()
  → groupAPI.issueInviteLink(groupId) → {slug, shareUrl}
  → 코드(slug) 표시 + 복사(UIPasteboard) + ShareLink(shareUrl)
```

## §3 에러 매핑 (VM.map)
| APIError.code | status | 메시지 |
|---|---|---|
| INVITE_LINK_NOT_FOUND | 404 | 잘못된 코드이거나 만료되었어요 |
| INVITE_LINK_EXPIRED | 410 | 잘못된 코드이거나 만료되었어요 |
| GROUP_ALREADY_MEMBER | 409 | 이미 이 그룹의 멤버예요 |
| GROUP_CAPACITY_EXCEEDED | 409 | 그룹 정원이 가득 찼어요 |
| (그 외/네트워크/파싱) | - | 합류하지 못했어요. 잠시 후 다시 시도해주세요 |

- preview 단계: 주로 404/410. accept 단계: 409(이미멤버/정원). 두 단계 모두 map 적용, 에러는 입력 화면에 노출(확인 다이얼로그 닫고 .input 복귀).

## §4 구현 순서
1. **B1 — API 계층**: AuthServiceProtocols(프로토콜+기본구현) + GroupAPI(InviteLinkPreview+previewBySlug). 컴파일 단위 독립.
2. **B2 — 합류 VM/View**: InviteCodeViewModel 재작성 + InviteCodeView 확인 다이얼로그 + onJoined 시그니처.
3. **B3 — 콜백 배선**: OnboardingRouter(1) + MainTabView(2) onJoined 갱신(refresh+enterGroup).
4. **B4 — 발급/공유**: GroupManageViewModel(issueInvite/copy) + GroupManageView 초대 섹션.
5. **B5 — 테스트**: InviteCodeViewModelTests 신규 + GroupManageViewModelTests 보강.

순차 의존(B2가 B1 타입 사용, B3이 B2 시그니처 사용). 배치 병렬 없음(파일 의존 체인).

## §5 자기 비판 (design-critic 대행)
- **[해소] 프로토콜 리플**: previewBySlug 추가가 12개 스텁을 깨뜨림 → extension 기본 구현(throwing)으로 무수정. 신규 테스트 스텁만 실제 override.
- **[해소] onJoined 시그니처 변경**: `()->Void`→`(Int)->Void` 3개 호출부 동시 수정(컴파일러가 누락 강제). 온보딩은 `{ _ in }`로 흡수.
- **[주의] 재발급 시 기존 코드 만료**: 백엔드 BR-3(`expirePendingByGroupId`)로 issueInviteLink 재호출 시 이전 미수락 토큰 만료. → GroupManageView는 **load 시 자동 발급 금지**, 명시적 "만들기" 1회만. 세션 내 동일 코드 유지(VM @StateObject). "재생성" 버튼 미제공(우발적 만료 방지).
- **[확인] preview 인증**: by-slug preview는 공개(웹 SSR 사용)지만 iOS는 Bearer 동반 호출 — 무해. accept만 인증 필수(userId).
- **[확인] ShareLink 가용성**: 앱이 NavigationStack/confirmationDialog(iOS16+) 사용 → ShareLink(iOS16+) 가용. shareUrl 미설정(상대경로) 시 코드 텍스트 공유로 폴백.

## §6 수용 기준 매핑
AC1·AC2 → §2 합류 흐름(B2). AC3 → §3 에러(B2). AC4 → §2 in-app onJoined(B3). AC5 → §2 발급(B4). AC6 → VM canSubmit/isLoading(B2). AC7 → 입력값 그대로 slug 취급(B2). AC8 → §1-9/10(B5, GitHub Actions).
