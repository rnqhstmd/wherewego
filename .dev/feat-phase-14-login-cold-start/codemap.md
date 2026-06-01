## 코드 맵: Phase 14 — 로그인 콜드 스타트 안정화

### 핵심 파일
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/auth/AuthService.java:54-82 → 카카오 로그인 진입점. bulkhead(5) 후 DB 작업을 UserLoginPersistence에 위임. @Transactional 없음(외부 HTTP 포함)
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/auth/UserLoginPersistence.java:49-115 → @Retryable(maxAttempts=2, backoff 500ms) DB upsert + @Recover. 콜드 스타트 초과 시 AUTH_KAKAO_API_FAILED(502) 발생 지점
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/auth/kakao/KakaoOAuthClient.java:23-31 → RestClient 2개 타임아웃 미배선 (수정 대상 #3)
- backend/modules/jpa/src/main/resources/jpa.yml:99-121 → prod Neon 데이터소스. connection-timeout 5000, minimum-idle 0 (수정 대상 #2)
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/auth/LoginRetryListener.java → retry 메트릭(auth.login.retry.*)/로그

### 참조 파일
- backend/apps/wherewego-api/src/main/java/com/wherewego/config/env/KakaoApiProperties.java:33-35 → Callback.timeoutMs (정의됐으나 KakaoOAuthClient가 미사용)
- backend/apps/wherewego-api/src/main/resources/application.yml:59-71 → kakao.callback.timeout-ms: 3000
- backend/apps/wherewego-api/src/main/java/com/wherewego/support/error/ErrorType.java:19 → AUTH_KAKAO_API_FAILED (HttpStatus.BAD_GATEWAY 502)
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/monitoring/ThresholdMonitorScheduler.java:63 → 기존 @Scheduled(fixedRate) 패턴 참조 (keep-warm 스케줄러 #1 참고)
- backend/apps/wherewego-api/src/main/java/com/wherewego/config/s3/S3Config.java:29-33 → apiCallTimeout/connection/socket 타임아웃 배선 예시
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/notify/slack/SlackNotifier.java:56-60 → RestClient requestFactory 타임아웃 배선 예시 (SimpleClientHttpRequestFactory)

### 설정
- backend/modules/jpa/src/main/resources/jpa.yml → 데이터소스/HikariCP (prod 프로필 Neon)
- backend/apps/wherewego-api/src/main/resources/application.yml → kakao.callback.timeout-ms, task.scheduling.pool.size
- context/auth/phase-14-login-cold-start.md → Phase 14 계획 문서 (원인 분석 + 수정 범위 3종)
