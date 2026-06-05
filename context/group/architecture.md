# group 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

- 엔티티: `Group`, `GroupMember`, `InviteLink`
- 스키마 관계: User 1 ─ N `GroupMember` N ─ 1 Group (**N:M, 스키마 레벨**)
- 비즈니스 제약: **GM-1 이후 1인 다중 활성 그룹 허용** (이전 MVP는 "1인 1활성"이었으나 V018에서 해제). 그룹당 정원 10인(`MAX_GROUP_MEMBERS=10`)
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
- 관련 도메인: [[pin]] (그룹 스코프), [[chatbot]] (연동 코드 입력 시 user_id → group_id 확정)

## 주제 문서

| 주제 | 설명 |
|------|------|
