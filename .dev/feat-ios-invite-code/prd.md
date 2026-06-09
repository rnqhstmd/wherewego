# PRD — IC-2 iOS 초대 코드 (코드 입력 가입 + 발급/공유)

## 배경
IC-1(백엔드)·IC-3(웹)에서 초대 체계를 "링크 직접 가입" → "**slug 코드** + 앱 가입"으로 전환했다.
그러나 iOS `InviteCodeViewModel.join()`은 입력값을 **UUID 토큰으로 그대로** `acceptInvite(token:)`에 전달한다(구버전 웹 1:1 이식). 실제 사용자에게 노출·복사되는 코드는 **slug(base56 8자)**이므로 slug ≠ token — **현재 코드 입력 합류가 실패**한다.
또한 그룹에 들어간 뒤 **남을 초대(코드 발급/공유)하는 동선이 GroupManageView에 없다**(발급/복사는 온보딩 WelcomeWizard에만 존재).

## 확정된 정책 결정 (사용자)
- **D1 합류 확인**: 코드 입력 → by-slug preview로 그룹명 확보 → "OO 그룹에 합류할까요?" 확인 → accept. (오타·오그룹 방지)
- **D2 공유 방식**: GroupManageView에서 코드(slug) 표시+복사 **및** 링크(shareUrl) 시스템 공유 둘 다.
- **D3 입력 허용**: 합류 입력란은 **코드(slug)만** 허용. 전체 URL 자동 추출은 하지 않는다(URL 진입은 기존 `.invite` 딥링크 prefill 경로가 담당 — 일관).

## 요구사항
- **[Must] FR1** — 합류를 slug 기반으로 재배선: `slug 입력 → GET /groups/invite-links/by-slug/{slug}(토큰·그룹명) → POST /groups/invite-links/{token}/accept`.
- **[Must] FR2** — 합류 전 그룹명 확인 단계(D1).
- **[Must] FR3** — 신규 에러코드별 사용자 메시지:
  - 404 `INVITE_LINK_NOT_FOUND` / 410 `INVITE_LINK_EXPIRED` → "잘못된 코드이거나 만료되었어요"
  - 409 `GROUP_ALREADY_MEMBER` → "이미 이 그룹의 멤버예요"
  - 409 `GROUP_CAPACITY_EXCEEDED` → "그룹 정원이 가득 찼어요"
  - 그 외/네트워크 → "합류하지 못했어요. 잠시 후 다시 시도해주세요"
- **[Must] FR4** — `GroupAPI.previewBySlug(slug:)` + `InviteLinkPreview` DTO 추가(`GroupAPIProtocol` 포함).
- **[Must] FR5** — in-app(MainTabView) 합류 성공 시 `GroupContext.refresh()` + 가입 그룹 `enterGroup(groupId)`(목록 즉시 반영 + 지도 진입). 온보딩(OnboardingRouter) 합류는 기존 `afterGroupResolved` 흐름 유지.
- **[Should] FR6** — GroupManageView "초대 코드" 섹션: `issueInviteLink` → slug 코드 표시 + 복사(UIPasteboard) + 링크 공유(ShareLink/시스템 공유). D2.
- **[Must] FR7** — 단위 테스트: slug 합류 성공 경로, 에러코드 매핑, 발급/복사 경로(GitHub Actions iOS).

## 수용 기준 (AC)
- **AC1** — 유효한 slug 입력 → 그룹명 확인 → 합류 시 by-slug preview로 token 확보 후 accept 성공, 그룹 가입됨.
- **AC2** — 합류 전 그룹명이 표시되고, 사용자가 확인을 눌러야 accept가 실행된다(즉시 accept 금지).
- **AC3** — 에러코드별 메시지가 FR3 매핑대로 노출된다(이미 멤버/정원 초과/만료·없음 구분).
- **AC4** — in-app 합류 성공 시 그룹 목록에 즉시 반영되고 해당 그룹 지도(레벨1)로 진입한다. 온보딩 합류는 기존 위저드 흐름으로 진행된다.
- **AC5** — GroupManageView에서 초대 코드 발급 시 slug 코드가 표시되고, 복사 버튼이 동작하며, 링크 공유 시트가 열린다.
- **AC6** — 빈/공백 코드는 합류 버튼 비활성, 로딩 중 중복 제출 차단.
- **AC7** — 입력란은 코드만 받는다(URL을 붙여넣으면 그 문자열 그대로 slug로 취급 → 정상 slug가 아니면 FR3 만료/없음 메시지). URL 파싱·추출은 하지 않는다.
- **AC8** — 신규/수정 단위 테스트가 GitHub Actions(macOS) iOS 빌드+테스트에서 통과한다.

## 범위 밖
- 백엔드 변경(IC-1 완료, 계약 그대로 사용) · 웹(IC-3 완료)
- WelcomeWizard 발급 UI 변경(기존 URL 복사 정상 동작 — 유지)
- 신규 딥링크/AASA 설정(기존 `.invite` prefill 경로 재사용)
- 앱 전역 리브랜딩 · 커플 1:1 데드코드 정리(별도 PR)
