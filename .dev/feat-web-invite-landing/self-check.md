# 자기점검 — IC-3 웹 랜딩 (implement)

> coder 산출물 미반환 → 오케스트레이터 직접 검토(코드 Read + 검증 실행). frontend는 Windows 검증 가능.

## Critical: 0건

## AC 충족 (PRD 대비)
| AC | 충족 | 근거 |
|----|------|------|
| AC-1 미리보기+코드+복사+앱스토어 | ✅ | InvitePreviewClient: 그룹 카드 + 코드 카드(slug, mono 28px) + BtnSub 복사 + BtnPrimary 앱스토어 |
| AC-2 복사+피드백 | ✅ | onCopy: navigator.clipboard.writeText(slug) + copied 2초 토글("복사됐어요") |
| AC-3 앱스토어 env/비활성 | ✅ | BtnPrimary disabled={!isAppStoreReady}, 라벨 "App Store에서 받기"/"출시 예정", onClick window.location.href=IOS_APP_URL |
| AC-4 웹수락 동선 없음 | ✅ | acceptInviteLink/useRouter import 제거, onAccept 제거(grep clean) |
| AC-5 만료화면 앱스토어 | ✅ | InviteExpiredState: useRouter/"홈으로" 제거 → 앱스토어 BtnPrimary, 안내 보강 |
| AC-6 OG 회귀 없음 | ✅ | page.tsx 무변경(slug props 이미 전달, generateMetadata 그대로) |
| FR-8 클립보드 폴백 | ✅ | catch → window.prompt(직접 복사 안내) |

## 검증 (실행)
- `npx tsc --noEmit`: **0** (타입 통과)
- `npm run test`(vitest): **25 파일 182개 전부 통과** (신규 `InvitePreviewClient.test.tsx` 포함)
- `npx eslint`(IC-3 변경 4파일): **EXIT:0 clean**
- 전체 `npm run lint` 10 errors = **기존 develop 코드**(SpeechBubblePopup.test img / renderPinCard unused 등) — IC-3 무관, 회귀 아님
- `npm run build`(Next 프로덕션): 진행 중(background)

## Warning / Info
- [Info] NEXT_PUBLIC_IOS_APP_URL은 빌드타임 인라인 → Vercel 환경변수 설정 후 재배포 필요(appStore.ts 주석 + PR 본문 명시).
- [Info] 앱 미출시 동안 앱스토어 버튼 "출시 예정" 비활성 — 의도된 동작.

## QUESTION: 0건

## 판정
Critical 0 · AC 6/6 + FR-8 충족. tsc/test/우리 lint 통과. build 결과 확인 후 review.
