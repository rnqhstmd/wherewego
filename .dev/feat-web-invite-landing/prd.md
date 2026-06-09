# PRD: IC-3 웹 랜딩 — 초대 링크를 코드 복사 + 앱스토어 유도로 전환

## 배경
초대 시스템을 앱 중심으로 전환한다. 기존 `/invite/[slug]`는 "합류하기"로 **웹에서 직접 가입**(`acceptInviteLink`)했으나, wherewego가 iOS 앱으로 전환되며 웹 가입을 종료한다. 초대 링크는 이제 **앱 설치 + 코드 입력 가입을 유도하는 랜딩**이 된다. frontend(Vercel 자동배포)·백엔드 by-slug 미리보기(IC-1 #101)는 그대로, **CTA만 교체**.

## 확정 정책 (requirements Q&A)
- **웹 수락 완전 교체(앱 전용)**: 기존 "합류하기"(웹 직접 가입) 제거. 앱 미출시 중엔 웹 가입 불가(앱 출시와 함께 가는 전략).
- **앱스토어 URL = 환경변수**: `NEXT_PUBLIC_IOS_APP_URL`. 미설정 시 버튼 비활성 + "출시 예정" 표기.

## 요구사항

### Must
- **FR-1 (코드 표시)**: 랜딩에 초대 코드(URL `slug`, base56 8자)를 크게 표시. 앱에 입력할 값. (`InviteLinkPreviewResponse`엔 slug 없음 → `params.slug` 사용)
- **FR-2 (복사)**: "코드 복사" → `navigator.clipboard`로 slug 복사 + 완료 피드백("복사됐어요", 2~3초 후 원복).
- **FR-3 (앱스토어 유도)**: "App Store에서 받기" → `NEXT_PUBLIC_IOS_APP_URL`. **미설정 시 버튼 비활성 + "출시 예정"** 표기.
- **FR-4 (웹 수락 제거)**: 기존 "합류하기"(`acceptInviteLink`) + "취소" 버튼 제거. `acceptInviteLink` import·호출 제거.
- **FR-5 (안내 문구)**: "wherewego 앱을 설치하고 이 코드를 입력하세요" 가이드 문구.

### Should
- **FR-6 (만료 화면 일관)**: `InviteExpiredState`(만료/소진/없음)에도 앱스토어 버튼 + "앱에서 새 초대를 받아주세요" 안내 추가.
- **FR-7 (기존 보존)**: 그룹명/초대자/만료시간 미리보기 + 카톡 OG 메타데이터(`page.tsx generateMetadata`) 그대로 유지.

### Could
- **FR-8 (클립보드 폴백)**: `navigator.clipboard` 미지원(비-HTTPS/구형 브라우저) 시 폴백 안내 또는 텍스트 선택.

## 비범위
- **딥링크(Universal Links / AASA)** — 앱 자동 열기·코드 자동 입력은 후속(`public/.well-known` 없음). MVP는 복사→수동 입력.
- **Android** (앱은 iOS 전용)
- **iOS IC-2**(앱 코드 입력 가입) — 별도 작업. 본 랜딩 코드(slug)는 IC-2의 가입 입력과 체계 정합 전제.
- **백엔드 변경** (by-slug 미리보기 IC-1 그대로 사용)

## 수용 기준
- **AC-1**: 유효 링크 클릭 → 그룹 미리보기 + 코드(slug) 표시 + 복사 버튼 + 앱스토어 버튼.
- **AC-2**: "코드 복사" → 클립보드에 slug 복사 + 완료 피드백.
- **AC-3**: 앱스토어 버튼 = `NEXT_PUBLIC_IOS_APP_URL`로 이동. 미설정 시 비활성 + "출시 예정".
- **AC-4**: 기존 "합류하기"(웹 수락) 동선 없음(`acceptInviteLink` 미호출).
- **AC-5**: 만료/소진 링크 → `InviteExpiredState` + 앱스토어 유도.
- **AC-6**: 카톡 공유 OG 미리보기(그룹명/초대자) 회귀 없음.

## 확인 필요 사항
추가 확인 없음(웹 수락 완전 교체·앱스토어 환경변수 확정). PRD 확정 가능.
