# Trust Ledger — DM 그룹별 봇방 목록 (review)

> qa-manager·security-auditor 미반환 환경 → 오케스트레이터 직접 QA + ZT 통합 감사.
> 대상: iOS diff 11파일(+635/-76). 백엔드 무변경. Mechanical Gate(빌드/테스트 실행)=Windows iOS 불가 → Mac DoD-B(리뷰어).

## QA (스펙 충족)
- CERTAIN(Critical): 0
- Warning: 1 (수정 완료)
- Info: 1
- QUESTION: 0

### [Warning/수정완료] DMListViewModel 무음 refresh 실패 시 스피너 무한 고정 가능
- 파일: ios/WhereWeGo/Features/Chat/DMListViewModel.swift (fetch catch)
- 근거: 콜드스타트에서 MainTabView.task 의 refresh()가 in-flight 인 동안 DMListView.task 의 load()가 isFetching 가드로 조기 반환 → 그 refresh()가 실패하면 showLoading=false 라 에러 미세팅 → loadState 가 .idle 잔존 → .idle 가 로딩 스피너로 렌더되어 무한 고정(다음 포그라운드 복귀까지 자가회복은 되나 소프트락).
- 수정: catch 에서 **기존 목록이 없으면(.idle/.loading/.error)** 무음 refresh 라도 .error 노출(재시도 경로). 이미 .loaded 면 화면 유지(무음). 테스트 ⑦ 추가(미로드 refresh 실패→.error).

### [Info] formatTime 중복
- DMListViewModel.formatTime 이 NotificationInboxViewModel.formatTime 과 동일 로직 중복(설계서 명시 인지). 후속 공용 유틸 통합 여지(범위 외, 비차단).

## ZT 통합 감사 (정책/보안/허점)
- CRITICAL: 0 · HIGH: 0 · MEDIUM: 0

### 점검 항목 (모두 통과)
- **인증/인가**: 봇 API 호출은 기존 APIClient(토큰 자동 부착) 경유. groupId 는 path Int(인젝션 불가). 백엔드가 활성 멤버십 강제(비멤버 403). iOS 는 사용자 본인 그룹 목록(botRooms 결과)만 진입시킴 → 권한 우회 경로 없음.
- **읽음 시맨틱 신뢰(BR-4)**: unread 판정은 백엔드(마지막 BOT & lastRead<latest) 그대로 신뢰. iOS 자체 계산 없음 → 클라이언트 위변조 표면 없음.
- **데이터 노출**: groupName/lastPreview 는 방 소유자(본인)에게만 표시. 로깅/print 추가 없음(PII 유출 없음).
- **deprecated 엔드포인트**: 백엔드 비그룹 `/chat/bot/messages` 잔존하나 iOS 소비 제거 → 보안 회귀 없음.
- **신규 권한/자격(entitlement)**: 없음(네트워크/푸시/위치 변경 없음).
- **Swift 6 동시성**: @MainActor VM, @unchecked Sendable 목, StateObject 래퍼 — 데이터레이스 표면 없음.

## 미해결 항목
없음. (Warning 1건 수정 완료, Critical/QUESTION 0)

## 잔여(비차단) — 리뷰어 인수
- iOS 빌드/시뮬/단위테스트 **실행** 검증은 Windows 불가 → **Mac DoD-B(리뷰어)**. 타입/시그니처/enum/동시성은 코드 리뷰 수준 직접 검증 완료(잔존 구 시그니처 0, 생성처 전수 정합).
