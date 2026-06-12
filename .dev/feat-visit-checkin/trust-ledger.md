# Trust Ledger — 방문 체크인·추억 전환 정책 v2

## 통합 감사 (review, 2026-06-12)

### Mechanical Gate
- 백엔드: `./gradlew :apps:wherewego-api:test`(PinVisitServiceIT 7 + GroupChatServiceIT) — 27중 2실패는 **develop 선행 실패 확정 항목**(thumbnailUrl·rooms preview, 본 변경 무관). 신규 8케이스 전부 PASS
- iOS: Windows 빌드 불가 → CI(GitHub Actions)가 최종 게이트(push 후 확인 필수)

### QA 리뷰 (스펙 충족)
- CERTAIN 0건 / QUESTION 0건. AC-1~8 전 항목 코드·IT 대조 충족(self-check.md 참조)

### ZT 통합 감사
- CRITICAL/HIGH/MEDIUM 0건. 점검 근거:
  - [권한] declareVisit — requireActiveMembership + 비관 락 + sanitizeCompanions(그룹 활성 멤버만, 본인 자동 제거) ✓
  - [경로 차단] 공개 postMessage 는 PIN_VISIT/PIN_MEMORY 계속 CHAT_KIND_INVALID 400 — 카드 위조 불가 ✓
  - [트랜잭션] 카드 적재 = 핀 전환 동일 트랜잭션, 푸시는 afterCommit(유령 푸시 방지 — GP-1 선례) ✓
  - [개인정보] 푸시 본문 고정 문구("멤버가 추억을 남겼어요") — 위치·명단 미포함 ✓
  - [데이터 삭제] V023 의 VISIT_DETECTED DELETE — 사용자 승인 결정(PRD Q3) + CHECK 제약 재정의 정합 ✓

### 기록 항목 (수정 불요)
- [Warning/설계 이탈·정당] PinShareCard.swift 수정 — VisitToastView 삭제로 formatDate 유틸을 VisitCompanionSheet 로 이관하며 참조 갱신(삭제 파생, 불가피)
- [Info/ASSUMPTION] 신규 MessageKind 2종(PIN_VISIT·PIN_MEMORY)은 구버전 앱이 디코드 불가 — **서버·앱 동시 배포 전제**(베타 수용, 설계 §4)
- [Info] MockBean deprecation 경고 — 기존 코드베이스 전반의 경고, 본 변경 무관
