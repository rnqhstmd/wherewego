# Trust Ledger — iOS P5

## 통합 감사 (review, security-auditor) — CRITICAL 0 / HIGH 1 / MEDIUM 7 / LOW 4

### HIGH
- [GAP] DemoSeedRunner 멱등 재기동 시 user2 refreshToken 해시 재설정 누락
  - 근거: 재시드 시 `applyDemoRefreshToken(user1)`만. user2는 데모 로그인 대상 아님(설계 의도).
  - 권고: Javadoc에 "데모 로그인 user1 한정" 명시(문서화). 기능 GAP 아님.

### MEDIUM (주요)
- [RISK] 데모 회전 예외 게이트 독립성: `@ConditionalOnProperty(enabled)` 시드 게이트와 `matchesDemoAccount` 회전 예외가 독립 → 운영 enabled=false라도 oauthId env 주입 시 회전 예외 발동 가능.
  - 권고: `matchesDemoAccount`에 `enabled` 게이트 추가(방어). **→ 이번 수정 반영**
- [ASSUMPTION] 데모 oauthId가 실제 카카오 ID와 충돌 시 일반 사용자 회전 영구 스킵 위험.
  - 권고: enabled 게이트로 운영(false)에서 무력화. DEMO 식별자 규약 권장. **→ enabled 게이트로 완화**
- [GAP] BR-6 vs 데모 시드: REEL 핀에 DEMO_REEL_URL 저장(런타임 앱은 instagramUrl=nil) → 불일치.
  - 권고: 데모 시드 핀 instagramUrl=null로 통일. **→ 이번 수정 반영**
- [GAP] 로그아웃 시 디바이스 unregister 실패 창(reassign이 다음 등록 시 보완) — best-effort 설계 범위. 문서화.
- [ASSUMPTION] STOMP 구독 인가 거부(ERROR) 시 무한 reconnecting 가능 — 무음 장애. P6 보완 권장(이번 비반영, 기록).
- [ASSUMPTION] 데모 refresh JWT TTL 만료 시 데모 로그인 불가 → 심사 기간보다 긴 TTL·재시드 절차 운영 문서 필요.
- [POLICY] BR-3 서버 길이 검증 → **확인 완료(닫힘)**: `ChatV1Dto @Size(max=2000/1000)` 존재.

### LOW
- [RISK] STOMP CONNECT Bearer 평문 — 운영 wss(보호), Debug ws(로컬 전용). 문서화.
- [RISK] 운영 IPA에 DEMO_REFRESH_TOKEN 포함 시 백도어 — 심사 후 CI에서 placeholder 재설정 정책 필요.
- [GAP] CoupleChatViewModel.reconcileLatest가 cursor/hasMore 미갱신 → loadMore 정합 위험. **→ 이번 수정 반영**
- [RISK] DeepLinkRouter pinId 범위 미검증(음수 허용). **→ 이번 수정 반영(pinId>0)**

## QA 리뷰 (review, qa-manager)
- [CERTAIN/Critical] StompClient 타임아웃 Task 미취소 → 연결 성공 후에도 잔존, 특정 조건 재연결 루프 유발. **→ 이번 수정 반영(timeoutTask cancel)**
- [QUESTION] Q1 PROCESSING 프레임 roomId=userId(의미 오류, 미사용) → roomId=0+주석. **→ 반영**
- [QUESTION] Q2 subscribe-after-load 레이스 창(첫 진입 STOMP 프레임 소실 가능) → subscribe before load. **→ 반영**
- [QUESTION] Q3 데모 시드 instagramUrl → null(위 BR-6 GAP과 동일). **→ 반영**
- [Warning] receiveLoop Swift6 동시성 경고(컴파일 가능, Mac 검증), ChatScrollContainer onAppear loadMore(P6), AppDependencies 캡처 불일치(무누수), makeProcessingFrame roomId(Q1).

## 자기점검 (implement) — Critical 5건 수정 완료
isMine 항상 true / 글자수 trimmed 불일치 ×2 / 절단전송 / 단일콜백 → 전부 수정.

## 미답변/이월 (P6·운영 문서)
- AC-16 앱 아이콘 PNG: Mac에서 1024 PNG 추가 필요(구조만 스캐폴드).
- STOMP 인가거부 무한 reconnecting 보완(P6).
- 데모 토큰 JWT TTL·운영 IPA placeholder 재설정 CI 정책(운영 문서).
