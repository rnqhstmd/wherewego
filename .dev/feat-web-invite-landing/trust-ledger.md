# Trust Ledger — IC-3 웹 랜딩 (review)

> qa-manager·security-auditor 미반환 → 오케스트레이터 직접 QA + ZT 감사. frontend(Next.js 16)는 Windows 검증 가능.

## Mechanical Gate
- `npx tsc --noEmit`: **0**
- `npm run test`(vitest): **25 파일 182개 통과**(신규 `InvitePreviewClient.test.tsx` 포함)
- `npx eslint`(IC-3 변경 4파일): **clean(0)**. 전체 lint 10 errors는 기존 develop 코드(회귀 아님)
- `npm run build`(Next 프로덕션): background 실행 중 → complete 전 green 확인

## QA (스펙 충족)
- Critical 0 · Warning 0 · Info 2
- AC-1~6 + FR-8 전부 충족(self-check.md 참조)

## ZT 통합 감사 (정책/보안/허점)
- CRITICAL 0 · HIGH 0 · MEDIUM 0

### 점검 항목 (모두 통과)
- **XSS**: `slug`/`groupName`/`inviterNickname`은 React `{}` 보간 → 자동 이스케이프. `dangerouslySetInnerHTML` 없음.
- **외부 이동**: `window.location.href = IOS_APP_URL`은 빌드타임 상수(env), 사용자 입력 무관 → open-redirect 표면 없음. `window.prompt`(폴백)은 slug 표시만.
- **클립보드**: `navigator.clipboard.writeText(slug)` — slug는 by-slug preview 성공한 서버 검증값. 실행 없음.
- **웹 가입 차단**: `acceptInviteLink` import/호출 제거 → 웹 직접 가입 동선 제거(앱 전용 전환 의도). by-slug preview(SSR)는 page.tsx 무변경 — IC-1 레이트리밋/정보노출(404 통일) 그대로.
- **임의 slug 접근**: `/invite/{임의}` → by-slug 404 → `InviteExpiredState`(기존 동작, 정보 노출 없음).
- **신규 권한/네트워크**: 없음(읽기 전용 랜딩).

## 신규 위험 (self-check 미보고분)
- 없음. (Info 2건은 self-check 기보고: env 빌드타임 재배포 / "출시 예정" 비활성)

## 미해결 항목
없음. (Critical 0 · CRITICAL 0 · QUESTION 0)

## 판정
클린 통과. build green 확인 후 complete.
