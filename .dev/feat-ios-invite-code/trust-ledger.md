# Trust Ledger — IC-2 iOS 초대 코드

## 통합 감사 (review) — 오케스트레이터 직접 수행 (읽기 전용 에이전트 미반환)

### QA (스펙 충족) — CERTAIN 0건
- AC1·2 (slug preview→확인→accept): InviteCodeViewModel.preview/confirmJoin ✓ (입력 slug 아닌 preview 토큰으로 accept — 테스트 test_confirmJoin_usesPreviewToken).
- AC3 (에러 매핑): message(for:) 4분기 + 일반 폴백 ✓ (테스트 4건).
- AC4 (in-app enterGroup): MainTabView 2곳 onJoined → enterGroup + refresh ✓ (기존 패턴 정합).
- AC5 (발급·복사·공유): GroupManageView inviteSection + VM issueInvite/copyInviteCode ✓ (테스트 3건).
- AC6 (canSubmit/로딩 가드): preview/confirmJoin isLoading 가드 ✓.
- AC7 (입력=slug 그대로): URL 파싱 없음 ✓.
- AC8 (단위 테스트): InviteCodeViewModelTests 11 + GroupManageViewModelTests +3 → GitHub Actions(macOS) 검증.

### 보안 감사 — CRITICAL 0건
- [INFO] slug 경로 보간 무인코딩: `/groups/invite-links/by-slug/\(slug)`. 비정상 입력(`/`·`..`)은 Spring 단일 PathVariable 미스매치 → 404. 인증 필요 엔드포인트 외 노출 없음. **기존 acceptInvite(token) 패턴과 동일** — 일관성 유지. 위험 낮음.
- [INFO] PII: previewBySlug 응답에 inviterNickname 포함되나 **미표시**(그룹명만 확인 다이얼로그에 노출). IC-3 웹 정책(닉네임 비노출)과 정합.
- [확인] accept 인증: userId 는 Bearer 에서 추출(서버), 클라이언트 위조 불가. preview 는 공개지만 iOS 는 인증 동반 — 무해.
- [확인] 코드(slug) 공유는 의도된 동작(비밀 아님). 재발급 시 이전 코드 만료(백엔드 BR-3) — 자동발급 금지로 우발 만료 차단.

### 빌드 게이트
- iOS Windows 빌드 불가 → GitHub Actions(macOS)가 빌드+단위테스트 자동 검증(push 후, PR 머지 전 green 필수).
- 백엔드/프론트엔드 변경 0건(`git status` 확인) → gradle/npm 게이트 무관.

### 종합
- Critical/CERTAIN 0건. 진행 가능. CI(iOS) green 확인 후 리뷰어 머지.
