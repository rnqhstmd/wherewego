# 설계: Phase 14 — 로그인 콜드 스타트 안정화

## 변경 범위 (파일 5개)

### 1. FR-2 재시도 예산 확대 — `backend/modules/jpa/src/main/resources/jpa.yml`
- **prod 프로필** `connection-timeout: 5000` → `10000`. 주석(112-114) worst-case math를 `10s + 0.5s + 10s ≈ 20.5s`로 갱신.
- **main(top) 블록** `leak-detection-threshold: 15000` → `30000`. 주석을 "@Retryable worst-case ~20.5s 보다 길게"로 갱신.
- local/dev/test 프로필 미변경.

### 2. FR-2 주석 동기화 — `backend/.../domain/auth/UserLoginPersistence.java`
- 코드 로직(maxAttempts=2, backoff 500/2.0) **변경 없음**. `@Retryable` javadoc(36-47)의 타이밍 추정 주석만 갱신: `connection-timeout(5s)` → `(10s)`, `worst ≈ 10.5s` → `≈ 20.5s`.

### 3. FR-3 카카오 타임아웃 배선 — `backend/.../infrastructure/auth/kakao/KakaoOAuthClient.java`
- 생성자에서 `props.callback().timeoutMs()`(3000)로 `ClientHttpRequestFactory` 생성, 양쪽 RestClient에 `.requestFactory(factory)` 적용.
- `SlackNotifier`(56-60) 패턴 그대로 사용: `SimpleClientHttpRequestFactory` + `setConnectTimeout` + `setReadTimeout` (connect=read=timeoutMs). 단일 factory 인스턴스를 두 RestClient가 공유(stateless config).
- 기존 try/catch(RestClientException → AUTH_KAKAO_API_FAILED) 그대로 유지 → 타임아웃 시 자동으로 502 경로.

### 4. FR-1 keep-warm 스케줄러 (신규) — `backend/.../infrastructure/db/NeonKeepWarmScheduler.java`
- `@Component` + `@ConditionalOnProperty(prefix="db.keep-warm", name="enabled", havingValue="true")` → **기본 OFF**(프로퍼티 없으면 빈 미생성, 스케줄 미등록).
- `@Scheduled(fixedRateString = "${db.keep-warm.interval-ms:240000}")` (4분).
- 생성자 주입: `DataSource`(주 데이터소스). 클래스 필드로 `activeStartHour/activeEndHour/zone` 주입(@Value 또는 properties).
- 동작: 현재 시각(KST)이 `[activeStartHour, activeEndHour)` 이면 try-with-resources로 `Connection`→`SELECT 1` 실행. 윈도우 밖이면 skip. 예외는 `log.warn` 후 삼킴(다음 주기 재실행). 성공 시 `log.debug` 실행 시각 기록(BR-5).
- `@EnableScheduling`은 기존 `ThresholdMonitorScheduler`로 이미 활성.

### 5. FR-1 설정 — `backend/.../resources/application.yml`
```yaml
db:
  keep-warm:
    enabled: ${DB_KEEP_WARM_ENABLED:false}   # 기본 OFF. Neon 잔여 컴퓨트 확인 후 true.
    interval-ms: 240000                        # 4분 (< Neon suspend ~5분)
    active-start-hour: 7                        # 07시 KST
    active-end-hour: 23                         # 23시 KST (미만)
    zone: Asia/Seoul
```
- 프로퍼티 바인딩: 기존 `KakaoApiProperties` 등의 `@ConfigurationProperties` record 등록 방식(@ConfigurationPropertiesScan/@EnableConfigurationProperties)을 코더가 확인해 동일 패턴으로 `KeepWarmProperties` record 추가하거나, 스케줄러에서 `@Value`로 직접 주입(단순). 둘 중 기존 컨벤션 우선.

## 데이터 모델 / API 계약
- 변경 없음 (스키마·엔드포인트·DTO 불변).

## 테스트 전략
- 기존 회귀: `AuthServiceTest`, `UserLoginPersistenceTest`, `UserLoginPersistenceRetryIT`, `AuthV1ControllerIntegrationTest`, `EnvBindingTest` 통과 유지.
- 신규 단위 테스트(`NeonKeepWarmSchedulerTest`): ① 윈도우 밖이면 DataSource 미호출 ② 윈도우 안이면 `SELECT 1` 실행 ③ DataSource 예외 시 throw 없이 삼킴. (Mockito로 DataSource/Connection mock, 시각은 Clock 주입 또는 hour 파라미터화로 결정적으로.)
  - → 결정적 테스트 위해 스케줄러에 `Clock` 주입(기본 `Clock.system(zone)`) 권장.
- `KakaoOAuthClient` 타임아웃: 기존 `KakaoCallbackClientTest` 스타일 참고. 타임아웃 단위 테스트는 선택(저가치) — 정상 경로 회귀만 확인.
- EnvBinding: 신규 프로퍼티가 바인딩 검증에 걸리지 않는지 확인.

## 리스크
- leak-detection 30s 상향: 실제 leak 감지 지연(허용 — 단일 인스턴스·소규모).
- keep-warm DataSource 직접 사용: 풀에서 커넥션 1개 일시 점유(주기당 수십 ms). maximum-pool-size 10 대비 무시 가능. 기본 OFF라 운영 영향 0until 활성화.
- connection-timeout 10s: 콜드스타트 외 진짜 DB 장애 시 사용자가 최대 20s 대기 후 502(허용 — 기존 10.5s 대비 증가하나 실패율↓ 목적).

## 핵심 결정
1. keep-warm 기본 OFF (env-gated) — 무료 컴퓨트 한도 안전.
2. connection-timeout 상향은 prod만.
3. 카카오 타임아웃은 단일 3000ms connect+read.
4. @Retryable 코드 로직 불변 — 주석만 갱신.
