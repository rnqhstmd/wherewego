# 자기점검 — PR-1 (채팅+STOMP)

## Critical (자동 수정 완료)
- [Critical/해소] BotChatProcessor.java — @Async 스레드 내 `CompletableFuture.supplyAsync().orTimeout().join()` 이중 위임/블로킹 + 형식적 타임아웃 → 래퍼 제거, doProcess 직접 호출. 시간 상한은 ContentParser/PlaceSearch/Gemini의 ChatbotContext 데드라인 + HTTP read timeout으로 강제(무한 hang 불가 검증). compileJava Green.

## Warning/Info (phase-review 이월)
- [Warning] StompAuthChannelInterceptor.java:99 — `/topic` 하위 bot/couple 외 임의 destination 구독 시 인가 미검증. 현재 서버가 해당 토픽 미발행이라 유출 없으나, 토픽 네임스페이스 확장 시 갭. → else 분기에 `/topic/chat/` 하위 비-bot/couple 명시 거부 검토.
- [Info] ChatMessageJpaRepository — cursor null JPQL `(:cursor IS NULL OR m.id < :cursor)` 타입 안전성(파생 메서드 2분기 대안).
- [Info] AsyncConfig — setKeepAliveSeconds 미설정(코어2~맥스4 스레드 무한 대기). keepAlive 60s 권고.
- [Info] BotChatProcessor — ChatbotContext syncDeadlineMs와 FR-7 30초 기준 이중화 혼선(현재 동작 정상).

## QUESTION (phase-review 이월 — 사용자 확인)
- [QUESTION] Bearer 대소문자 무시(StompAuthChannelInterceptor) — P1 HTTP 필터와 일관성으로 적용됨. 유지 권고(iOS 앱 소문자 bearer 허용). 의도 확인.
- [QUESTION] ChatMessageAppender.appendBotSystem — senderType=BOT + kind=SYSTEM. SenderType.SYSTEM enum 미사용 상태. 봇 실패/안내 메시지의 senderType을 BOT 유지 vs SYSTEM 변경.

## AC 충족 (자기점검 확인)
AC-1(PROCESSING 즉시)·AC-2(STOMP bot 발행)·AC-3(cursor/빈방)·AC-4(couple 저장+브로드캐스트)·AC-5(비멤버403)·AC-6(STOMP 무토큰 거부)·AC-14(webhook 회귀0)·AC-15(멤버1명 저장만) 모두 충족.
