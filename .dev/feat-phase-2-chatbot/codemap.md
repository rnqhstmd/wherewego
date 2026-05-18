## 코드 맵: Phase 2 — chatbot Skill Webhook + place 파이프라인

### 핵심 파일

**Phase 0 spike (운영 코드로 흡수 검토)**
- `backend/spike/instagram-meta-scraper/src/main/java/com/wherewego/spike/instagram/HtmlFetcher.java` → 3-stage 우회 NO_UA/CHROME_UA/FULL_HEADERS, jsoup + java.net.http (운영 적용 전 법무 검토 필요)
- `backend/spike/instagram-meta-scraper/src/main/java/com/wherewego/spike/instagram/MetaExtractor.java` → og:title / og:description 추출
- `backend/spike/instagram-meta-scraper/src/main/java/com/wherewego/spike/instagram/PlaceNameExtractor.java` → 📍 이모지 → 키워드 → 해시태그 우선순위 regex
- `backend/spike/instagram-meta-scraper/result.md` → 차단율/패턴 기여도 통계 (실행 결과)

**DB 스키마 (V001 이미 존재)**
- `backend/apps/wherewego-api/src/main/resources/db/migration/V001__init_schema.sql:114-132` → `bot_link_codes` (5자리 CHAR(6) + TTL 10분 + 활성 코드 1개/user UNIQUE)
- `backend/apps/wherewego-api/src/main/resources/db/migration/V001__init_schema.sql:141-149` → `bot_user_mappings` (botUserKey ↔ user_id 영구 매핑, UNIQUE)
- `backend/apps/wherewego-api/src/main/resources/db/migration/V001__init_schema.sql:162-197` → `pins` (chatbot 자동 등록 대상, instagram_url + group_id UNIQUE)

### 참조 파일 (Phase 1 패턴 답습)

- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/auth/AuthService.java` → 도메인 서비스 + Transactional + 외부 API 호출 패턴
- `backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/auth/kakao/KakaoOAuthClient.java` → RestClient 인라인 + 베이스 URL 외부화 + 4xx/5xx 매핑
- `backend/apps/wherewego-api/src/main/java/com/wherewego/config/env/KakaoApiProperties.java` → `kakao.local-api-key` 이미 정의됨 (Phase 2에서 확장 필요)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/config/security/AuthUser.java` + `AuthUserArgumentResolver.java` → @AuthUser Long userId 주입 (6자리 코드 발급 API에서 사용)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/auth/AuthV1Controller.java` → REST 컨트롤러 패턴 + Set-Cookie 등 ResponseEntity 구성
- `backend/apps/wherewego-api/src/main/java/com/wherewego/support/error/ErrorType.java` → AUTH_* 7건. BOT_*/PLC_* 추가 필요
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/ApiControllerAdvice.java` → MethodArgumentNotValidException 등 표준 예외 매핑
- `backend/modules/jpa/src/main/java/com/wherewego/domain/BaseEntity.java` → id BIGSERIAL + createdAt/updatedAt/deletedAt 자동
- `backend/apps/wherewego-api/src/main/java/com/wherewego/config/security/JwtAuthenticationFilter.java` → KakaoSkillSecretFilter를 어디 앞에 끼울지 결정 시 참조
- `backend/modules/jpa/src/testFixtures/java/com/wherewego/testcontainers/PostgresTestContainersConfig.java` → 통합 테스트 DB 설정
- `backend/spike/instagram-meta-scraper/build.gradle.kts:2` → jsoup 1.18.1 의존성 버전 (운영 모듈 복제용)
- `backend/spike/instagram-meta-scraper/src/main/java/com/wherewego/spike/instagram/InstagramMetaSpikeRunner.java` → spike 진입점 (실행 모드 참고용)

### 설정

- `backend/.env` / `backend/.env.example` → `KAKAO_LOCAL_API_KEY`, `GOOGLE_PLACES_API_KEY`(Phase 5), `KAKAO_SKILL_SECRET`, `KAKAO_BOT_ID` 이미 정의됨
- `backend/apps/wherewego-api/src/main/resources/application.yml` → kakao/google/web-security 환경변수 바인딩 (이미 적용)
- `context/chatbot/architecture.md` + `glossary.md` → 시스템 구조 (5초 SLA, 동기/비동기 분기), 용어
- `context/place/architecture.md` + `glossary.md` → ContentParser 디스패치, 좌표 정규화, 검색 결과 분기 1/복수/0
- `docs/adr/0001-redis-kafka-usage.md` → Google Places 폴백 `@Async` + 카카오 콜백 메시지 (Redis 결정은 ADR-0002로 폐기)
- `docs/adr/0002-redis-removal-caffeine.md` → Caffeine 캐시 사용 (5자리 코드 TTL + 2초 룰 세션 모두 인메모리)
