# Cross-Review 결과

- advisor: claude (qa-manager·security-auditor 병렬 발행 → 보고 미반환 환경 증상 → 오케스트레이터 직접 수행 폴백)
- 브랜치: feat/group-profile-images (base: develop)
- DEV_DIR: .dev/feat-group-profile-images
- 검증 범위: GP-1 전체(66파일, +2,887/-212) + PR #123 gemini 리뷰 반영분(미커밋 4파일)

## AC 충족 매트릭스

| AC | 충족 | 근거 |
|----|------|------|
| AC-1 그룹 생성 크롭→썸네일 반영, 미지정=콜라주 | O | GroupCreateView.swift submit() 2단계(생성→uploadGroupImage), GroupAvatarView(1/2/3/4 콜라주) |
| AC-2 활성 멤버 누구나 변경·제거, 비멤버 403 | O | GroupMemberService.updateGroupImage/clearGroupImage — findByIdForUpdate 락 + requireActiveMembership, GroupV1ControllerIntegrationTest(+178줄) |
| AC-3 프사 지정·제거 → 4개 노출 지점 반영 | O | effectiveProfileImageUrl 단일 규칙(UserRepositoryImpl·GroupMemberService), GroupListView memberStrip·GroupAvatarView·GroupManageView·GroupMessageRow |
| AC-4 재로그인 후 지정 프사 유지(FR-7) | O | UserLoginPersistence 3경로(카카오 웹/네이티브/애플) updateProfile 제거, UserLoginPersistenceTest 갱신 |
| AC-5 멤버 전원 가입순 일렬 + 이니셜 폴백 | O | GroupListView.memberStrip(가입순, AvatarView 이니셜 폴백) |
| AC-6 정원 8(9명 그룹 가입 차단·7명 성공) | O | MAX_GROUP_MEMBERS=8, `>=` 검사, GroupMemberServiceTest(+226줄)에 정원 케이스 포함 |
| AC-7 채팅 상세 타인 메시지 발신자 프사 | O | GroupChatMessageFrame.senderProfileImageUrl + GroupMessageRow AvatarView 32 |
| AC-8 로드 실패 시 기본 표현 폴백 | O | AvatarView .failure→이니셜(안정 해시 틴트), GroupAvatarView 폴백 셀 |
| AC-9 backend compile·테스트 green, iOS CI green | O | compileJava+compileTestJava EXIT=0(리뷰 반영 후 재확인 포함), iOS CI green(#123 push), 단위테스트 2종 SUCCESS |

[Must] 8/8 충족(FR-1~8), [Should] FR-9(그룹원 목록 프사) 충족 — GroupMemberInfo.profileImageUrl.

## 설계 범위 이탈

이탈 없음 — PR #123 리뷰 반영분 4파일(GroupMemberService·UserService·UserRepositoryImpl·GroupCreateView)은 모두 설계서 변경 범위 내 파일. (B4의 MainTabView·OnboardingRouter·MapView 배선 이탈은 self-check.md에 기보고 — 중복 제외)

## 신규 위험

### Warning
- [RISK] ios/WhereWeGo/Features/Group/GroupCreateView.swift — 이미지 실패 alert의 isPresented Binding race
  - 근거: `isPresented: Binding(get: { imageFailedGroupId != nil }, set: { if !$0 { imageFailedGroupId = nil } })` 패턴에서, SwiftUI가 버튼 액션 실행 **전에** isPresented=false 를 set 하는 경우(버전별 순서 비보장) set 클로저가 imageFailedGroupId 를 nil 로 만들어 버튼 액션의 `if let groupId = imageFailedGroupId` 가 실패 → onCreated 미호출(화면이 닫히지 않음)
  - 권고: `alert(_:isPresented:presenting:actions:message:)` 로 전환 — presenting 값이 액션 클로저 파라미터로 캡처되어 dismiss 시점과 무관하게 안전

### Info
- [GAP] afterCommit 경로의 S3 회수는 프로세스 크래시 시 유실 가능(커밋 후 afterCommit 미실행) — best-effort 명세(BR-3) 범위 내, 고아 객체만 남고 깨진 링크는 없음(이전보다 안전한 방향). 기록만.

## 기존 Trust Ledger 상태 변화

- [LOW/일관] "deleteQuietly 트랜잭션 내부 실행" → **해소됨**: deleteAvatarAfterCommit(TransactionSynchronization.afterCommit, 미활성 시 즉시 삭제 폴백)으로 전환. 검증: 익명 클래스의 파라미터 캡처(effectively final) 정상, deleteQuietly 는 예외 삼킴이라 afterCommit 예외 전파 없음, 락 트랜잭션 점유 시간도 단축(부수 개선)
- toPublicUrl 캐싱(cachedPublicUrlBase volatile): 멱등 계산이라 락 없는 단순 캐시로 충분 — 정상

## 총평
- 강점: AC 전 항목이 코드·테스트 근거로 추적 가능. 리뷰 반영(트랜잭션 정합)이 기존 best-effort 명세를 실질 개선
- 합산: Critical 0, Warning 1(alert race), Info 1
- 권고: alert presenting 패턴 전환 1건 수정 후 커밋 권장
