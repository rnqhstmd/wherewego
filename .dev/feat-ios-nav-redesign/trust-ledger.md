# Trust Ledger — P7 iOS 내비게이션 재설계

## 통합 감사 (review) — security-auditor (sonnet)
date: 2026-06-03
요약: CRITICAL 0 / HIGH 3 / MEDIUM 5 / LOW 2 / ASSUMPTION 3. 보안 CRITICAL/HIGH 취약점(인젝션·하드코딩 시크릿·인가 우회) 없음.

### 오케스트레이터 검증 후 처리 (false positive / 기해소)
- [HIGH→해소] 계정 삭제 후 세션 잔류 우려 — `SessionStore.logout()`이 `tokens.clear()`로 Keychain 토큰 삭제(SessionStore.swift:42-44). 토큰 잔류 없음. defer는 scope 종료(=performLogout 후) 실행이라 순서 오해.
- [HIGH→완화] logoutHandler nil 폴백 시 디바이스 토큰·CurrentUser 미정리 — prod는 MainTabView가 `dependencies.logout`(전체 정리)을 **항상 주입**. nil은 테스트 경로만. (선택 하드닝: nil 폴백에 currentUser.clear 추가 가능)
- [HIGH→수용] 알림 flyTo 핀 미로드 시 no-op 무피드백 — 설계서 §3 "로드 전 flyTo=best-effort no-op" 명시 수용. 기존 MainTabView 동작과 동일(회귀 아님).
- [GAP/HIGH→해소] 계정삭제 그룹 cascade 미정의 — P2 백엔드 `DELETE /users/me`가 개인데이터+마지막1인 시 그룹/핀 삭제+Apple revoke 처리(로드맵 확정). 클라 선행 탈퇴 불요.
- [GAP/HIGH→false positive] BotChatViewModel CoupleChat 참조 — **주석만**(BotChatViewModel.swift:254 `// ...CoupleChatViewModel.reconcileLatest 와 대칭`). 코드 참조 아님. 컴파일 무관.

### 이월 (MEDIUM/LOW — 비차단, 후속 개선 후보)
- [MEDIUM] 알림 상세 selectItem 실패 시 사용자 피드백 없음(activeDetail=nil 무음).
- [MEDIUM] 내정보 load 실패 시 재시도 버튼 없음(BR-6는 알림함만 명시).
- [MEDIUM] AddPlaceViewModel Coordinate == 부동소수점 비교(역지오 중간좌표 가드) — epsilon 미고려.
- [LOW] CLGeocoder 동시호출 취소(cancelGeocode) 미적용 — 디바운스로 완화, 실패 시 좌표 폴백.
- [LOW] onForeground 중복 호출(앱 콜드스타트 .task + scenePhase .active) — list 2회 가능, 가드 없음.
- [ASSUMPTION] 좌표 number 직렬화 가정(JacksonConfig WRITE_BIGDECIMAL_AS_PLAIN) — 통합테스트 픽스처로 계약 동결 권장.
- [ASSUMPTION] PIN_SAVED 푸시에 향후 pinId 추가 시 .map만 라우팅(무음 회귀 가능) — payload 스펙 동결 권장.

## QA 최종 리뷰 — qa-manager (sonnet)
- [Critical→수정완료] MyInfoViewModelTests `KeychainTokenStore()` 무인자 생성자 없음(actor, init(baseURL:)만) → 컴파일 에러. `DummyTokens()`로 교체.
- [Warning 이월] MyInfoView @StateObject가 매 body 재생성 시 MyInfoViewModel 생성자 불필요 호출(perf/일관성) — MainTabView가 @StateObject 소유 + MyInfoView @ObservedObject 권장.
- [Warning 이월] NotificationInboxViewModel.load() 에러 후 재시도 시 loadState=.loading이 기존 items 덮음(UX 깜빡임).
- AC-1~11 전부 충족(코드+테스트 정합 확인).
