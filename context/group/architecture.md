# group 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

- 엔티티: `Group`, `GroupMember`, `InviteLink`
- 스키마 관계: User 1 ─ N `GroupMember` N ─ 1 Group (**N:M, 스키마 레벨**)
- 비즈니스 제약 (MVP): 한 사용자는 동시에 활성 GroupMember 1개만 가질 수 있음 (서비스 레이어에서 검증, DB 제약 아님)
- 초대 링크: UUID 기반 단방향 토큰. TTL 24h. 수락 시 GroupMember 행 추가
- 탈퇴(연결 해제) 정책:
  - GroupMember 행은 `left_at` 타임스탬프로 soft delete
  - 해당 사용자가 등록한 핀은 **그룹에 잔류**. `pins.created_by`는 user_id 그대로 유지 (개인정보보다 추억의 맥락 보존 우선)
  - 탈퇴한 사용자는 그룹 핀을 더 이상 조회/수정할 수 없음 (활성 GroupMember 기준 권한 검사)
- 관련 도메인: [[pin]] (그룹 스코프), [[chatbot]] (5자리 코드 입력 시 user_id → group_id 확정)

## 주제 문서

| 주제 | 설명 |
|------|------|
