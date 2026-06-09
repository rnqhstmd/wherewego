phase: review
status: in_progress
vcs-type: git
branch: feat/web-invite-landing
base: develop
dev-dir: .dev/feat-web-invite-landing
project-type: node (frontend Next.js 16 / Vercel) + java-spring
project-root: ./
args: "IC-3 웹 랜딩 — 초대 링크 /invite/[slug]를 코드 복사 + 앱스토어 유도 랜딩으로 전환"
flags: (none — NORMAL)
mode: normal
intent-source: user-selection
started: 2026-06-09
current-step: "review 클린 통과(직접 QA/ZT). Next build(background) green 확인 후 complete(커밋+PR)."
parent-context: "GM-2 마무리 후 IC-3. frontend(Vercel)의 /invite/[slug] CTA를 웹수락→코드 복사+앱스토어로 교체. 백엔드·인프라 0. develop 기반 독립 PR. IC-2(iOS)와 slug 체계 정합 전제. push 후 Vercel preview + gh switch rnqhstmd."
key-decisions: "웹 수락(acceptInviteLink) 완전 제거(앱 전용). 앱스토어 URL=NEXT_PUBLIC_IOS_APP_URL(미설정 시 비활성+출시예정). 딥링크(AASA)=후속. 만료화면도 앱스토어 유도. page.tsx 무변경(OG 회귀 0)."
phases:
  setup: completed
  requirements: completed
  design: completed
  implement: completed
  review: in_progress
  complete: pending
execution-log:
  - phase: setup
    result: "develop 동기화. 새 브랜치 feat/web-invite-landing. invite 라우트·API 계약(InviteLinkPreviewResponse엔 slug 없음→params)·BtnPrimary 정독. 코드맵. Next.js 16 주의."
  - phase: requirements
    result: "PRD 직접. Q&A 2건(웹수락 완전교체 / 앱스토어 환경변수). 승인. prd.md."
  - phase: design
    result: "설계 직접(소형, design-critic 생략). appStore 헬퍼+InvitePreviewClient 재작성+InviteExpiredState+. env. 승인. design.md."
  - phase: implement
    agent: coder(frontend)
    result: "신규 appStore.ts+InvitePreviewClient.test.tsx, 수정 InvitePreviewClient/InviteExpiredState/.env.example. 자기점검 Critical 0. tsc 0·vitest 182통과·변경파일 lint clean. self-check.md."
  - phase: review
    result: "직접 QA/ZT 클린(Critical/CRITICAL/QUESTION 0). XSS/redirect/웹수락제거/OG회귀 점검 통과. trust-ledger.md. Next build green 확인 대기."
