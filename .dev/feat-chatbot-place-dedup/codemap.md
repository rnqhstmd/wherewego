# 코드 맵: 챗봇 예외 처리 개선 + URL 다른 동일 장소 중복 감지

## 핵심 파일

- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/InstagramLinkHandler.java:397-415` → `tryRegister`에서 DataIntegrityViolationException 삼킴 → 사용자에게 "찾지 못함"으로 잘못 표시되는 근본 원인
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/InstagramLinkHandler.java:417-443` → `composeResponse`. autoRegistered / manualNeeded 두 리스트만 분류. `alreadySaved` 섹션 추가 대상
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/InstagramLinkHandler.java:445-507` → `handleLegacySingle` / `handleGoogleFallback`. 단일 응답 "이미 저장된 장소입니다." → 새 통합 포맷으로 일치시킬 곳
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinService.java:60-67` → `registerFromInstagram(memo)` — 사전 중복 검사 진입점
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinRepository.java` → 인터페이스. `findActiveByGroupPlaceNear` 추가
- `backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/pin/PinRepositoryImpl.java` → 구현체. JPA 위임
- `backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/pin/PinJpaRepository.java` → Spring Data JPA 인터페이스. 네이티브/JPQL 쿼리 추가

## 참조 파일

- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/Pin.java:30-36` → `@UniqueConstraint` 주석이 V005와 불일치 — 옵셔널 정리
- `backend/apps/wherewego-api/src/main/resources/db/migration/V005__relax_pins_unique_to_include_place_name.sql` → 실제 DB UNIQUE 제약 `(group_id, instagram_url, place_name)`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/ChatbotErrorMessages.java:26` → `PLC_DUPLICATE_PIN` → "이미 저장된 장소예요." (참고용, 변경 없음)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/place/PlaceSearchHit.java` → `placeName`, `latitude`, `longitude` 추출 소스

## 설정

- `backend/apps/wherewego-api/src/main/resources/application.yml` → 좌표 근접 임계값은 코드 상수로 하드코드 (성격상 튜닝 불필요)
