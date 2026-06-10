# Trust Ledger — GC-1 (2026-06-10)

> qa-manager + security-auditor 통합 감사 — 직접 수행(메모리 feedback: 에이전트 산출물 미반환).
> 대상: `.dev/feat-group-chat-backend/diff.txt`(55 files, +2022/-308) + 변경 파일 직접 Read.

## 통합 감사 (review)

### CERTAIN / CRITICAL
- 없음

### MEDIUM
- [RISK/MEDIUM] REEL_LINK URL 길이 가드 부재 — `GroupChatService.validateReelUrl`
  - 근거: `INSTAGRAM_URL` 패턴이 `/reel/{id}/?.*`로 임의 suffix 를 허용해 초장문 URL 이 payload_json(JSONB 무제한)에 그대로 적재 가능. TEXT 는 2000자 가드가 있으나 URL 은 없음(비대칭). pins.instagram_url 은 TEXT 라 DB 불일치는 없음 — 저장 남용/응답 비대 리스크만.
  - 권고: `validateReelUrl`에 2000자 상한 추가(CHAT_REEL_URL_INVALID 재사용)

### LOW / INFO
- [INFO] 푸시 문구 발신자 익명("멤버가 …") — 노출 최소화 관점 안전. GC-2에서 닉네임 풍부화 검토 가능
- [INFO] `TYPE_GROUP_MESSAGE` 푸시 payload 에 roomId 만 포함(groupId 없음) — 기존 botResult 패턴 동일, iOS 는 방 목록 조회로 매핑(GC-2 계약에 명시됨)
- [INFO] getRooms N+1(그룹당 2쿼리) — 봇 목록 동형, 베타 규모 수용(설계 §7 문서화)

### 보안 점검 통과 항목
- 권한: 전 엔드포인트 활성 멤버십 강제(403 GROUP_NOT_MEMBER), 추출은 발신자 동일성 추가 강제(NULL 거부 — 탈퇴=영구 등록전 정책 준수)
- SSRF: 추출 대상 URL 은 전송 시점에 https + instagram.com/instagr.am 패턴으로 제한, 추출 시점 재신뢰 없음(저장값 사용)
- 인젝션: JPQL 파라미터 바인딩 일관, payload 는 Jackson 직렬화(수동 문자열 조립 없음)
- 정책 정합: 채팅 알림함 미적재(notificationService 미호출) ✓ / 봇·카카오 웹훅 무변경(BR-GC1-1) ✓ / WS 미도입 ✓
- V021: soft delete 그룹 백필 제외(deleted_at IS NULL), 롤백 절차 주석화, 멱등(IF NOT EXISTS/NOT EXISTS)

### Mechanical Gate 기록
- build: 컴파일 그린(main+test)
- test: 607건 — GC-1 관련 전부 통과(신규 GroupChatServiceIT 12케이스 포함). 잔여 실패 21건은 develop 워크트리 동일 실행으로 **선행 실패 확정**(GC-1 무관: auth/chatbot/place/pin/users-migration 영역, Docker 기동 후 비교)
- 회귀 2건(GroupMemberServiceTest — createGroup 방 생성 훅의 mock 누락)은 리뷰 전 수정·통과

### 미답변 QA QUESTION
- 없음
