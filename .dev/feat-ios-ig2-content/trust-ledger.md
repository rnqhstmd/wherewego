# Trust Ledger — IG-2 (2026-06-12)

> qa-manager·security-auditor 역할 직접 수행(메모리 관례 — 서브에이전트 보고 미반환 이슈 회피).

## 통합 감사 (review)

### CRITICAL
- 없음.

### HIGH
- 없음.

### MEDIUM
- [ASSUMPTION/MEDIUM] 채팅방 헤더 멤버 수 — `GroupContext.groups`(부트스트랩 시점) 조인이라 멤버 가입/탈퇴 직후 stale 가능.
  - 근거: GroupChatView.headerTitle ← DMListView groupContext 조인(GP-1 DMRoomRow 선례와 동일 수준).
  - 권고: MVP 수용(기존 목록 썸네일과 동일 신선도). 후속에서 방 진입 시 그룹 단건 재조회 검토.
- [GAP/MEDIUM] 알림 행 탭 → detail API 왕복 후 지도 이동 — 네트워크 지연 시 탭 반응이 늦게 느껴질 수 있음.
  - 근거: NotificationInboxViewModel.selectItem(설계 §4 의도된 트레이드오프 — 목록 응답 pinId 배열 미추가로 백엔드 표면 축소).
  - 권고: isRouting 가드로 중복 탭 차단 적용됨. 수용.

### 정책/보안 점검 (이상 없음)
- [POLICY] 신규 응답 필드 전부 추가형 + iOS 옵셔널 디코딩(decodeIfPresent 동치) — develop→main 배포 시차에서 구서버/구앱 양방향 호환 ✓.
- [RISK] thumbnailUrl(S3 public URL) 노출 범위 = 알림 수신자(그룹 멤버 본인 행만 조회) — 기존 PinSummary.photoUrl 과 동일 노출 수준, 신규 위험 없음 ✓.
- [RISK] registeredByProfileImageUrl — 그룹 멤버 프리뷰(GP-1 FR-4)로 이미 노출되는 정보 ✓.
- [RISK] `.pinFocus` 딥링크는 내부 라우팅 전용(푸시 type/Universal Link 파싱 미추가) — 외부 주입 표면 없음 ✓.
- [GAP] PRD 수용 기준 대비: AC-1~5 구현 확인(AC-1 시각 정합·AC-6 iOS CI 는 DoD-B/CI 게이트). PRD FR-5 의 "대표 pinId" 는 설계 §4 에서 detail API 재사용으로 의도적 축소(설계 승인됨).

## Mechanical Gate
- backend `./gradlew build -x test` EXIT=0 ✓
- backend 변경 통합 테스트 2클래스 타겟 실행 — 결과는 아래 "테스트 결과" 절.
- iOS 빌드+단위 테스트 = GitHub Actions CI(Windows 로컬 불가) — push 후 확인.

## 테스트 결과
- 작업 브랜치: UserV1(16개 중 2 실패) + NotificationV1(전부 통과). 신규 테스트(pinCount 2건·알림 3필드 1건) 전부 PASS.
- 실패 2건(GP-1 프사 제거·null 폴백)은 **develop 워크트리 대조에서 동일 실패 — 선행 실패 확정, IG-2 회귀 아님**.
- Gate 판정: 통과(회귀 0건).

## QA 리뷰 합산
- Critical: 1건 → 자기점검에서 즉시 수정 완료(썸네일 nil 생략 위반).
- Warning(이월, 시각 QA Mac DoD-B): 채팅 헤더 nil 폴백 표기 · 입력바 전송 아이콘 분기 · 알림 행 미읽음 점 위치.
- QUESTION: 없음.
