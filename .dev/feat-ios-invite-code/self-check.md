# 자기점검 — IC-2 구현

## 변경 파일 (요약)
수정 8 + 신규 1 + 테스트 보강 1:
1. Core/Auth/AuthServiceProtocols.swift — previewBySlug 프로토콜 요구 + 익스텐션 기본구현(throwing)
2. Features/Group/GroupAPI.swift — InviteLinkPreview + previewBySlug 구현
3. Features/Onboarding/InviteCodeViewModel.swift — slug 2단계 재작성(preview/confirmJoin/dismissConfirm/message)
4. Features/Onboarding/InviteCodeView.swift — preview 버튼 + confirmationDialog + onJoined:(Int)->Void
5. App/OnboardingRouter.swift — onJoined { _ in afterGroupResolved() }
6. App/MainTabView.swift — 2곳 onJoined: enterGroup + refresh (+딥링크는 selection=.map)
7. Features/Group/GroupManageViewModel.swift — issueInvite/copyInviteCode + 상태
8. Features/Group/GroupManageView.swift — inviteSection(코드 표시 + 복사 + ShareLink)
9. WhereWeGoTests/InviteCodeViewModelTests.swift — 신규(11 케이스)
10. WhereWeGoTests/GroupManageViewModelTests.swift — issue/copy 3 케이스 + 스텁 주입

## 정적 검증 (Windows — iOS 빌드 불가, CI 위임)
- ✅ 옛 `join(onJoined:)` 호출 잔존 0건(메서드 제거 + 호출부 전환 완료).
- ✅ `InviteCodeView(` 호출부 3곳(MainTabView×2, OnboardingRouter×1) 모두 `(Int)->Void` 시그니처로 갱신.
- ✅ 프로토콜 리플: previewBySlug 익스텐션 기본구현으로 12개 기존 스텁 무수정(컴파일 유지). 신규 StubInviteAPI만 실제 override.
- ✅ 동시성: 클로저 내 `groupContext.enterGroup`/`selection=` 직접 호출 — 기존 동일 패턴(MainTabView:152 `onReselectMap:{ groupContext.backToList() }`, :307 `selection=.chat`)과 정합.
- ✅ API 가용성: deploymentTarget iOS 17 → ShareLink/confirmationDialog/textSelection 모두 지원.
- ✅ `client.request(_:type:)` GET 2-arg 형태 — myActiveGroup/listMembers와 동일.
- ✅ InviteLinkPreview Decodable(optional inviterNickname/expiresAt) — 백엔드 4필드 응답과 정합(추가 키 무시).
- ✅ 토큰 유실 회귀 방지: dismissConfirm은 pendingGroupName만 해제, pendingToken은 confirmJoin이 소비(테스트 test_dismissConfirm_keepsToken).
- ✅ XcodeGen project.yml: WhereWeGoTests 폴더 글로브 → 신규 테스트 자동 포함.

## 빌드 게이트
- iOS: Windows 빌드 불가 → **GitHub Actions(macOS) "Build & Unit Test (iOS Simulator)"가 검증**(push 후).
- 백엔드/프론트엔드: 변경 0건 → gradle/npm 게이트 무관.

## AC 커버리지
AC1·2 합류(preview→confirm→accept) / AC3 에러매핑 / AC4 in-app enterGroup / AC5 발급·복사·공유 / AC6 canSubmit·isLoading / AC7 입력=slug그대로 / AC8 단위테스트(CI).

## 잔여 위험 (review에서 확인)
- W1: confirmationDialog 자동닫힘 타이밍 — dismissConfirm이 토큰 보존하므로 합류 보장(테스트로 고정). 회귀 시 인라인 확인 카드로 대체 가능.
- W2: ShareLink 절대 URL 폴백 — shareBaseUrl 미설정 시 코드 텍스트 공유(URL.scheme nil 가드).
- W3: 재발급 시 이전 코드 만료(백엔드 BR-3) — 자동발급 금지로 우발 만료 차단. UX 안내 문구는 후속.
