# 자기점검 결과 (qa-manager)

> Phase 2.11 PR-A 자기점검. AC-1/2/3/7/8/9/15/16 모두 충족.

## 요약
- Critical: 2건 (자동 수정 완료)
- Warning: 3건 (phase-review 이월)
- Info: 1건 (phase-review 이월)
- Question: 2건 (phase-review 이월)

## Critical 자동 수정 결과

### C-1: mdcTaskDecorator 빈 제거 (완료)
- **이유**: 코드베이스에 `@Async` 사용처 없고 `ThreadPoolTaskExecutor.setTaskDecorator()` 호출 없음. 빈 선언만 미연결 상태로 주석의 보증 불이행.
- **수정**: `RequestIdFilterConfig.java`에서 `mdcTaskDecorator` 빈 메서드 + `TaskDecorator`/`MDC`/`Map` import 제거. 주석에 "PR-B에서 ThresholdMonitorScheduler/AsyncConfigurer와 함께 도입" 명시.
- **검증**: `PlaceFallbackOrchestrator.runAsync`는 명시적 `MDC.getCopyOfContextMap()` 캡처, `PendingInstagramAutoSaveScheduler`는 진입 시 `MDC.put("SCHEDULER")` — 두 비동기 진입점은 빈 의존 없이 정상 동작.

### C-2: @EnableScheduling 제거 (완료)
- **이유**: `RequestIdFilterConfig`의 책임은 "필터 등록"이며 스케줄링 활성화는 무관한 관심사. 현재 `@Scheduled` 메서드 없음.
- **수정**: `RequestIdFilterConfig.java`에서 `@EnableScheduling` 어노테이션 + import 제거.
- **추후**: PR-B에서 `ThresholdMonitorScheduler` 도입 시점에 별도 `SchedulingConfig` 클래스로 추가.

## Warning (phase-review 이월)

### W-1: Gemini 멀티 추출 메서드의 cache=n/a (PR-B에서 갱신 예정)
- **파일**: `GeminiPlaceClient.java:357`(`extractCandidatesInternal`), `:503`(`extractPlaceNames`)
- **현상**: `extractPlaceName`은 `cache=miss/hit` 정확히 분기하나, 멀티 추출 2 메서드는 캐시 우회로 항상 `cache=n/a`.
- **조치**: PRD 주석에 "PR-B에서 통합 예정" 명시. PR-B의 FR-OBS-9 GooglePlacesResponseCacheService 도입 시점에 Gemini 멀티 메서드도 일관성 검토 권장.

### W-2: KakaoCallbackClient early return 시 로그 미발행
- **파일**: `KakaoCallbackClient.java:74-81` (callbackUrl blank / SSRF 가드)
- **현상**: 가드로 skip되는 경우 `finally` 도달 못해 구조화 로그 미발행. AC-3 literal 충족(외부 API 호출이 없으므로)이나 운영 추적 관점 권장.
- **조치**: phase-review에서 가드 skip 케이스의 로그 정책 논의.

### W-3: GooglePlacesClient 429 outcome 판별이 message string 매칭 의존
- **파일**: `GooglePlacesClient.java:124-129`
- **현상**: `e.getMessage().contains("status=429")`로 판별. 현재 메시지 생성 로직과 일치해 동작하나, 메시지 변경 시 `rate_limited` outcome이 `error`로 잘못 분류 가능.
- **조치**: `CoreException`에 HTTP status 필드 추가 또는 caller에서 outcome 직접 결정 (별도 리팩토링).

## Info

### I-1: file-rolling-appender.xml의 cleanHistoryOnStart=true
- **현상**: 배포 재기동 시점마다 보관 정책 초과 파일 즉시 삭제. 90일/5GB 정책에 부합하므로 정상.
- **조치**: 운영 인지 사항. 결정 변경 없음.

## Question

### Q-1: mdcTaskDecorator 빈의 실제 소비자 (C-1 자동 수정으로 해소)
- 자동 수정으로 빈 제거. PR-B 시점에 AsyncConfigurer + ThreadPoolTaskExecutor 도입 시 재추가 결정.

### Q-2: PlaceV1Dto switch→if/instanceof 변환 적정성
- **검증**: `PlaceSearchOutcome sealed permits Single, Multiple, Empty` — if/instanceof 2개 + else로 3 변종 모두 커버. 논리적 정확.
- **부수 이슈**: switch pattern matching이 Java 21에서 정식 GA임에도 컴파일러가 "preview"로 처리한 원인이 미해결 (Gradle 설정·toolchain 의심). 별도 이슈로 추적 권장. 본 PR에서는 if/instanceof 변환으로 우회.

## AC 체크리스트 (모두 충족)

| AC | 수용 기준 | 상태 | 근거 |
|----|----------|------|------|
| AC-1 | HTTP 요청 로그에 UUID requestId | ✅ | `RequestIdFilter.java:28-29` |
| AC-2 | 연속 요청 격리 | ✅ | `RequestIdFilter.java:33-35` finally MDC.clear() |
| AC-3 | 외부 API 4곳 5필드 로그 | ✅ | Google:119, Gemini:250/357/503, Kakao:101, Instagram:81 |
| AC-7 | 일별 .log.gz 생성 | ✅ | `file-rolling-appender.xml:7` |
| AC-8 | max-history=90, total-size-cap=5GB | ✅ | `file-rolling-appender.xml:8-9` |
| AC-9 | Docker 재배포 후 파일 보존 | ✅ | `deploy.yml:87` -v 볼륨 마운트 |
| AC-15 | 단건 실패 Slack 본문에 requestId | ✅ | `SlackNotifier.java:113-114` MDC 자동 동봉 |
| AC-16 | 스케줄러 Slack에 "SCHEDULER" | ✅ | `PendingInstagramAutoSaveScheduler.java:51` |
