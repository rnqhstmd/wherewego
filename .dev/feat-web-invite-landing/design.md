# 설계: IC-3 웹 랜딩 — 코드 복사 + 앱스토어 유도

## 설계 규모
**소형** — frontend 수정 2 + 신규 1 + 설정 1. 구조 변경 없음, 백엔드·인프라 0 → design-critic 생략.

> ⚠️ **Next.js 16 breaking changes**(`frontend/AGENTS.md`) — coder는 `node_modules/next/dist/docs/` 확인 후 작성. 단 본 설계는 기존 패턴(client component, BtnPrimary, tokens) 재사용이라 신규 Next API 사용 없음.

## 현황 (정독)
- `page.tsx`: SSR by-slug preview + `generateMetadata`(카톡 OG) + 만료 분기. `InvitePreviewClient(slug, preview)` / `InviteExpiredState`.
- `InvitePreviewClient`: 미리보기(inviterNickname/groupName/만료 카운트다운) + "합류하기"(`acceptInviteLink`) + "취소".
- `InviteExpiredState`: 만료 안내 + "홈으로"(`router.replace("/map")`).
- `BtnPrimary`/`BtnSub`: `<button>`(children/onClick/disabled/style). `colors`/`fonts` from `@/lib/design/tokens`.
- 환경변수: `NEXT_PUBLIC_*`는 빌드타임 인라인 → client component 접근 가능.

## 확정 설계

### D-1. 앱스토어 URL 헬퍼 (신규 `frontend/src/lib/config/appStore.ts`)
```ts
/** iOS App Store URL. 미설정(빈 문자열)이면 버튼 비활성 + "출시 예정". */
export const IOS_APP_URL = process.env.NEXT_PUBLIC_IOS_APP_URL ?? "";
export const isAppStoreReady = IOS_APP_URL.length > 0;
```
- `InvitePreviewClient` + `InviteExpiredState` 공용 (process.env 중복 접근 방지).
- NEXT_PUBLIC_은 빌드타임 인라인 — Vercel 환경변수 설정 후 **재배포** 필요(런타임 변경 X).

### D-2. InvitePreviewClient 재작성 (FR-1~5, FR-8, AC-1~4·6)
- **제거**: `acceptInviteLink` import, `useRouter`, `onAccept`/`submitting`/`error`, "합류하기"/"취소" 버튼.
- **유지**: 미리보기 헤더(inviterNickname) + 그룹 카드(groupName + 만료 `remainingText` 카운트다운).
- **추가**:
  - **코드 카드**: `slug`를 크게(mono 폰트, letterSpacing) 표시 + "코드 복사" 버튼(`BtnSub`).
  - **복사 핸들러**(FR-2/FR-8):
    ```ts
    const [copied, setCopied] = useState(false);
    const onCopy = async () => {
      try { await navigator.clipboard.writeText(slug); setCopied(true); setTimeout(() => setCopied(false), 2000); }
      catch { /* FR-8: 미지원 시 안내 (예: prompt 또는 "직접 복사하세요") */ }
    };
    ```
    버튼 라벨: `copied ? "복사됐어요" : "코드 복사"`.
  - **안내 문구**(FR-5): "wherewego 앱을 설치하고 이 코드를 입력하세요".
  - **앱스토어 버튼**(FR-3): `BtnPrimary onClick={() => { if (IOS_APP_URL) window.location.href = IOS_APP_URL; }} disabled={!isAppStoreReady}`. 라벨 `isAppStoreReady ? "App Store에서 받기" : "출시 예정"`.
- **레이아웃**(기존 컨테이너/패딩 유지): 헤더 → 그룹 카드 → 코드 카드(복사) → 안내 문구 → `flex:1` → 앱스토어 버튼.

### D-3. InviteExpiredState 보강 (FR-6)
- `useRouter`/"홈으로"(`/map`) 제거(웹 가입 종료 → /map 무의미).
- 안내 문구 보강: "짝꿍에게 새 초대 링크를 받아주세요" 유지 + 앱 안내 추가.
- **앱스토어 버튼**: `BtnPrimary`(D-2와 동일 패턴, `isAppStoreReady` 분기). client component 유지("use client").

### D-4. .env.example 보강 (FR-3)
```
# iOS App Store URL (미설정 시 초대 랜딩 앱스토어 버튼 비활성 + "출시 예정")
NEXT_PUBLIC_IOS_APP_URL=
```

## 변경 범위 (신규 1 · 수정 3)
- 신규: `frontend/src/lib/config/appStore.ts`
- 수정: `frontend/src/app/invite/[slug]/InvitePreviewClient.tsx` (재작성), `InviteExpiredState.tsx` (앱스토어), `frontend/.env.example`
- `page.tsx`: **무변경** (preview/OG/분기 그대로 — AC-6 회귀 방지). `slug` props는 이미 전달 중.

## 구현 순서
1. `appStore.ts` (헬퍼)
2. `InvitePreviewClient.tsx` (코드 표시·복사·앱스토어, 웹수락 제거)
3. `InviteExpiredState.tsx` (앱스토어 유도)
4. `.env.example` (NEXT_PUBLIC_IOS_APP_URL)
5. 테스트(vitest) + 검증

## 테스트 (vitest — `npm run test`)
- 기존 invite 테스트 존재 여부 확인(`acceptInviteLink` 호출 검증 테스트가 있으면 제거/수정).
- `InvitePreviewClient` 신규/수정: ① slug 코드 표시 ② "코드 복사" → `navigator.clipboard.writeText(slug)` 호출(mock) + "복사됐어요" 토글 ③ 앱스토어 버튼 env 미설정 시 disabled+"출시 예정", 설정 시 활성 ④ `acceptInviteLink` 미호출(import 제거).
- `@testing-library/react` + `vitest` (devDeps에 존재).

## 리스크 / 호환
- **R1** Next.js 16 breaking — 기존 패턴 재사용이라 영향 최소, coder가 docs 확인.
- **R2** `navigator.clipboard`는 secure context(HTTPS) 필요 — Vercel HTTPS라 OK, 로컬 http/구형은 FR-8 폴백.
- **R3** NEXT_PUBLIC_ 빌드타임 인라인 — Vercel 환경변수 변경 시 재배포 필요(문서/PR 본문에 명시).
- **R4** `page.tsx` 무변경으로 OG/만료 분기 회귀 0(AC-6).

## 확인 필요 사항
추가 확인 없음. 설계 완료.
