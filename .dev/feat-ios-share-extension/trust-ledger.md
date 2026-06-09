# Trust Ledger — Share Extension

## 통합 감사 (review) — 오케스트레이터 직접(읽기 전용 에이전트 미반환)

### QA (스펙 충족) — CERTAIN 0건
- AC1 활성화 규칙(Info.plist NSExtension URL 1건)·AC2 체크박스+빈선택·AC3 다중전송(테스트 검증)·AC4 전송완료 대기+로그인 안내·AC5 부분실패 집계(테스트)·AC6 access group·AC7 CI+테스트 → 충족.
- [QUESTION/Minor] 토큰이 send 도중 만료되면: ShareAPIClient가 401→refresh 1회 재시도하므로 대개 투명 처리. refresh도 실패하면 그 그룹은 "전송 실패"로 집계(loginRequired 전환 아님). 드문 케이스라 수용.

### 보안 감사 — CRITICAL 0건
- [확인] 토큰 공유 = 키체인 access group(App Group). 토큰은 키체인(암호화)에만 존재, UserDefaults/파일 노출 없음. 동일 팀/App Group 앱만 접근.
- [확인] kSecAttrAccessibleAfterFirstUnlock — 메인 앱과 동일. 익스텐션이 잠금 후에도 토큰 읽기 가능(필요).
- [확인] 신규 비밀 0. 기존 auth 재사용. 전송 URL은 사용자가 명시 공유 → 봇 처리 경로(수동 붙여넣기 동치), 신규 공격면 없음.
- [확인] refresh token은 공유 키체인에서 읽어 /auth/refresh로만 사용(Bearer 불요), 응답 토큰 키체인 갱신.
- [INFO] App Group provisioning(기기/릴스)은 Apple Developer portal 등록 필요 → Mac DoD-B. CI(시뮬, 서명 OFF)는 무관.

### 빌드 게이트
- iOS Windows 빌드 불가 → GitHub Actions(macOS 시뮬)가 WhereWeGo 스킴+ShareExtension 의존성 빌드 + ShareViewModelTests 검증. **실제 공유 동선은 실기기(DoD-B)**.

### 종합
- Critical/CERTAIN 0건. Swift 6 Sendable·NSItemProvider·XcodeGen 익스텐션 정합은 CI에서 1차 검증(메모리 교훈: iOS 컴파일 결손은 CI에서 노출 → push 후 watch). 진행 가능.
