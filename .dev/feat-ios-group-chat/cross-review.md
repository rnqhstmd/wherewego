# Cross-Review 결과

- advisor: claude (오케스트레이터 직접 수행 — qa-manager/security-auditor 읽기 에이전트 미반환, 자가 검증 한계 명시)
- 브랜치: feat/ios-group-chat (base: develop)
- DEV_DIR: .dev/feat-ios-group-chat
- 실행: 2026-06-10
- 미션: 산출물(PRD AC / 설계 변경범위) 약속 대비 충실도 + trust-ledger·self-check 미보고 신규 위험만

## AC 충족 매트릭스

| AC | 충족 | 근거 |
|----|------|------|
| AC-1 봇 메시지(BOT·PLACE_CARDS·PROCESSING·MEMO_PROMPT) 미노출 | O | GroupMessageRow.body: PLACE_CARDS/PROCESSING/MEMO_PROMPT → EmptyView |
| AC-2 같은 릴스 재공유 시 2번째 자동 등록됨 | O | registered 서버 파생만 신뢰(GroupChatFrame.registered) — 백엔드 UNIQUE 정합. 클라 표시 책임만 |
| AC-3 타인 REEL_LINK 「등록하기」 비활성 + 403 방어 | O | GroupMessageRow.reelButton ②비활성, GroupChatViewModel.runExtract CHAT_EXTRACT_FORBIDDEN→failed |
| AC-4 저장 후 발신자·타인 ③상태 갱신 | O | saveFromWizard → reconcileLatest 교체-병합(registered 갱신) |
| AC-5 다른 그룹 릴스도 전환 후 필터 | O | MapViewModel.focusReel(groupId:) self.groupId≠target → switchTo |
| AC-6 빈 방·그룹0·추출실패·네트워크 안내 | O | GroupChatView.emptyState, DMListView.empty/error, RegisterState.empty/failed |
| AC-7 발신자 구분(senderUserId==내 id) | O | GroupMessageRow.isOutgoing, GroupChatViewModel.currentUserId |
| AC-8 URL 단독만 REEL_LINK | O | GroupChatViewModel.send → InstagramURL.isReelURL(공백 혼합 false) |

**[Must] FR-GC2-1~8 전체 충족, [Should] 셸 정리 FR-GC2-9~11 충족.** AC 미충족 0건.

## 설계 범위 이탈

설계서 §1 변경 범위와 실제 변경의 차이(모두 정당 — 문서 정합성 권고 수준):

- **InstagramURL.swift (신규)** — 설계 §1 신규 파일 표에 누락. 단 §6 결정·구현순서 1단계엔 명시. → 문서 불일치(코드는 의도대로).
- **FloatingTabBar.swift (수정)** — 설계 §1 수정 표에 미기재(MainTabView "탭 라벨"로 뭉뚱그림). 탭명 "DM"→"채팅"의 실제 위치. → 문서 불일치.
- **GroupChatView 자체 스크롤** — 설계 §1은 "ChatScrollContainer 재사용"이라 명시했으나, ChatScrollContainer가 `[ChatFrame]` 전용이라 GroupChatFrame과 타입 불일치 → 동일 패턴(ScrollViewReader/loadMore/하단추적)을 자체 구현. → 설계 약속과 구현 방식 차이(불가피, 정당).
- **ShareDTO.swift 무변경** — 설계는 "ShareGroup 필드 정합" 수정 예정이었으나 ShareGroup이 groupId/groupName만 디코딩해 이미 호환 → 무변경. → 설계보다 적게 변경(긍정적).

## 신규 위험

(trust-ledger / self-check 미보고 항목만)

### Critical
- 없음.

### Warning
- 없음.

### Info
- [범위] GroupChatView가 ChatScrollContainer 대신 자체 스크롤 컨테이너를 구현 — 향후 ChatScrollContainer 제네릭화로 중복 제거 여지(GC-3 봇 정리 시 ChatFrame 경로가 사라지므로 그때 통합 검토). 현 시점 dead code 격리 관점에선 자체 구현이 합리적.
- [문서] InstagramURL.swift·FloatingTabBar.swift가 설계 §1 변경범위 표에 누락 — design.md §1 갱신 권고(추적성).
- [시각] ReelRegisterSheet의 presentationDetents가 상태 전환(extracting `[.medium]` → wizard `[.medium,.large]`)마다 달라짐 — iOS에서 동적 detent 변경 시 시트 리사이즈 애니메이션이 어색할 수 있음. DoD-B(Mac/실기기) 시각 확인 권고.

## references 위반
- references/ 디렉토리 없음 → 해당 없음.

## 총평
- **강점**: ① PRD AC 8/8 전부 코드 근거로 충족 ② 설계의 핵심 위험(registered 교체-병합, 딥링크 전환, willPresent 동시성)이 단일 경로/가드로 통제됨 ③ trust-ledger/self-check가 트레이드오프를 이미 투명하게 기록.
- **합산**: Critical 0, Warning 0, Info 3 (모두 문서 정합성/시각 — 코드 동작 결함 아님).
- **권고**: 코드 변경 불요. design.md §1 변경범위 표에 InstagramURL·FloatingTabBar를 추가하고 "ChatScrollContainer 재사용→자체 구현(타입 불일치)" 단서를 보강하면 문서 정합성이 완성됨. 시각 항목은 DoD-B로 이연.
- **한계**: 본 cross-review는 구현 작성자(동일 Claude)가 수행해 독립적 모델 관점이 아니다. 진정한 교차 검증이 필요하면 `--advisor codex`로 재호출 권장.

## 처리 결과
- **#2 design.md 문서 정합 → 반영됨**: §1 신규 표에 `InstagramURL.swift` 추가, 수정 표에 `FloatingTabBar.swift` 추가, `GroupChatView` 행에 "ChatScrollContainer 타입 불일치→자체 구현" 단서 보강, `ShareDTO` 무변경 정정.
- **#1 GroupChatView 자체 스크롤 → 기록만**: GC-3 봇 정리 시 ChatScrollContainer 제네릭화 통합 검토(현 시점 dead code 격리상 합리적).
- **#3 ReelRegisterSheet detent 시각 → 기록만**: DoD-B(Mac/실기기) 이연.
