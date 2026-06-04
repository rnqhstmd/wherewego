# chatbot 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현

## 요구사항

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-BOT-1 | 카카오 Skill Webhook 엔드포인트 | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — `POST /api/v1/chatbot/webhook` + `KakaoSkillSecretFilter` |
| FR-BOT-2 | 6자리 연동 코드 발급 (웹 측, TTL 10분, 충돌 재생성) | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — `POST /api/v1/bot/link-codes` + Partial UNIQUE INDEX |
| FR-BOT-3 | 6자리 코드 인식 → botUserKey ↔ user_id 영구 매핑 | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — `BotUserMappingService.link()` TOCTOU 보완 포함 |
| FR-BOT-4 | 인스타 링크 수신 → 동기 5초 내 place 파이프라인 (Kakao Local까지) | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — `ChatbotContext.deadlineMs=4500` 데드라인 가드 |
| FR-BOT-5 | Google 폴백 비동기 + 콜백 메시지 푸시 | ✅ | [#11](https://github.com/rnqhstmd/wherewego/pull/11) — Phase 5: 동기/비동기 자동 분기 + 카카오 콜백 푸시 |
| FR-BOT-6 | 검색 결과 복수 시 리스트 카드(최대 5개) | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — `PlaceSearchOutcome.Multiple` + `placeSelectionCandidate` Caffeine 10m |
| FR-BOT-7 | 장소명 추출 실패 시 폴백 메시지 | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — Phase 2.5([#15](https://github.com/rnqhstmd/wherewego/pull/15))에서 Gemini로 추출 정확도 ↑ |
| FR-BOT-8 | 핀 등록 완료 알림 응답 (자동 태그=PLACE 명시) | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — `Pin.autoFromInstagram()` tag=PLACE 고정 |
| FR-BOT-9 | UnknownHandler 상태별 분기 — 미연동/연동·pending 없음/연동·pending 있음/연동·최근 자동저장 4가지로 응답·QuickReply 분기. 연동된 사용자에게 "🔗 그룹 연동하기" QuickReply 미노출 | ✅ | [#60](https://github.com/rnqhstmd/wherewego/pull/60) — commit 0a03e11 (`UnknownHandler.java`) |
| FR-BOT-10 | `InstagramLinkHandler.processWithMemoAsync` useCallback push 실패 시 `bodyText` 무관하게 `PendingNotificationSession`에 fallback 텍스트 강제 적재 — silent failure 차단 | ✅ | [#60](https://github.com/rnqhstmd/wherewego/pull/60) — commit 0a03e11 (`InstagramLinkHandler.java`) |
| FR-BOT-11 | `InstagramPendingMemoHandler` pending 만료 후 메모 silent drop 차단 — utterance echo back + 900자 길이 가드. `RecentlyAutoSavedSession` 있으면 함께 안내 | ✅ | [#60](https://github.com/rnqhstmd/wherewego/pull/60) — commit 0a03e11 (`InstagramPendingMemoHandler.java`) |
| FR-BOT-12 | 메모 입력 중 "그룹 연동하기" QuickReply 메모 오용 차단 — utterance 정확 일치 시 메모 저장 skip, pending 유지 + "지금은 메모 입력 중이에요" 안내 | ✅ | [#60](https://github.com/rnqhstmd/wherewego/pull/60) — commit 0a03e11 (`InstagramPendingMemoHandler.java`) |

## 후속 작업

- **봇 채팅 STOMP→이벤트 전환 완료 (2026-06-04, iOS hotfix)**: iOS 인앱 봇 채팅의 실시간 수신을 WebSocket(STOMP) → 이벤트(전송 직후 폴링 2초·최대 10회 + APNs 푸시 + scenePhase 재조회)로 전환. [[notification]] 옵션B(SSE/WS→fetch 트리거, 운영 단순성 우선) 정책과 정합 — 봇 채팅은 "요청→@Async(SLA 4.5초)→결과 1건" 패턴이라 상시 소켓이 과잉이었음. STOMP 스택 제거(iOS Core/Realtime 4파일 + 백엔드 `WebSocketStompConfig`/`StompAuthChannelInterceptor`/`ChatStompPublisher`), `publishBot`/`publishCouple` 제거(`pushBotResult` APNs 유지), `spring-boot-starter-websocket` 의존 제거. "재연결중" 배너 지속 장애 근본 해소. 백엔드 compile 성공, iOS DoD-B(Mac) 잔여. [PR #94](https://github.com/rnqhstmd/wherewego/pull/94)

- **Phase 12 완료 (2026-05-27)**: 챗봇 v2 재설계 — 카카오 i 오픈빌더 버튼 토글 UX 불가(클릭 = 즉시 발화 + 메시지 수정 불가)로 인해 v2 원안의 "버튼 토글 + 완료" 모델 폐기. **콤마 번호 직접 입력** 모델로 전환(1라운드 완결). `ReelSavedSelectionSession` 단일 record(SINGLE_WANT / MULTI_SELECTING / BULK_SAVE / MEMO_WAITING) + 3분 TTL Caffeine 캐시. 분기: 0개→IDLE, 1개→SINGLE_WANT(QR 가고싶어요/발견저장), 2~30개→MULTI_SELECTING(콤마 숫자 직접 입력 + "전부"/"건너뛰기" QR), 31개+→BULK_SAVE(메모만). 파싱 규칙: 콤마 split + trim + `^\d+$` + 1~N 범위 + LinkedHashSet dedup. 폐기: `PendingInstagramSession`, `TwoSecondMemoHandler`, `InstagramPendingMemoHandler`. 신규 MessageType: `REEL_PLACE_SELECTION`, `SINGLE_WANT_YES/NO`. SELECTION/MEMO 중 룰렛/공유 액션은 거부 + 세션 유지(D-7). 상세: [pin/phase-12-pin-experience-v2.md](../pin/phase-12-pin-experience-v2.md) §챗봇 v2 재설계 — [PR #76](https://github.com/rnqhstmd/wherewego/pull/76)


- **Phase 2.5 완료**: 장소명 추출을 Gemini 2.0 Flash로 교체 → FR-BOT-7 추출 성공률 ↑ ([#15](https://github.com/rnqhstmd/wherewego/pull/15))
- **Phase 5 완료**: Google Places 비동기 폴백 (FR-BOT-5) — [#11](https://github.com/rnqhstmd/wherewego/pull/11)
- **Phase 2.6 PR-B 완료**: Bucket4j 챗봇 Webhook 레이트 리밋(botUserKey 분당 10회), 그룹 탈퇴 시 BotUserMapping cascade, `@RefreshScope` + Actuator `/refresh` — [#18](https://github.com/rnqhstmd/wherewego/pull/18)
- **Phase 2.10 완료**: 카카오 i 오픈빌더 PLACE_SELECTION 시나리오 설정(빌더 콘솔 운영 작업, 코드 변경 없음) + 카카오톡 실기기 1회 수동 E2E 검증(PR 본문 절차/결과 기록) + Phase 2.7 IT 5케이스 회귀 통과 — [#24](https://github.com/rnqhstmd/wherewego/pull/24)
- **Phase 2.7 완료**: PLACE_SELECTION E2E IT 5케이스 보강 (정상/만료/미연동/그룹 미가입/중복 핀) — [#20](https://github.com/rnqhstmd/wherewego/pull/20)
- **Phase 2.11 계획**: 챗봇 흐름이 의존하는 외부 API의 관제는 [[observability]] 도메인에서 통합 — Instagram scraper 차단 감지(FR-OBS-11), Google Places 일일 한도 사전 경고(FR-OBS-10), `KakaoCallbackClient` 재시도 보강(FR-OBS-14). 챗봇 응답 SLA(5초)와 핀 자동 등록 70% 성공률을 사후 발견에서 사전 감지로 전환
- **Phase 8 완료**: 인앱 알림 트리거 — `InstagramLinkHandler`의 `handleCandidates`/`handleLegacySingle`/`handleGoogleFallback` 3곳 + `PlaceSelectionHandler` 단건 저장 분기에서 `notificationService.createForChatbotBatch(groupId, userId, savedPinIds)` 호출. 4경로(`autoSaveOnExpiry`/`autoSavePreviousImmediately` 포함) 자동 커버. 릴스 1건 = 알림 1건 묶음, 유형 `CHATBOT_PINS`. 카카오톡 푸시 대신 앱 내 알림함으로 대체 — [#40](https://github.com/rnqhstmd/wherewego/pull/40)
- **hotfix/chatbot-response-edges 완료 (2026-05-25)**: 챗봇 응답 엣지 케이스 4건 (FR-BOT-9~12) — UnknownHandler 상태별 분기, useCallback push 실패 가시화, pending 만료 메모 silent drop 차단, 그룹연동 QuickReply 메모 오용 차단. PRD/AC는 `.dev/hotfix-chatbot-response-edges/prd.md` (FR 4건·AC 9건). 보안 감사 1회차 HIGH 1건(utterance 1000자 가드 누락) → 900자 절단 가드 추가로 해소 — [#60](https://github.com/rnqhstmd/wherewego/pull/60) — commit 0a03e11
