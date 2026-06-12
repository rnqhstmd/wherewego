# Trust Ledger (GP-1)

> 리뷰 에이전트(qa-manager/security-auditor) 미반환 환경 이슈로 오케스트레이터가 직접 수행한 통합 감사 결과. 2026-06-11.

## Mechanical Gate
- backend `./gradlew compileJava compileTestJava`: EXIT=0 (3회)
- backend 단위 테스트(GroupMemberServiceTest, UserLoginPersistenceTest): BUILD SUCCESSFUL
- 전체 `test`/iOS 빌드: 환경 제약(Docker 필요·Windows) — iOS CI가 push 후 검증(기존 파이프라인 관례)

## 통합 감사 (review)

### CRITICAL / HIGH
- 없음

### MEDIUM
- 없음

### LOW / 관찰
- [LOW/일관→**해소**] UserService·GroupMemberService의 S3 `deleteQuietly` 트랜잭션 내부 실행 — PR #123 gemini 리뷰 반영(2026-06-12)으로 `deleteAvatarAfterCommit`(TransactionSynchronization.afterCommit, 미활성 시 즉시 삭제 폴백) 전환. cross-review.md 재검증 통과
- [LOW/UX] ios AvatarView: AsyncImage `.empty`(로딩 중)에 이니셜 폴백 렌더 → 로드 완료 시 이니셜→이미지 전환 깜빡임 가능. AC-8(깨진 이미지 금지) 우선으로 수용

### 검증된 방어
- 업로드 검증: ImageUploadGuard 3중(contentType 화이트리스트 → 2MB → 매직바이트) — group/user/pin 3개 엔드포인트 모두 적용 확인
- 픽셀 폭탄: S3AvatarStorage가 디코드 전 헤더 판독으로 MAX_DIMENSION 상한 검사(S3PinPhotoStorage 동형)
- 권한: 그룹 이미지 = `findByIdForUpdate` 락 + 활성 멤버 검증(그룹명 수정과 동일 직렬화). 프사 = @AuthUser 본인만
- 키 인젝션: S3 키는 서버가 숫자 id 로 조립(`groups/{id}/avatar`, `users/{id}/avatar`) — 사용자 입력 미개입
- 정보 노출 범위: 멤버 프사는 본인 소속 그룹 목록·그룹원 목록·방 멤버 채팅 프레임에서만 노출(기존 권한 경계 내)
- FR-7: 재로그인 updateProfile 제거가 카카오 웹/네이티브/애플 3경로 모두 반영, 신규 가입 수집은 보존
- FR-8: MAX_GROUP_MEMBERS=8, `>=` 검사로 기존 9~10명 그룹은 신규 가입만 차단(데이터 보정 불요)

### 미답변 QA QUESTION (기록)
- (사용자 확인 대기 — phase-review Step 4b에서 처리)
