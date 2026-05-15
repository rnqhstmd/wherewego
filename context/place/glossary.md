# place 용어 사전

| 용어 | 설명 |
|------|------|
| 카카오 로컬 API | 국내 장소 키워드 검색 API. 응답: `x`(경도), `y`(위도). 좌표 누락 문서는 결과에서 필터링 |
| Google Places Text Search | 해외 장소 폴백 API. `language=ko` 적용. `geometry.location.lat/lng`. **Phase 5 이월** (비동기 + 카카오 콜백 푸시) |
| og:title / og:description | 인스타 페이지 HTML 메타 태그. 캡션이 보통 `og:description`에 들어 있음 |
| 📍 이모지 우선 추출 (Phase 2) | 캡션 내 핀 이모지 뒤 텍스트를 1순위 장소명 후보로 사용. **Phase 2.5에서 Gemini로 대체 예정** |
| 위치/장소 키워드 (Phase 2) | 2순위 추출 규칙. `장소:`, `at @`, `in @` 뒤 단어. **Phase 2.5에서 Gemini로 대체** |
| 해시태그 폴백 (Phase 2) | 3순위 추출 규칙. `#장소명` 형태에서 추출. **Phase 2.5에서 Gemini로 대체** |
| Gemini 2.0 Flash (Phase 2.5) | Google AI Studio LLM. 캡션 텍스트 → 장소명 1개 추출. 무료 1,500건/일. timeout 3000ms |
| CaptionCleaner (Phase 2.5) | og:description 앞부분(likes/comments) 제거 정제기. regex `/:\s*"(.+)"\.?\s*$/` |
| GeminiPlaceClient (Phase 2.5) | Gemini 2.0 Flash REST API 호출 클라이언트. `Optional<String>` 반환 |
| 검색 결과 분기 (Outcome) | sealed `PlaceSearchOutcome` — Single(1건 자동 등록) / Multiple(2~5건 리스트 카드) / Empty(0건 폴백 메시지) |
| ContentParser | URL 종류별 파싱 추상화 인터페이스. MVP는 InstagramParser만 구현, 추후 TikTok/YouTube 등 확장 가능 |
| ChatbotContext / 5초 SLA 데드라인 | 카카오 i 오픈빌더 5초 SLA 가드. `deadlineMs=4500`. 외부 호출 진입 직전 `remaining() <= 0` 컷오프 |
| Skill Secret | 카카오 i 오픈빌더 webhook 인증용 공유 비밀. `X-Kakao-Skill-Secret` 헤더, `MessageDigest.isEqual`로 타이밍 공격 완화 |
| placeSelectionCandidate | 리스트 카드 선택용 Caffeine 캐시. key=`{botUserKey}:{placeId}`, TTL 10분, 1회 사용 후 invalidate |
| twoSecondMemo | 2초 룰 메모 부착용 Caffeine 캐시. key=`botUserKey`, value=`pinId`, expireAfterWrite=2s |
| INSTAGRAM_SCRAPING_ENABLED | 인스타 스크래핑 feature flag. 법무 미승인/IP 차단 시 즉시 무력화. 운영 초기값 `false`, `@RefreshScope` 도입은 Phase 후속 |
