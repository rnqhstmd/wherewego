# memo 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

- 저장: `pins.memo` 컬럼 + `pins.memo_source` 컬럼 (AUTO / MANUAL)
  - 별도 테이블 분리 없음 (MVP 단순 구조)
  - 메모 이력은 보존 안 함
- 2초 룰 매칭 흐름:
  1. [[chatbot]] Webhook이 링크 수신 → in-memory 또는 Redis에 `last_link[botUserKey] = {pin_id, ts}` 저장
  2. 같은 `botUserKey`에서 텍스트 수신 시 `now - ts < 2s`이면 해당 핀의 메모로 저장
     - 단, **해당 핀의 `memo_source == MANUAL`이면 차단** (수동 우선 정책)
  3. 초과 시 일반 대화로 처리하고 메모 매칭하지 않음
- 우선순위 정책 (확정):
  - 자동(챗봇 2초 룰) → `memo_source=AUTO`로 저장
  - 수동(웹 수정) → `memo_source=MANUAL`로 저장
  - **수동이 자동을 덮어쓸 수 있음** (MANUAL이 항상 우선)
  - **수동 메모 존재 시 후속 자동 메모 매칭은 무시** (수동 값 보존)
- 잠금 해제 정책 (Phase 4 도입):
  - 웹 PATCH로 `memo`를 **빈 문자열(`""`)** 저장 시 → `memo=NULL, memo_source=NULL`로 초기화
  - 잠금 해제 후 이후 챗봇 2초 룰 AUTO 메모 갱신이 다시 허용됨 (`updateAutoMemoIfNotManual` WHERE 통과)
  - 의도: "사용자가 메모를 지웠다면 챗봇이 다시 채워줄 수 있도록"

## 주제 문서

| 주제 | 설명 |
|------|------|
