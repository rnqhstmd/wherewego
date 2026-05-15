# chatbot 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

```
[카카오톡 사용자]
       │ 메시지 전송
       ▼
[카카오 i 오픈빌더] ──Skill Webhook──▶ [Spring Boot /chatbot/webhook]
                                              │
                                              ├─ 6자리 코드 분기 → 매핑 저장
                                              ├─ 인스타 링크 분기 → place 파이프라인 (동기, 5s 내)
                                              ├─ Google 폴백 필요 시 → 비동기 처리 + 카카오톡 푸시
                                              └─ 텍스트 분기 → 2초 룰 메모 매칭
                                              │
                                              ▼
                                        리스트 카드/말풍선 응답
```

- 연동 코드 (6자리 숫자, TTL 10분):
  - 테이블: `bot_link_codes (id, user_id FK, code CHAR(6), expires_at, used_at, created_at)`
    - `UNIQUE INDEX uq_bot_link_codes_active_user ON (user_id) WHERE used_at IS NULL` — 활성 코드 유저당 1개 강제
    - `INDEX idx_bot_link_codes_code ON (code) WHERE used_at IS NULL` — 챗봇 수신 코드 조회용
  - 발급 시 기존 미사용 코드가 있으면 재생성(덮어쓰기) 불가 — Partial UNIQUE INDEX로 DB 레벨에서 차단됨. 서비스 레이어에서 기존 코드 만료(used_at 세팅) 후 신규 발급
  - 챗봇이 코드 수신 → `bot_link_codes` 조회(used_at IS NULL + expires_at > NOW()) → 만료 시 "재발급 안내", 유효 시 `bot_user_mappings` 영구 매핑 저장
- botUserKey 영구 매핑:
  - 테이블: `bot_user_mappings (id, user_id FK, bot_user_key VARCHAR(100), linked_at)`
    - `CONSTRAINT uq_bot_user_mappings_user UNIQUE (user_id)` — 유저당 봇 계정 1개 강제
    - `CONSTRAINT uq_bot_user_mappings_bot_key UNIQUE (bot_user_key)` — 봇 계정 중복 연동 방지
- 5초 SLA 전략:
  - 캡션 스크래핑 + Kakao Local 1차 호출까지는 **동기**로 5초 내 완료 보장
  - Kakao 결과 없음 → Google Places 폴백은 **비동기**, 결과는 카카오톡 별도 메시지로 푸시
  - 푸시 채널: 카카오 i 오픈빌더 콜백 API
- 관련 도메인: [[place]] (장소명 추출), [[memo]] (2초 룰), [[pin]] (등록 결과), [[group]] (그룹 매칭), [[tag]] (자동 등록은 PLACE 기본값)

## 주제 문서

| 주제 | 설명 |
|------|------|
