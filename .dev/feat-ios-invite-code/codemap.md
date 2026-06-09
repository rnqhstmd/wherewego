## 코드 맵: IC-2 iOS 초대 코드(가입 + 발급/공유)

### 핵심 파일 (iOS)
- ios/WhereWeGo/Features/Onboarding/InviteCodeView.swift → 코드 입력 화면. 현재 token 직접 입력 전제(웹 구버전 이식). slug 기반으로 재배선 대상.
- ios/WhereWeGo/Features/Onboarding/InviteCodeViewModel.swift:26 → join(): code를 그대로 acceptInvite(token:)에 전달 → **slug≠token이라 깨짐**. preview→accept 2단계로 교체 대상.
- ios/WhereWeGo/Features/Group/GroupAPI.swift → acceptInvite(token)/issueInviteLink(groupId) 존재. **previewBySlug(slug) 신규 추가 필요** + InviteLinkPreview DTO.
- ios/WhereWeGo/Features/Group/GroupManageView.swift → 그룹 관리 시트(이름/그룹원/위험). **초대 코드 발급/공유 섹션 추가 대상**(in-app 초대).
- ios/WhereWeGo/Features/Group/GroupManageViewModel.swift → load/rename/delete/leave. issue/copy 메서드 추가 대상.
- ios/WhereWeGo/Features/Group/GroupContext.swift:103 → enterGroup/refresh/exitGroup. 가입 성공 후 refresh+enterGroup 배선 대상.

### 참조 파일 (iOS)
- ios/WhereWeGo/App/OnboardingRouter.swift:69 → .inviteCode 라우트(onboarding). onJoined=afterGroupResolved. onJoined 시그니처 변경 시 영향.
- ios/WhereWeGo/App/MainTabView.swift:198,218,236 → in-app 합류 시트(GroupListView onJoin→groupEntrySheet=.invite) + 딥링크(.invite prefill=slug). onJoined=시트 close만(그룹 갱신 누락 — 보완 대상).
- ios/WhereWeGo/Features/Group/GroupListView.swift → "초대 코드로 합류"(onJoin) 진입점. 빈 상태 + addGroupRow.
- ios/WhereWeGo/Features/Onboarding/WelcomeWizardViewModel.swift:76 → 기존 발급/복사 패턴(issueInviteLink → shareText=shareUrl ?? token → UIPasteboard 복사). 일관성 기준.
- ios/WhereWeGo/Core/Networking/APIClient.swift:17 → APIError{code,status,message}. code로 에러 분기(GROUP_ALREADY_MEMBER 등).
- ios/WhereWeGo/Core/Session/CurrentUser.swift → 방장 판정 등에서 사용.

### 참조 파일 (백엔드 — 계약, 변경 없음)
- backend/.../interfaces/api/group/GroupV1Controller.java:54,65 → POST invite-links/{token}/accept · GET invite-links/by-slug/{slug}(preview, 토큰 반환).
- backend/.../interfaces/api/group/GroupV1Dto.java:53 → InviteLinkPreviewResponse{token,groupName,inviterNickname,expiresAt}. InviteLinkResponse{token,slug,expiresAt,shareUrl}.
- backend/.../support/error/ErrorType.java:47-52 → GROUP_CAPACITY_EXCEEDED(409)·GROUP_ALREADY_MEMBER(409)·INVITE_LINK_NOT_FOUND(404)·INVITE_LINK_EXPIRED(410).

### 설정
- ios/WhereWeGoTests/ → 단위 테스트(InviteCodeVM/GroupContext 등). 신규 VM 로직 테스트 추가 대상.
- iOS는 Windows 빌드 불가 → GitHub Actions(macOS)가 빌드+단위테스트 검증.
