# Cross-Review 결과

- advisor: claude (qa-manager + security-auditor cross-review 미션 — 메모리 관례에 따라 오케스트레이터 직접 수행)
- 브랜치: feat/ios-ig2-content (base: develop)
- DEV_DIR: .dev/feat-ios-ig2-content
- 실행 시각: 2026-06-12 (PR #125 생성 후)
- 입력: prd.md · design.md · trust-ledger.md · self-check.md · codemap.md · diff(stat, 32파일 +1,291/−443)

## AC 충족 매트릭스

| AC | 충족 | 근거 |
|----|------|------|
| AC-1: 채팅방 버블·입력바·헤더 목업 v4 문법 + REEL 3상태 회귀 없음 | **O** | 수신 꼬리 6r `GroupMessageRow.swift:85`(UnevenRoundedRectangle) · 발신 cta 20 동일 shape 분기 · 아바타 28pt 묶음 마지막 `GroupMessageRow.swift:239` · 헤더 principal `GroupChatView.swift:38`(GroupAvatarView 28 + 멤버 N명 :70) · reelButton 3상태 로직 diff상 무변경. 시각 최종 대조는 Mac DoD-B(비범위) |
| AC-2: 진입 최신 + 앵커 삭제 + 배너·미읽음 정상 | **O** (각주) | onAppear 무조건 `scrollToBottom` `GroupChatView.swift:133` · 앵커 4심볼(initialUnreadAnchorId/serverLastReadId/initialUnreadCount/didInitialScroll) 전 코드베이스 grep 0건 · showNewMessagePill·onChange 분기 유지. **각주**: 계획된 GroupChatViewModelTests 수정은 실행되지 않음 — 재점검 결과 앵커 참조 테스트가 애초 부재(7건은 전부 reconcileLatest 호출)했고, 플래키 하드닝은 VM `disappear()` await로 수행(아래 범위 이탈 ④ 참조) |
| AC-3: 내정보 헤더·편집 버튼·설정 3행 + 편집 화면 | **O** | 통계 2종 `MyInfoView.swift:167-168` · 편집 시트 배선 `:84` · 알림 설정 시스템 딥링크 `:254` · `ProfileEditView.swift` 신설(프사 플로우 공유 + Nickname 검증 + 완료 규칙) |
| AC-4: 알림 탭 → 지도·그룹·말풍선(1)/fitBounds(N) 한 탭 | **O** | `.pinFocus` 발화 `NotificationInboxViewModel.swift:149` · 소비 `MainTabView.swift:348` · `MapViewModel.focusPins` `:521`(1개=flyTo+selectedPinId/N개=fitBounds) · 가드 2종(떠난 그룹·삭제 장소) 토스트 |
| AC-5: 백엔드 필드 4종 + 테스트 green | **O** | `UserV1Dto.java:22`(pinCount) · `UserService.java:109`(countActiveByCreatedBy) · NotificationItemResult/DTO 3필드(diff 확인) · 신규 통합 테스트 3건 PASS(실패 2건=develop 대조 선행 실패) |
| AC-6: iOS CI green | **O** | PR #125 — Build & Unit Test (iOS Simulator) **pass** (5m7s, run 27389044959) |

**[Must] 6/6 충족** (AC-2 각주 1건 — 계획 항목의 대상 부재 확인으로 갈음).

## 설계 범위 이탈

| # | 파일 | 변경 요약 | 평가 |
|---|------|----------|------|
| ① | `UserV1ApiSpec.java` | pinCount 스웨거 서술 1줄 보강 | 설계서 §5 미명시·구현 계획 #2에도 없음. **정당** — 응답 계약 변경 시 ApiSpec 동기화는 레포 관례(GP-1 선례) |
| ② | `context/ig-redesign-plan.md` | IG-2 완료 표기 + 편차 5건 기록 | phase-complete Step 4 환류(사용자 승인) — 프로세스 산출물, 이탈 아님 |
| ③ | `.dev/feat-ios-ig2-content/*` 7종 | 파이프라인 산출물 | 레포 관례(.dev 커밋), 이탈 아님 |
| ④ | `GroupChatViewModelTests.swift` **미수정** | 계획 #9(앵커 테스트 삭제+하드닝)가 코드 수정 없이 종결 | **역이탈(계획 미실행)** — 앵커 테스트 부재 grep으로 확인, 플래키 하드닝은 VM disappear await가 대체. 결과적으로 정당하나 설계서와 diff가 불일치하므로 기록 |

→ 실질 이탈 0건 (①은 관례상 정당, ④는 대상 부재).

## 신규 위험

(trust-ledger·self-check에 없는 항목만)

### Critical
- 없음.

### Warning
- 없음.

### Info
- [GAP] `MyInfoView.swift:168` — 핀 통계가 `viewModel.pinCount ?? 0`으로 렌더되어, **백엔드 develop→main 미배포 기간(구서버) 동안 실제 핀 수와 무관하게 "0"이 표시**된다.
  - 근거: PRD FR-5/설계 §0은 "구서버 호환=옵셔널 디코딩"만 약속했고 표시 폴백 정책은 미정의. nil(필드 부재)과 실값 0이 화면상 구분 불가.
  - 권고: `pinCount`가 nil이면 "–" 표시(또는 통계 항목 숨김)로 한 줄 수정. 또는 main 배포가 임박했으므로 수용하고 배포 순서로 해소.

## 총평
- **강점**: ① 약속 추적성 — PRD 수용 기준 6건 전부 파일:라인 근거로 닫힘, CI까지 green. ② 죽은 코드 정리 철저 — 앵커·상세 화면 관련 심볼 잔존 0건(grep 검증). ③ 모든 백엔드 확장이 추가형+옵셔널 디코딩으로 배포 시차 안전.
- **합산**: Critical 0 · Warning 0 · Info 1 (+ 기록성 각주 2: AC-2 테스트 계획 미실행 사유, ApiSpec 관례 이탈).
- **권고**: Info 1건(pinCount nil 폴백 표시)만 판단하면 머지 가능 상태. 시각 정합은 Mac DoD-B에서 목업 v4 대조.

## 처리 결과
- 1번 항목 (Info `MyInfoView.swift:168` pinCount nil→0 오인): **수정됨** — statItem을 문자열 기반으로 전환, nil이면 "–" 표시(사용자 승인, 오케스트레이터 직접 수정).
