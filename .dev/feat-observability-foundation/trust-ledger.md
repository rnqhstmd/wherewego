# Trust Ledger — Phase 2.11 PR-A

## 통합 감사 (QA + Security Audit, phase-review 1st iteration)

### Critical (QA, CERTAIN)

#### CT-1: GooglePlacesClient ctx.expired() 경로 구조화 로그 미발행
- **파일**: `GooglePlacesClient.java:68-72`
- **현상**: try 블록 진입 전 `if (ctx.expired())` throw → finally 미실행 → outcome="error" 초기값 기록 (AC-3 부분 미충족)
- **권고**: `start`/`outcome` 변수를 ctx.expired() 체크 앞으로 이동 또는 명시적 로그 발행

### HIGH (Security)

#### R-1: Instagram URL 로그 인젝션 가능성
- **파일**: `InstagramScraperClient.java:62,67,71,76`
- **근거**: 외부 입력 URL이 SLF4J `{}` 치환으로 비가공 기록. CRLF 포함 시 로그 라인 위변조 가능
- **권고**: `url.replaceAll("[\\r\\n]", "_")` 또는 `safeHost(url)` 패턴 적용

#### R-2: Slack 알림 본문 Instagram URL PII 노출
- **파일**: `PlaceFallbackOrchestrator.java:207-209`
- **근거**: `ctxMap.put("instagramUrl", jobCtx.instagramUrl())` → Slack Block Kit으로 사용자 활동 데이터 노출
- **권고**: URL 마스킹(`instagram.com/p/***`) 또는 본문 제거

#### G-1: @EnableScheduling 미적용 (PR-B 누락 위험)
- **파일**: `RequestIdFilterConfig.java`
- **근거**: 설계서 명시 vs 자기점검 C-2 제거 결정의 불일치. PR-B 배포 시 `ThresholdMonitorScheduler` 묵묵히 미실행 위험
- **권고**: PR-B 배포 전 별도 `SchedulingConfig` 추가 명시 또는 PR-A에 복원 결정

### MEDIUM (Security)

#### R-3: X-Request-Id 응답 헤더 전체 노출
- **파일**: `RequestIdFilter.java:30`
- **근거**: 모든 클라이언트에 자체 UUID 응답 → 추적 정보 노출
- **권고**: prod 프로파일에서는 헤더 echo 생략, dev/local에서만 노출

#### R-4: chown 1000:1000 권한 충돌 + 로그 파일 광범위 읽기
- **파일**: `.github/workflows/deploy.yml:86`
- **근거**: UID 1000이 ec2-user와 일치하면 모든 SSH 사용자가 로그 디렉토리 접근. `chmod` 미설정으로 기본 755
- **권고**: `chmod 750` 또는 `chmod 700` 추가, Dockerfile USER 명시

#### G-2: extractCandidatesInternal 비구조화 INFO 로그 혼재
- **파일**: `GeminiPlaceClient.java:332-335,473-477`
- **근거**: `log.info("Gemini candidates raw response: {}")` 등 비구조화 로그가 5필드 로그와 INFO 레벨 공존 → 파서 기반 모니터링 혼선
- **권고**: DEBUG 레벨로 낮추거나 제거

#### G-3: PlaceV1Dto.java 번들링
- **파일**: `PlaceV1Dto.java`
- **근거**: PR-A 범위 외 변경. 사전 컴파일 에러 fix로 사용자 결정 포함됨 (PR 본문 명시 예정)
- **권고**: PR 본문에 별도 fix로 명시

#### P-1: JSON structured logging MDC 자동 직렬화 런타임 미검증
- **파일**: `json-console-appender.xml:1-9`
- **근거**: Spring Boot 3.4+ `structured-console-appender` + logstash format에 MDC 포함 검증이 주석만으로 처리. logstash-logback-encoder 의존성 부재
- **권고**: dev 배포 후 JSON 출력 샘플로 requestId 필드 실측 확인

#### A-1: task Runnable MDC 클로저 캡처 혼선
- **파일**: `PendingInstagramAutoSaveScheduler.java:49-60`
- **근거**: 외부 호출자(`InstagramLinkHandler`)가 람다 클로저로 MDC를 캡처하여 임의 덮어쓰기 가능성
- **권고**: `task.run()` 진입 전 MDC 보장 코드 또는 주석으로 호출자 책임 명시

### Warning (QA, CERTAIN)

#### W-4: InstagramScraperClient HtmlFetcher 예외 미처리
- **파일**: `InstagramScraperClient.java:47,79-83`
- **근거**: `outcome` 초기값 `OUTCOME_ERROR`. `HtmlFetcher.fetch()`가 RuntimeException 던지면 try 탈출 → outcome=error
- **권고**: `try` 블록 내 `catch (Exception e)` 추가

#### W-5: Spring Boot 3.4+ structured-console-appender 버전 검증
- **파일**: `json-console-appender.xml:8-10`
- **근거**: Spring Boot 3.4 미만이면 dev/prod 로그 전체 출력 실패. 현재 프로젝트 Spring Boot 3.4.4로 충족
- **권고**: build.gradle.kts에 Spring Boot 버전 lower bound 명시

#### W-6: GooglePlacesClient 429 message string 매칭 의존
- **파일**: `GooglePlacesClient.java:88-91,124-129`
- **근거**: `classifyOutcome`의 `msg.contains("status=429")`가 `HttpStatusCode.toString()` 구현 변경에 취약
- **권고**: 별도 예외 타입(`RateLimitedException`) 또는 CoreException에 status 필드 추가

### LOW (Security)

#### R-5: GooglePlacesClient keyword 평문 WARN 로그
- **파일**: `GooglePlacesClient.java:69,113`
- **근거**: 사용자 입력 키워드가 90일 보관 파일 로그에 평문 기록 (개인명/주소 등 가능)
- **권고**: 마스킹(`keyword.substring(0, 10) + "..."`) 적용

#### A-2: 재배포 시 pendingTask 유실 미문서화
- **파일**: `PendingInstagramAutoSaveScheduler.java:38`
- **근거**: `tasks` 인메모리 → 재배포 시 자동 저장 작업 묵묵히 누락
- **권고**: PRD 위험 테이블에 추가 + `@PreDestroy`에 결과 로깅

### Info (QA)

#### I-2: extractPlaceCandidates op 값 일치
- **파일**: `GeminiPlaceClient.java:357-358`
- **현상**: 퍼블릭 API 이름 `extractPlaceCandidates` 사용. 문제없음, 인지 목적 기록

### QUESTION (QA, 사용자 확인 필요)

#### Q-3: file-rolling-appender.xml 위치 (설계서 vs 실제)
- **현상**: 설계서 `logback/appenders/` vs 실제 `appenders/`. 실제 동작 OK (logback.xml에서 `appenders/...`로 include)
- **선택지**: (a) 설계서 정정 (Recommended), (b) 파일 이동

#### Q-4: ChatbotRateLimitFilter 필터 순서가 RequestIdFilter 이후인가?
- **현상**: ChatbotRateLimitFilter의 Slack 알림이 MDC 없이 발송될 위험. 필터 순서 확인 필요
- **선택지**: (a) HIGHEST_PRECEDENCE + 1 이상 (확인 후 OK이면 종결), (b) 순서 명시 필요

### 미답변 QA QUESTION (Trust Ledger 기록만, 사용자가 "전부 넘어가기" 선택 시 사용)

(현재 없음)

### G-1 추가 Question (Security)
G-1은 QUESTION으로도 분류 가능. 의도적 제거인지 실수 누락인지 사용자 확인 필요.

## 교차 검증 결과 요약

- **정합**: FR-OBS-6/7/12/13 핵심 동작 모두 코드로 구현됨. AC-1/2/7/8/9/15/16 충족
- **부분 충족**: AC-3 — ctx.expired() 경로에서 구조화 로그 미발행 (CT-1)
- **불일치**: 설계서 `@EnableScheduling` vs 자기점검 제거 결정 (G-1) — PR-B 배포 시 별도 조치 필요
