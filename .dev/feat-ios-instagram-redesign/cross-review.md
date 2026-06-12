# Cross-Review 결과 (IG-1)

- advisor: claude (직전 GP-1 cross-review에서 qa-manager·security-auditor 2/2 보고 미반환 확인 → 재시도 생략, 오케스트레이터 직접 수행)
- 브랜치: feat/ios-instagram-redesign (base: feat/group-profile-images, stacked)
- DEV_DIR: .dev/feat-ios-instagram-redesign
- 약속 원본: prd/design 부재(경량 구현 모드) → **context/ig-redesign-plan.md(승인 SSOT) + context/app-redesign-instagram.html(목업 v4)** 을 PRD/설계 대용으로 사용
- 검증 범위: IG-1 diff 1,607줄(iOS 9파일 + 문서 7파일, 백엔드 무변경 — 계획서 약속 부합) + PR #124 리뷰 반영분

## AC 충족 매트릭스 (계획서 IG-1 4항목 + 확정 스펙)

| 약속 | 충족 | 근거 |
|------|------|------|
| 1. InstaNavBar(48pt·Pretendard Bold 21·tracking -0.5·우측 액션 슬롯) | O | InstaNavBar.swift:14-27 — frame(height:48)·sansBold(21)·@ViewBuilder Trailing·EmptyView 편의 init |
| 1. ScreenHeader 대체·삭제 | O | 파일 삭제 + 사용처 3곳(채팅·알림·그룹목록) 교체, 코드 참조 grep 0건 |
| 1. 탭바 내정보 = 내 프사 원형 | O | FloatingTabBar.iconView .myInfo 분기 — AvatarView 25pt, 선택 시 cta 링 2pt, CurrentUser @ObservedObject 관찰 |
| 2. 탭바 5→4탭 | 선행 완료 | IA 재설계 #106에서 이미 4탭(MainTab.allCases=4, MainTabTests 검증) — IG-1 잔여는 프사 원형뿐(반영됨) |
| 3. 그룹 목록 플랫화(아바타 54·멤버 일렬·여백 구분) | O | GroupListView.groupRow — GroupAvatarView 54, memberStrip 18pt/-5 겹침/bg 링, 카드·테두리 제거, 행 패딩 8×16 |
| 3. ＋ 메뉴 2항목(새 그룹/초대 코드) + 하단 칩 제거 | O | addMenu(Menu 2항목, onCreateGroup/onJoin 재배선), addGroupRow/addGroupChip 삭제, 빈 상태에도 상단바 노출 |
| 3. "내 그룹" 섹션 라벨 | O | sansSemiBold(12) inkSoft, 목업 .sect 정합 |
| 4. 채팅 목록 플랫 행 56 | O | DMRoomRow roomAvatar 56(GroupAvatarView/이니셜 폴백 유지), 카드·clipShape 제거 |
| 4. 미읽음 = "새 메시지 N개"(ink Bold) + cta 점 8pt, 캡슐·시간 컬럼 제거 | O | previewText(count>0 → "새 메시지 N개 · 시각", nil/0+hasUnread → "새 메시지"), 점 8pt WGColor.cta |
| 4. 읽음 = 내용 + · 시각 인라인 | O | timeSuffix(lastAt nil 시 생략), formatTime 재사용 |
| 4. 편집 버튼 없음 | O | InstaNavBar(title:"채팅") trailing 미지정 |
| 전역. 토큰 100% 유지·하드코딩 색 금지 | O | #hex/Color(red:) grep 0건, WGColor/WGFont만 사용 |
| 전역. 백엔드 무변경 | O | diff에 backend 파일 없음 |

[계획서 IG-1] 4/4 항목 충족 (항목 2는 선행 완료 확인).

## 설계 범위 이탈

이탈 없음 — 계획서 대상 목록(MainTabView·GroupListView·DMListView+VM·DesignSystem·ScreenHeader 사용처 일괄) 내. MainTabTests 수정은 FloatingTabBar 시그니처 변경의 필연 부수(자기점검 Critical 해소分 — self-check.md 기보고).

## PR #124 리뷰 반영 판단 (사용자 요청)

| 리뷰 | 판단 | 처리 |
|------|------|------|
| ＋ 버튼 터치 영역 44pt(HIG) | 타당 | 반영됨(f644de2) — frame 44×44 trailing 정렬 + contentShape |
| currentUser.load() 동기 대기 병목 | 타당 | 반영됨(f644de2) — fire-and-forget Task 분리(싱글톤이라 VM 수명 무관 안전) |

신규 리뷰 코멘트 없음. 단 PR head가 옛 커밋(e5ea186) — force push 미실행으로 반영분이 원격 미반영 상태.

## 신규 위험

### Critical / Warning
- 없음

### Info
- [GAP] FloatingTabBar.tabButton의 `.foregroundColor(선택색)`이 .myInfo 분기의 AvatarView에도 상속되나, AvatarView가 자체 색(이니셜 흰색·틴트 배경)을 지정해 시각 영향 없음 — 구조상 관찰만
- [ASSUMPTION] InstaNavBar/플랫 행의 시각 밀도·safe area 배치는 Windows에서 렌더 확인 불가 — Mac DoD-B에서 목업 v4 대비 확인 필요(기존 잔여 항목과 동일 트랙)

## 총평
- 강점: 목업 v4 수치(48/21/54/56/18/-5/8pt)가 코드에 1:1 추적 가능. 리뷰 2건 즉시 반영으로 HIG·성능 결함 선제 해소
- 합산: Critical 0, Warning 0, Info 2(둘 다 행동 불요 — Mac DoD-B 트랙으로 이월)
- 권고: force push로 원격 동기화(f644de2) → iOS CI green 확인이 남은 게이트

## 처리 결과
- Info 2건은 수정 위임 대상 아님(시각 검증=Mac DoD-B 이월, 색 상속=영향 없음 관찰) — 기록만
