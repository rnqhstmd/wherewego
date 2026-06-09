## 코드 맵: IC-3 웹 랜딩 — 초대 링크 → 코드 복사 + 앱스토어 유도

> frontend(Next.js 16, Vercel 자동배포). 기존 `/invite/[slug]` 라우트의 CTA만 교체. 백엔드·인프라 변경 0.

### 핵심 파일 (변경 대상)
- `frontend/src/app/invite/[slug]/InvitePreviewClient.tsx` → 미리보기 + "합류하기"(웹 수락). **IC-3: CTA를 코드(slug) 표시+복사+앱스토어 버튼으로 교체**
- `frontend/src/app/invite/[slug]/page.tsx` → SSR 진입(by-slug preview + OG 메타 + 만료 분기). slug=params로 받음. **OG/미리보기/만료 분기는 그대로 재사용**
- `frontend/src/app/invite/[slug]/InviteExpiredState.tsx` → 만료/소진 상태. **IC-3: 앱스토어 버튼 추가 검토**

### 참조 파일
- `frontend/src/lib/api/invite-server.ts` → getInviteLinkPreviewServer(slug) (SSR by-slug)
- `frontend/src/lib/api/group-client.ts` → InviteLinkPreviewResponse(token·groupName·inviterNickname·expiresAt), acceptInviteLink
- `frontend/src/lib/design/tokens` → colors/fonts
- `frontend/src/components/ui/BtnPrimary`·`BtnSub` → 버튼 컴포넌트

### 설정
- `frontend/AGENTS.md` → ⚠️ **Next.js 16 breaking changes** — coder는 `node_modules/next/dist/docs/` 확인 후 작성
- `frontend/.env.example` → BACKEND_BASE_URL. **앱스토어 URL 환경변수 추가 필요**(NEXT_PUBLIC_*)
- `frontend/public/.well-known/` → AASA(apple-app-site-association) **없음** → 딥링크 자동입력 미설정(MVP는 복사만)

### 백엔드 (변경 0 — develop 머지본)
- `GroupV1Dto.InviteLinkPreviewResponse`(token·groupName·inviterNickname·expiresAt) — slug는 응답에 없으나 URL params로 보유
- `previewBySlug`(IC-1 #101) — 만료/소진/정원초과 구분 응답
