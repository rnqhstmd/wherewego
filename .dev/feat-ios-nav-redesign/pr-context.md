# PR Context — P7 iOS 내비게이션 재설계

## 비즈니스 맥락

### 배경
현재 iOS 앱은 하단 3탭(지도/봇/커플) 위에 지도 화면 안 별도 액션바(검색·추가·룰렛)가 겹쳐 "가로 바 두 줄" 혼란을 유발했다. 릴스 링크로 장소를 저장하는 "봇방"이 커플 대화와 같은 채팅방 급으로 배치돼 성격이 어긋났고, 알림함·내정보(설정) 화면은 iOS에 없어 웹으로만 접근 가능했다.

### 목표 (PRD)
- 하단 내비게이션을 **단일 5탭**(어디갈까·채팅·＋·알림·내정보)으로 통일하고 지도 내 별도 액션바 제거
- 장소 추가를 가운데 **＋(센터 액션)** 하나로 통합(검색 + 지도 콕찍기)
- 릴스 저장을 **채팅 탭 직행** 최단 동선으로
- 알림함·내정보를 **웹→SwiftUI 이식**으로 신규 구현
- iOS 17~25 솔리드 폴백 / iOS 26+ Liquid Glass
- 1:1 커플챗 **제거**(제품 결정)

### 핵심 제약
- **백엔드 추가 개발 0** — 알림(NotificationV1Controller `/api/v1/notifications` 3엔드포인트)·닉네임 PUT·그룹탈퇴(DELETE members/me)·계정삭제(DELETE users/me) 전부 기존 REST 재사용. ＋ 역지오코딩은 온디바이스 CLGeocoder.
- 순수 SwiftUI 작업.

### 주요 요구사항 (Must)
5탭 IA·＋통합추가(검색+콕찍기+역지오 디바운스300ms·좌표폴백)·채팅직행·커플챗삭제·알림함(진입 read-all·미읽음 빨간점·flyTo·삭제핀 가드)·내정보(닉네임/그룹탈퇴/로그아웃/계정삭제 확인다이얼로그)·딥링크 정리(.coupleChat 제거, COUPLE_MESSAGE→.chat).

## 범위·DoD
- **본 PR(DoD-A)**: 정적 SwiftUI 구현 + 빌드무관 단위 테스트(AC-1~11) + qa/보안 정적 리뷰 + 인수검증 ACCEPT.
- **DoD-B 이연(Mac/Xcode)**: 시각 픽셀 QA(AC-V1~10), iOS26 Liquid Glass modifier 실적용, TestFlight·앱스토어 제출, XCTest 실행. iOS는 Windows/비-Mac에서 빌드 불가하여 분리(P4~P6 동일 패턴).

## Audit Summary
- 총 13건 (CRITICAL: 0, HIGH: 3, MEDIUM: 5, LOW: 2, ASSUMPTION: 3) — 보안 CRITICAL/HIGH 취약점 없음
- QA Critical 1건(테스트 `KeychainTokenStore()` 무인자 init 없음 → `DummyTokens()` 교체) **수정 완료**
- 보안 HIGH 3건 전부 검증 결과 false positive/기해소: 세션잔류=`logout`이 `tokens.clear` 호출 / BotChat CoupleChat참조=주석만 / 계정삭제 cascade=P2 백엔드 처리(로드맵 확정)
- 품질 개선 반영: MyInfoView @ObservedObject 일관화, 알림 재조회 깜빡임 방지, performLogout nil폴백 currentUser.clear 하드닝
- 잔존 참조 0(.botChat/.coupleChat/SearchPin/Crosshair)
- 이월(비차단): 알림 상세 실패 피드백·내정보 재시도 버튼·Coordinate epsilon·CLGeocoder cancel·onForeground 중복가드 (trust-ledger.md 참조)

## 인수 검증
**ACCEPT** — Must AC-1~11 9/9 충족. AC-V1~V10 DoD-B 이연(렌더 로직 코드 작성됨).

## 설계 문서
- `docs/superpowers/specs/2026-06-02-ios-nav-redesign-design.md` (상위 설계)
- `.dev/feat-ios-nav-redesign/` (prd.md / design.md / trust-ledger.md / self-check.md)
