# group 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

- 엔티티: `Group`, `GroupMember`, `InviteLink`
- 스키마 관계: User 1 ─ N `GroupMember` N ─ 1 Group (**N:M, 스키마 레벨**)
- 비즈니스 제약: **GM-1 이후 1인 다중 활성 그룹 허용** (이전 MVP는 "1인 1활성"이었으나 V018에서 해제). 그룹당 정원 **8인**(`MAX_GROUP_MEMBERS=8` — GM-1의 10에서 GP-1 [#123](https://github.com/rnqhstmd/wherewego/pull/123)이 축소. `>=` 검사라 기존 9~10명 그룹은 강제 퇴장 없이 신규 가입만 차단)
  - ~~DB 레벨 1인1활성 강제(`uq_group_members_active_user` partial unique)~~ → **V018에서 제거**. 서비스 사전검사(`existsActiveByUserId`)도 제거
  - 정원 검사는 `groups` 행 비관락(`findByIdForUpdate`)으로 직렬화. `uq_group_members_pair`(동일 그룹 재가입 차단)는 유지 → `GROUP_REJOIN_FORBIDDEN`
- 테이블 스키마:
  - `group_members (id, group_id FK, user_id FK, joined_at, left_at, created_at, updated_at)`
    - `CONSTRAINT uq_group_members_pair UNIQUE (group_id, user_id)` (유지 — 동일 그룹 재가입 차단)
    - ~~`UNIQUE INDEX uq_group_members_active_user ON (user_id) WHERE left_at IS NULL`~~ — **V018에서 제거**(GM-1: 1인 다중 활성 그룹)
  - `invite_links (id, group_id FK, inviter_id FK, token VARCHAR(100) UNIQUE, expires_at, accepted_at, created_at, updated_at)`
    - `accepted_at IS NULL` = 미수락, `NOT NULL` = 수락 완료
    - 재발급 시 기존 미수락 토큰은 서비스 레이어에서 만료 처리 (accepted_at 없이 expires_at 경과로 자연 만료)
- 초대 링크: UUID 기반 단방향 토큰. TTL 24h. 그룹이 먼저 생성된 후 초대 링크 발급 (chicken-egg 없음). 수락 시 GroupMember 행 추가 후 accepted_at 기록
- 탈퇴(연결 해제) 정책:
  - GroupMember 행은 `left_at` 타임스탬프로 soft delete
  - 해당 사용자가 등록한 핀은 **그룹에 잔류**. `pins.created_by`는 user_id 그대로 유지 (개인정보보다 추억의 맥락 보존 우선)
  - 탈퇴한 사용자는 그룹 핀을 더 이상 조회/수정할 수 없음 (활성 GroupMember 기준 권한 검사)
- **방장(owner, GM-2)**: 활성 멤버 중 `joined_at` 최소(동률 `id`). 별도 owner 컬럼·승계 트랜잭션 없이 **조회 시점 계산** → 방장 탈퇴 시 다음 최선임 자동 승계
- **그룹 관리 API(GM-2)**: 그룹원 목록 조회(`GET /groups/{id}/members`, 활성 멤버만), 그룹명 수정(`PATCH /groups/{id}`, 모든 멤버 1~30자), 그룹 삭제(`DELETE /groups/{id}`, 방장만 → `GROUP_OWNER_REQUIRED`). 그룹 삭제 = 전원 `markLeft` + group soft delete + 미수락 토큰 만료 + 봇 unlink (leaveGroup 패턴 확장). 모두 `findByIdForUpdate` 락으로 직렬화
- **그룹 대표 이미지(GP-1, [#123](https://github.com/rnqhstmd/wherewego/pull/123))**: `groups.image_key`/`image_thumb_key`(V022, S3 키만 저장 — 핀 사진 패턴). `POST/DELETE /groups/{id}/image`(multipart, **활성 멤버 누구나** — 그룹명 수정과 동일 권한·동일 락 직렬화). 업로드는 `AvatarStorage`(S3 원본 jpg + 256px webp 썸네일) + `ImageUploadGuard` 3중 검증(타입/2MB/매직바이트)+픽셀 상한. **미지정 시 클라가 멤버 프사 콜라주(카톡식, 가입순 ≤4) 렌더** — 서버 합성 없음. 그룹 목록 응답에 `imageUrl`/`imageThumbUrl`/`members[]`(가입순 활성 멤버 프리뷰, IN 쿼리 1회) 동봉 — iOS 채팅탭 썸네일도 이 데이터를 groupId 조인으로 재사용(채팅 목록 응답 무변경)
- 관련 도메인: [[pin]] (그룹 스코프), [[chatbot]] (연동 코드 입력 시 user_id → group_id 확정)

## 주제 문서

| 주제 | 설명 |
|------|------|
