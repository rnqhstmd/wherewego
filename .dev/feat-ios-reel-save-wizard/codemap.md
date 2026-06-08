## 코드 맵: iOS 봇 릴스 저장 위저드(위시/발견 체크박스 + 메모 2스텝) + "보러가기" 릴스 필터 + PLACE_CARDS payload instagramUrl

### 핵심 파일 (변경 대상)
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chat/BotPlaceCardsPayloadBuilder.java:31 → PLACE_CARDS payload 빌더. `PlaceCardsPayload`에 출처 `instagramUrl` 추가 + `build(hits, instagramUrl)` 시그니처 변경
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chat/BotChatProcessor.java:128 → doProcess에서 `payloadBuilder.build(hits, url)` 호출(url 이미 보유)
- ios/WhereWeGo/Features/Chat/ChatMessageModels.swift:49 → `PlaceCardsPayload`/`ChatFrame`에 `sourceInstagramUrl` 디코딩 추가(PLACE_CARDS payload 루트)
- ios/WhereWeGo/Features/Chat/Bot/BotChatViewModel.swift:211 savePlaceCards → 전체 저장(체크=WISH/미체크=REEL) + memo + instagramUrl 기록, 결과(저장 url/카운트) 반환 + 보러가기 트리거
- ios/WhereWeGo/Features/Chat/PlaceCardsBubble.swift → 2스텝 위저드(체크박스→메모)로 확장 또는 신규 `ReelSaveWizard`
- ios/WhereWeGo/Features/Chat/ChatMessageRow.swift → PLACE_CARDS 렌더(위저드/버블 진입점)
- ios/WhereWeGo/Features/Chat/Bot/BotChatView.swift:24 → 빈/추출/저장 문구 개선, 위저드 연결
- ios/WhereWeGo/Features/Map/MapViewModel.swift:189 visiblePins → `focusedInstagramUrl` 필터 차원 + `focusReel(url)`(fitBounds)
- ios/WhereWeGo/App/DeepLinkRouter.swift → `.reelFocus(instagramUrl)` destination 추가
- ios/WhereWeGo/App/MainTabView.swift:275 consumePending → reelFocus 처리(탭 전환 + 지도 배너)

### 참조 파일
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chat/ChatMessageAppender.java:56 appendBotPlaceCards → payload JSON 직렬화(payload 구조만 바뀜, 코드 변경 불필요)
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/ReelMemoWaitingHandler.java:160 saveAll → 카카오 봇 위시/발견 분기 의미론(iOS 위저드의 참고 모델: 전부 저장·wishIndices만 WISH)
- ios/WhereWeGo/Features/Chat/ChatScrollContainer.swift:18 → PLACE_CARDS onSavePlaceCards 콜백 경로
- ios/WhereWeGo/Features/Chat/ChatAPI.swift → 봇/커플 메시지 API(변경 없음)
- ios/WhereWeGo/Features/Pin/PinAPI.swift → create(groupId, CreatePinRequest) — memo/instagramUrl/tag 이미 지원
- ios/WhereWeGo/Features/Pin/PinTag.swift → .REEL/.WISH/.MEMORY
- ios/WhereWeGo/Features/Map/TagFilterBar.swift → 좌하단 필터/범례(배너 톤 참고)

### 설정
- backend/apps/wherewego-api/src/main/resources/application.yml → place.scraper/instagram 설정(변경 없음)
- PinSummary(ios, MapViewModel copy 확장 내) → instagramUrl 필드 이미 보유

### 비고
- 백엔드 변경은 PLACE_CARDS payload에 instagramUrl 1필드 추가가 전부(컬럼/마이그레이션/엔드포인트 무변경)
- iOS 봇은 @AuthUser(JWT)로 동작 → 그룹 연동 흐름 없음(추가 제거 불필요)
- references/ 없음 → REFERENCES 빈 상태
