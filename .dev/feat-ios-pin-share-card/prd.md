# PRD — iOS 핀 공유 카드 (웹 Phase 9 이식)

## 배경
웹(frontend)에는 핀 공유 카드(Phase 9, `renderPinCard.ts`)가 있어 1080×1350 PNG를 생성한다.
지도 배경(흑백+blur+베이지 오버레이) 위에 메모/장소명/날짜/작성자/워터마크를 얹은 카드다.
**iOS 앱에는 이 기능이 전혀 없다** — 핀 상세에 공유 진입점 자체가 없다.
사용자 요청: "iOS 공유 카드를 웹과 일치시켜라" → 실제로는 **신규 이식**.

## 요구사항
- FR-1: 핀 상세(PinDetailContent) 헤더에 공유 버튼을 추가한다. 탭 시 공유 카드 시트를 띄운다.
- FR-2: 웹과 동일한 1080×1350(4:5) PNG 카드를 생성한다.
- FR-3: 지도 배경 — Mapbox Static Images API(streets-v12, zoom15, 1024×1280) 직접 호출
  (iOS는 토큰 보유, CORS 무관 → 웹의 Next.js 프록시 대신 직접 호출). 실패 시 light-v11 재시도, 그래도 실패면 단색 `#EAE4D4` 폴백.
- FR-4: 배경 처리 — 흑백(채도0) → 핀 글리프 합성 → blur(2px) → 베이지 오버레이 `#EAE4D4 @0.35`.
- FR-5: 핀 글리프 — 자기 핀(중앙, 흰 원 r24 + 글리프 40) + 그룹 핀(흰 원 r16 + 글리프 24).
  글리프: REEL=원/WISH=별/MEMORY=하트, 태그 색(pinReel/pinWish/pinMemory). 좌표는 Web Mercator 투영.
- FR-6: 텍스트(Pretendard) — 메모 60/semibold, 장소명 36/bold(+태그 글리프 28), 날짜·주소 22, "written by {작성자}" 22, 워터마크 "우리가 갈 지도" 24/semibold(좌하단).
- FR-7: 공유 시트 — 카드 미리보기(4:5) + 시스템 공유(ShareLink, PNG) + 사진 저장. 로딩/실패 상태 표시.
- FR-8: 카드 작성자 라벨 = `createdByNickname ?? "익명"`(웹 PinShareSheet 동치). 날짜 = `createdAt`(웹 카드 동치).

## 수용 기준
- AC-1: 핀 상세에 공유 버튼이 보이고, 탭하면 카드 시트가 뜬다.
- AC-2: 카드 배경이 웹처럼 흑백+blur+베이지 톤이다(컬러 지도 그대로 노출 금지).
- AC-3: 폰트/사이즈/색/워터마크가 웹 스펙(§FR-6)과 일치한다(Pretendard, ink #1A1A2E 계열).
- AC-4: Mapbox 토큰 미설정/네트워크 실패 시에도 크래시 없이 단색 폴백 카드가 생성된다.
- AC-5: 공유 시트에서 시스템 공유(인스타/메시지/저장/복사)가 동작한다.
- AC-6: 순수 로직(Static URL 빌더, geo→pixel 투영)은 단위 테스트로 검증되며 iOS CI가 통과한다.

## 비목표(이번 범위 아님)
- 웹의 "링크 복사(딥링크)" 별도 버튼 — 시스템 공유 시트가 링크/복사를 포괄하므로 생략(후속 가능).
- Pretendard SemiBold/Bold 별도 번들 — 앱 전역이 Regular+weight 관례이므로 동일 관례 사용(시각 미세차 DoD-B에서 판단).
- 픽셀 단위 leading 미세 일치 — Mac 시각 QA(DoD-B)에서 튜닝.

## 검증 제약
- Windows 빌드 불가. 컴파일/시각 검증은 iOS CI(빌드+단위테스트) + Mac DoD-B로 위임.
