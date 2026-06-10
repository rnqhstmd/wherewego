# 자기점검 결과 — GC-1 (2026-06-10)

> qa-manager 자기점검 모드 직접 수행(메모리 feedback: 에이전트 산출물 미반환). PRD 요구사항+수용 기준 대비.

## 요구사항 충족 매트릭스

| FR | 판정 | 근거 |
|----|------|------|
| FR-GC1-1 그룹 방 | ✅ | GROUP 일반화 + createGroup 훅 + V021 백필 + ensureGroupRoom 안전망. 멤버십 403 — IT `nonMember_forbidden` |
| FR-GC1-2 멤버별 읽음 | ✅ | chat_room_reads + markRead 전진만 + 조회 시 전진 — IT `unread_isPerMember` |
| FR-GC1-3 REEL_LINK | ✅ | kind 분기 + https/인스타 패턴 + TEXT 2000자 — IT 검증 3종 |
| FR-GC1-4 registered 파생 | ✅ | 페이지당 IN 쿼리 1회, 상태 컬럼 없음 — IT `registered_derivesFromPins`(false→true 양 메시지→false 회귀) |
| FR-GC1-5 추출 API | ✅ | extract 엔드포인트 + ReelPlaceExtractor 재사용 + 15s + append 없음 — IT `extractPlaces_senderGetsCards` |
| FR-GC1-6 발신자만 | ✅ | sender==caller 강제, NULL 거부 — IT `extractPlaces_validationChain` |
| FR-GC1-7 방 목록 | ✅ | hasUnread boolean + preview 규칙 — IT `getRooms_previewRules` |
| FR-GC1-8 푸시 | ✅ | afterCommit best-effort + 1인 생략 + kind 문구 — IT `push_fanOutExceptSender` |
| BR-GC1-1 봇 무변경 | ✅ | /chat/bot/* 무변경, BotChatProcessor 위임은 동작 동일(봇 IT `BotChatServiceGroupIT` 통과), 카카오 웹훅 무접촉 |
| Should(0곳/실패 구분) | ✅ | 0곳=200 빈 cards / 파서·검색 CoreException(PLC_*) 전파 |
| Could(중복 추출 가드) | 미구현 | 선택 사항 — 추출 read-only라 무해, 클라 버튼 비활성 1차 방어 |

## CERTAIN (Critical)
- 없음 (GroupMemberServiceTest mock 누락 회귀는 구현 중 발견·수정 완료 → 통과)

## Warning/Info (phase-review 이월)
- [Warning] GroupChatService.getRooms — 그룹당 latestMessage+읽음행 2쿼리 N+1. 봇 목록과 동형 패턴, 베타 규모(그룹 2~4개/인) 수용 — 설계 §7 명시
- [Warning] GroupChatMessageFrame ↔ ChatMessageFrame 구조 중복 — BR-GC1-1(봇 응답 무수정)을 위해 의도적 분리, GC-3 봇 제거 시 통합 검토
- [Info] extractPlaces 검증이 무트랜잭션 다중 조회 — 외부 호출 15s 동안 DB 커넥션 비점유 의도(설계 D5), 검증 간 race 영향 미미(read-only)
- [Info] 테스트 환경: Docker Desktop 수동 기동 필요(Testcontainers). 선행 실패 21건은 베이스(develop) 대조로 확정 — GC-1 무관

## QUESTION (phase-review 이월)
- 없음
