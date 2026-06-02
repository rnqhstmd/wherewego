# Cross-Review 결과

- advisor: codex (GPT-5.4)
- 브랜치: feat/observability-foundation (base: develop)
- DEV_DIR: .dev/feat-observability-foundation
- 실행 시각: 2026-05-20 (PR #28 생성 직후)
- 원시 응답: cross-review.raw.md

## AC 충족 매트릭스

| AC | 충족 | 근거 (파일:라인 또는 PRD 인용) |
|----|------|--------|
| AC-1 | O | PRD `모든 HTTP 요청` UUID 요구(prd.md:43,93). `RequestIdFilter.java:28-30`에서 `UUID.randomUUID()` 생성 후 MDC/응답 헤더 주입 |
| AC-2 | O | PRD `이전 요청 requestId 오염 금지`(prd.md:94). `RequestIdFilter.java:31-35` `finally { MDC.clear(); }` 보장 |
| AC-3 | O | PRD 5필드 요구(prd.md:45,95). `GooglePlacesClient.java:123-124`, `GeminiPlaceClient.java:177-178/185-186/250-251/357-358/502-503`, `KakaoCallbackClient.java:100-102`, `InstagramScraperClient.java:82-83` |
| AC-4 | 검증 대상 외 | PR-B 범위 (design.md:283-286,312,322) |
| AC-5 | 검증 대상 외 | PR-B 범위 (`GooglePlacesResponseCacheService` 미구현) |
| AC-6 | 검증 대상 외 | PR-B 범위 (동일 근거) |
| AC-7 | O | PRD `spring-yyyy-MM-dd.log.gz` 요구(prd.md:51,99). `file-rolling-appender.xml:6-10` TimeBasedRollingPolicy 설정 |
| AC-8 | O | PRD는 application.yml 검토 적었으나(prd.md:100), 설계서가 `logback.xml 단일 진실원천`으로 정정(design.md:228,294-295). 실제 값은 `file-rolling-appender.xml:8-10` `maxHistory=90`, `totalSizeCap=5GB` |
| AC-9 | O | PRD `-v 옵션` 요구(prd.md:101). `deploy.yml:86-87` `mkdir -p /var/log/wherewego` + `-v /var/log/wherewego:/var/log/wherewego` |
| AC-10 | 검증 대상 외 | PR-B 범위 (`ThresholdMonitorScheduler` 미구현, design.md:283-286,314) |
| AC-11 | 검증 대상 외 | PR-B 범위 |
| AC-12 | 검증 대상 외 | PR-B 범위 |
| AC-13 | 검증 대상 외 | PR-B 범위 |
| AC-14 | 검증 대상 외 | PR-B 범위 |
| AC-15 | O | PRD `단건 실패 Slack에 requestId` 요구(prd.md:75,107). `SlackNotifier.java:111-117` MDC 자동 동봉. 발송 경로 `PlaceFallbackOrchestrator.java:106-107/145-146/175-176`, `KakaoCallbackClient.java:95-98` |
| AC-16 | **부분** | PRD `스케줄러 발송 Slack에 SCHEDULER`(prd.md:77,108). PR-A에는 `PendingInstagramAutoSaveScheduler.java:49-58` MDC 주입 + `SlackNotifier.java:113-114` 자동 동봉만 있고, 실제 스케줄러 발송기 `ThresholdMonitorScheduler`는 설계서에서 PR-B로 분리됨(design.md:149-166,283-286,314) |

**PR-A 범위 [Must] AC 8건 중 7건 충족, 1건 부분 충족 (AC-16)**

## 설계 범위 이탈

설계서 변경 범위표(design.md:309-326)는 PR-A 핵심 코드/설정 파일 18개를 선언. 다음 9건은 표에 명시되지 않은 부수 변경:

- `PlaceV1Dto.java` — 13라인 (사전 컴파일 에러 fix 별도 커밋, scope_clarification에서 사전 양해)
- `context/README.md` — 2라인 (도메인 인덱스 + 로드맵 Phase 2.11 추가)
- `context/chatbot/status.md` — 1라인 (Phase 2.11 reference)
- `context/place/status.md` — 2라인 (FR-PLC-9/10 ⬜ 추가)
- `context/observability/PROJECTS.md` — 8라인 신규
- `context/observability/README.md` — 48라인 신규 (PRD 초안)
- `context/observability/architecture.md` — 71라인 신규
- `context/observability/glossary.md` — 21라인 신규
- `context/observability/status.md` — 43라인 신규

이탈 사유: 도메인 문서/상태 갱신은 설계서의 코드/설정 범위표에 미기재. 사용자가 `/gx-context`로 사전 작성한 산출물의 자연스러운 동기화로 판단됨 (PRD/설계 초안 기능). **설계 범위 이탈로 보고하되 정당성은 있음**.

PR-A 핵심 14개 파일(신규 3 + 수정 11 코드/설정 — file-rolling-appender, RequestIdFilter, RequestIdFilterConfig + PlaceFallbackOrchestrator, SlackNotifier, deploy.yml 등)은 누락 없이 모두 변경됨.

## 신규 위험

### Critical
없음

### Warning

#### [GAP] AC-16 PR-A 충족 입증 부족
- **위치**: `PendingInstagramAutoSaveScheduler.java:49-58`, `SlackNotifier.java:111-117`, `design.md:283-286,314`, `prd.md:77,108`
- **근거**: PRD는 정확히 "스케줄러 발송 Slack 알림 본문"에 `SCHEDULER`가 보여야 한다고 요구. 그러나 PR-A는 MDC.put("SCHEDULER")와 Slack 본문 자동 동봉의 **기반 구조만** 추가했고, 실제 스케줄러→Slack 발송 경로의 **유일한 실증 대상**(`ThresholdMonitorScheduler`)은 PR-B로 분리됨. `PendingInstagramAutoSaveScheduler`는 이름이 시사하듯 자동 저장만 수행하며 Slack 발송 직접 트리거가 아님.
- **권고**: 둘 중 하나:
  1. **AC-16을 PR-B 검증 항목으로 재분류** (PRD/status.md에서 AC-16을 PR-B 표시로 이동)
  2. **PR-A에 스케줄러→Slack Mock 단위 테스트 추가**하여 SCHEDULER 라벨이 본문에 노출되는지 닫음

### Info

#### [ASSUMPTION] FR-OBS-13 검증 기준은 PRD가 아닌 설계서 정정을 우선해야 함
- **위치**: `prd.md:58,100`, `design.md:228,294-295`
- **근거**: PRD는 `application.yml logging.file.name / logback.rollingpolicy`를 적용 파일로 명시했으나, 설계서가 `application.yml 변경 없음 + logback.xml 단일 진실원천`으로 정정. 후속 리뷰어가 PRD 문구만 보면 application.yml 확인을 시도해 혼선 가능.
- **권고**: PRD의 FR-OBS-13 적용 파일 문구와 AC-8 검증 위치를 설계서와 일치하게 정정 (별도 docs 커밋). 이미 설계서 "PRD 정정 필요 사항" 섹션에 명시되어 있어 인지된 항목임.

## references 위반 (해당 시)

해당 없음 (`references/` 디렉토리 미존재).

## 총평

- **강점 1**: PR-A 핵심 구현 파일 14개는 설계서 변경 범위표와 완전 대응 (누락 0).
- **강점 2**: AC-1/2/3/7/8/9/15는 코드 근거가 명확하고, RequestId → MDC → Slack 자동 동봉 연결이 일관적.
- **합산**: Critical 0건, Warning 1건, Info 1건.
- **권고 1줄**: AC-16의 소유 PR을 PR-B로 명확히 이동하거나 PR-A에 스케줄러 Mock 테스트로 닫고, PRD 문구를 설계서 기준으로 정렬.

## 처리 결과

### 1번 (Warning [GAP] AC-16): 수정됨 — PR-B로 재분류
- `prd.md` AC-16 검증 방법에 명시 추가: "PR-A는 MDC 주입(`MDC.put("SCHEDULER")`) + SlackNotifier 자동 동봉의 **기반 구조만** 충족 — 실제 스케줄러→Slack 발송 트리거(`ThresholdMonitorScheduler`)는 PR-B 범위이므로 AC-16의 최종 검증은 PR-B에서 수행한다."
- AC-16의 연결 FR을 `FR-OBS-12 (PR-B)`로 표시하여 명확화.

### 2번 (Info [ASSUMPTION] PRD FR-OBS-13/AC-8 문구): 수정됨
- `prd.md` FR-OBS-13의 "적용 파일" 문구 정정: `application.yml` 문구 제거, `backend/supports/logging/src/main/resources/logback/logback.xml`이 유일 적용 위치임을 명시. 커스텀 logback.xml 로딩 시 Spring Boot `logging.*` 자동 바인딩 미적용 사실 명시.
- AC-8 검증 방법 정정: `application.yml`의 설정값 검토 → `file-rolling-appender.xml`의 `<maxHistory>90</maxHistory>`, `<totalSizeCap>5GB</totalSizeCap>` 검토.

설계 범위 이탈 9건은 모두 도메인 문서 동기화로 정당성 인정되어 처리 대상 외 (변경 없음).

