# Trust Ledger — D단계: 알림 상세 / 내정보 축소 / 그룹관리 (review)

> qa-manager·security-auditor 미반환 환경 → 오케스트레이터 직접 QA + ZT 통합 감사.
> 대상: 풀스택 diff(백엔드 14 + iOS 17 + 테스트). Mechanical Gate 백엔드 통과, iOS 실행=Mac DoD-B(리뷰어).

## Mechanical Gate (실행 검증)
- 백엔드 `compileJava compileTestJava`: **BUILD SUCCESSFUL**
- `GroupMemberServiceTest`(단위 — 방장 판정·자동 승계·삭제 권한): **통과**
- `GroupV1ControllerIntegrationTest` + `NotificationServiceIT`(통합, PostgreSQL): **통과**
- iOS: Windows 빌드 불가 → Mac DoD-B(리뷰어). 시그니처/디코딩/동시성 직접 검토 완료.

## QA (스펙 충족)
- CERTAIN(Critical): 0 · Warning: 0 · Info: 1 · QUESTION: 0
- AC-1~8 전부 충족(self-check.md 표 참조). 백엔드 IT가 AC-4~8(멤버/방장/삭제/승계) 실행 검증.

### [Info] deleteGroup 전원 markLeft N+1
- `GroupMemberService.deleteGroup`이 멤버별 `findActiveByGroupIdAndUserId` 재조회. 멤버 수 작아 허용(설계 §6 R2, 비차단).

## ZT 통합 감사 (정책/보안/허점)
- CRITICAL: 0 · HIGH: 0 · MEDIUM: 0

### 점검 항목 (모두 통과)
- **인가**: `listMembers`/`renameGroup` = `requireActiveMembership`(비멤버 `GROUP_NOT_MEMBER`). `deleteGroup` = 방장(joined_at 최소)만, 비방장 `GROUP_OWNER_REQUIRED`(403). iOS는 `isOwner`일 때만 삭제 버튼 노출 + 백엔드 이중 검증(UI 우회해도 403).
- **데이터 노출**: 알림 `groupName`은 자기 알림(`receiverId`)의 그룹만 노출 — 권한 경계 내. soft-delete 그룹명 노출은 "어느 그룹" 맥락 의도(본인이 속했던 그룹).
- **입력 검증**: `updateGroupName` trim + 30자 가드(createGroup 동일). 그룹명 표시는 SwiftUI `Text` 자동 이스케이프(XSS 무관).
- **동시성**: `renameGroup`/`deleteGroup`/`leaveGroup` 모두 `findByIdForUpdate` 비관락으로 직렬화 → 동시 삭제+탈퇴 race 방지. `markLeft` 멱등.
- **방장 판정 위변조**: 조회 시점 `joined_at` 최소 계산(클라이언트 입력 무관) → iOS `isOwner`는 표시용일 뿐 권한은 백엔드 재계산.
- **신규 권한/자격**: 없음(네트워크/푸시/위치 변경 없음).

### 회귀
- `MyInfoView` leaveGroup 제거 → `GroupManageViewModel.leave`로 이전, 호출 정합(grep).
- `NotificationService` 생성자 변경(GroupRepository 주입) → IT 통과로 DI 검증.
- `GroupAPIProtocol` 신규 3메서드 → 전 stub 구현(compileTestJava·iOS grep 정합).

## 미해결 항목
없음. (Critical 0 · CRITICAL 0 · QUESTION 0)

## 잔여(비차단) — 리뷰어 인수
- iOS 빌드/시뮬/단위테스트 **실행** = Windows 불가 → **Mac DoD-B(리뷰어)**. 타입/시그니처/디코딩/Swift 동시성 직접 검증 완료.
