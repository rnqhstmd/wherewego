## 코드 맵: Phase 1 — 카카오 OAuth2 + JWT (auth 도메인)

### 핵심 파일
- `backend/apps/wherewego-api/src/main/resources/db/migration/V001__init_schema.sql:25-36` → `users` 테이블 스키마 (kakao_user_id UNIQUE, refresh_token TEXT 컬럼 존재)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/config/env/KakaoApiProperties.java:1-19` → Kakao OAuth/LocalAPI 설정 (clientId/clientSecret/redirectUri NotBlank)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/config/env/JwtProperties.java:1-15` → JWT 설정 (secret 32자+, access/refresh TTL Positive 검증)
- `backend/apps/wherewego-api/src/main/resources/application.yml:33-43` → jwt/kakao 환경변수 바인딩 (.env 기반)
- `backend/apps/wherewego-api/build.gradle.kts:1-29` → 의존성 (spring-security/oauth2-client 미포함 — Phase 1에서 추가 필요)

### 참조 파일
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/example/ExampleModel.java` → 도메인 엔티티 패턴 참고
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/example/ExampleService.java` → 도메인 서비스 패턴 참고
- `backend/apps/wherewego-api/src/main/java/com/wherewego/application/example/ExampleFacade.java` → 애플리케이션 레이어 Facade 패턴
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/ApiResponse.java:1-32` → 표준 API 응답 envelope
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/ApiControllerAdvice.java` → 예외 처리 advice
- `backend/apps/wherewego-api/src/main/java/com/wherewego/support/error/ErrorType.java:1-19` → 표준 에러 enum (AUTH_* 추가 필요)
- `backend/modules/jpa/src/main/java/com/wherewego/domain/BaseEntity.java:1-73` → 공통 엔티티 (id BIGSERIAL, createdAt/updatedAt/deletedAt)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/WherewegoApiApplication.java` → @ConfigurationPropertiesScan 부착 확인 (CookieProperties/CorsProperties 자동 스캔)
- `backend/build.gradle.kts:42-71` → 루트 subprojects 공통 의존성 (testcontainers/spring-boot-testcontainers)
- `backend/modules/jpa/src/testFixtures/java/com/wherewego/testcontainers/PostgresTestContainersConfig.java` → 통합/E2E 테스트 Testcontainers fixture
- `backend/modules/jpa/src/testFixtures/java/com/wherewego/utils/DatabaseCleanUp.java` → 모든 테이블 truncate (통합 테스트)

### 설정
- `backend/.env.example:24-40` → KAKAO_CLIENT_ID/SECRET/REDIRECT_URI, KAKAO_LOCAL_API_KEY, JWT_SECRET/ACCESS_TTL_SECONDS(3600)/REFRESH_TTL_SECONDS(1209600=14d). COOKIE_*, CORS_ALLOWED_ORIGINS 추가 필요
- `context/auth/architecture.md` → 시스템 구조 (Access 1h, Refresh 14d, users 테이블 필드)
- `context/auth/glossary.md` → auth 용어 사전 (kakao_user_id, Stateless 세션)
