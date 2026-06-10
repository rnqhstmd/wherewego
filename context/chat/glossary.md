# chat 용어 사전

| 용어 | 정의 |
|------|------|
| 그룹 채팅방 | 그룹당 활성 1개인 멤버 간 단체 채팅방(`chat_room` type=GROUP). 기존 COUPLE 방의 일반화 |
| REEL_LINK | 인스타 릴스 링크 메시지 kind. payload `{url, thumbnailKey}`. 하단에 3상태 버튼을 가진다 |
| 3상태 버튼 | REEL_LINK 버블 하단 버튼: ①발신자+미등록=「장소 등록하기」 ②타인+미등록=「장소 등록전이에요」(비활성) ③등록됨=「장소가 등록되었어요. 구경하실래요?」 |
| registered (파생 상태) | REEL_LINK 의 등록 여부. DB 컬럼이 아니라 `EXISTS(pins WHERE group_id+instagram_url)` 로 조회 시 계산 — 드리프트 불가 |
| 온디맨드 추출 | 봇의 전송 즉시 추출과 달리, 발신자가 「장소 등록하기」를 누른 시점에 추출 파이프라인(스크래핑→Gemini→Kakao)을 동기 실행 |
| 봇 티키타카 | (폐기 예정) 기존 모아보기의 봇 1:1 대화 흐름 — PROCESSING/PLACE_CARDS 메시지 왕복으로 장소를 저장하던 방식 |
| 수신 4경로 | WebSocket 없이 메시지를 받는 조합: 전송 직후 제한 폴링 / APNs(백그라운드+포그라운드 willPresent 재조회) / scenePhase 복귀 / (선택) 방 표시 중 폴링 |
| 멤버별 읽음 | `chat_room_reads(room_id, user_id, last_read_message_id)` — 그룹방에서 사용자마다 독립적인 읽음 포인터 |
