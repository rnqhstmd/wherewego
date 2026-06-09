# PRD — 인스타 공유 → 우리 앱 → 그룹 DM 다중선택 전송 (iOS Share Extension)

## 배경
릴스를 그룹에 저장하려면 URL을 복사해 봇방마다 일일이 들어가 붙여넣어야 한다. iOS **Share Extension**으로 인스타 "공유 → 우리가 갈 지도"를 만들면, 한 번에 여러 그룹을 체크박스로 골라 전송할 수 있다. 봇은 이미 메시지 URL에서 장소를 추출(릴스 저장)하므로 **백엔드는 기존 API 재사용**(`GET /chat/bot/rooms`, `POST /chat/bot/{groupId}/messages`)으로 변경 0.

## 전제 (assumption)
- 앱은 **미출시(개발 단계)** → 기존 로그인 사용자 없음 → Keychain access group 변경 시 **토큰 마이그레이션 불필요**.

## 확정된 정책 결정 (사용자)
- **D1 전송 신뢰성**: [보내기] 시 **선택 그룹 전송이 모두 끝날 때까지 대기(로딩 표시) 후 익스텐션 종료**. (즉시 닫기 금지 — 익스텐션 종료 시 전송 유실 방지)
- **D2 선택 기본값**: 진입 시 **매번 빈 선택**(아무 그룹도 체크 안 됨). 명시 선택 강제로 실수 전송 방지.

## 요구사항
- **[Must] FR1** — Share Extension 신규 타겟(XcodeGen). 활성화 규칙 = 공유 항목에 **웹 URL 1건** 포함 시 노출(`public.url`; plain-text에서 URL 추출 폴백).
- **[Must] FR2** — 진입 시 내 그룹 목록(`GET /chat/bot/rooms`)을 체크박스 멀티선택으로 표시(그룹명 + 멤버수). 기본 빈 선택(D2). 최소 1개 선택해야 [보내기] 활성.
- **[Must] FR3** — [보내기] → 선택 그룹마다 `POST /chat/bot/{groupId}/messages`(text=공유 URL). 봇이 URL→장소 추출(릴스 저장).
- **[Must] FR4** — 인증: **App Group + 공유 Keychain**으로 메인 앱 토큰 읽기. 만료 시 공유 refreshToken으로 갱신(KeychainTokenStore.performRefresh 재사용). 토큰 없음/갱신 실패 → "앱에서 로그인 후 다시 시도해주세요" 안내 + 종료.
- **[Must] FR5** — `KeychainTokenStore`에 keychain access group 지원 추가(메인 앱·익스텐션 동일 group). 앱/익스텐션 entitlements에 App Group + Keychain Sharing 추가.
- **[Must] FR6** — 전송 신뢰성(D1): 모든 선택 그룹 전송 완료까지 대기(로딩) 후 `completeRequest`로 종료. 부분 실패 시 실패 그룹 안내(best-effort, 성공분 유지).
- **[Must] FR7** — 단위 테스트(VM: 선택 검증·다중 전송 호출 수·에러/부분실패 매핑). UI/익스텐션 통합·실제 공유 동선은 Mac/기기.

## 수용 기준 (AC)
- **AC1** — 인스타 릴스 공유 시트에 "우리가 갈 지도"가 노출되고, 선택 시 익스텐션 UI가 뜬다.
- **AC2** — 내 그룹들이 체크박스로 표시되고(빈 선택 기본), 0개 선택이면 [보내기] 비활성.
- **AC3** — 2개 이상 선택 후 [보내기] → 각 그룹 봇방에 URL이 전송되고(전송 수 = 선택 수), 봇이 장소를 추출/저장한다.
- **AC4** — 전송이 모두 끝날 때까지 로딩 표시 후 종료(전송 중 닫힘 없음). 토큰 없음/만료 시 로그인 안내 + 정상 종료(크래시 없음).
- **AC5** — 부분 실패 시 어떤 그룹이 실패했는지 안내, 성공한 전송은 유지.
- **AC6** — 미출시 전제로 access group 변경 후에도 메인 앱 로그인 동작(토큰 저장/조회 정합).
- **AC7** — CI(macOS 시뮬) 빌드 + 단위 테스트 통과. 실제 인스타 공유 동선은 실기기(DoD-B).

## 범위 밖
- 백엔드 변경(재사용) · 봇 처리 로직 · 안드로이드 · 딥링크
- Apple Developer portal App Group 등록 / 기기 provisioning (Mac DoD-B)
- 전송 후 앱 자동 열기(닫기만) · 마지막 선택 기억(D2로 미채택)
