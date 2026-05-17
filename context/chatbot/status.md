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
| FR-BOT-5 | Google 폴백 비동기 + 콜백 메시지 푸시 | ⬜ | Phase 5 이월 |
| FR-BOT-6 | 검색 결과 복수 시 리스트 카드(최대 5개) | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — `PlaceSearchOutcome.Multiple` + `placeSelectionCandidate` Caffeine 10m |
| FR-BOT-7 | 장소명 추출 실패 시 폴백 메시지 | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — Phase 2.5([#15](https://github.com/rnqhstmd/wherewego/pull/15))에서 Gemini로 추출 정확도 ↑ |
| FR-BOT-8 | 핀 등록 완료 알림 응답 (자동 태그=PLACE 명시) | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — `Pin.autoFromInstagram()` tag=PLACE 고정 |

## 후속 작업

- **Phase 2.5 완료**: 장소명 추출을 Gemini 2.0 Flash로 교체 → FR-BOT-7 추출 성공률 ↑ ([#15](https://github.com/rnqhstmd/wherewego/pull/15))
- **Phase 5 완료**: Google Places 비동기 폴백 (FR-BOT-5) — [#11](https://github.com/rnqhstmd/wherewego/pull/11)
- **Phase 2.6 PR-B 완료**: Bucket4j 챗봇 Webhook 레이트 리밋(botUserKey 분당 10회), 그룹 탈퇴 시 BotUserMapping cascade, `@RefreshScope` + Actuator `/refresh` — [#18](https://github.com/rnqhstmd/wherewego/pull/18)
- **Phase 2.6 PR-C 이월**: 카카오 i 오픈빌더 PLACE_SELECTION 버튼 `action="message"` + `extra.placeId` 동작 검증
