# Cross-Review 결과

- advisor: codex (GPT-5.4)
- 브랜치: feat/observability-external-api-monitoring (base: develop)
- DEV_DIR: .dev/feat-observability-external-api-monitoring
- diff: 2,994줄 (merge-base develop ~ HEAD)
- PR: https://github.com/rnqhstmd/wherewego/pull/29

## AC 충족 매트릭스

| AC | 충족 | 근거 |
|----|------|------|
| AC-1 | O | `GooglePlacesClient.java:150-167,184-190` finally의 metrics.recordDuration/recordCall + `GooglePlacesMetrics.java:39-49` |
| AC-2 | O | `GooglePlacesClient.java:85-107` 캐시 hit 조기 return + `recordCall("cached")`. `GooglePlacesClientTest.java:233-248` 외부 HTTP 0회 검증 |
| AC-3 | O | `GooglePlacesClient.java:150-160` 빈 결과 `responseCache.put(keyHash, List.of())` |
| AC-4 | O | put이 empty/success 분기(152-167)에만 존재. rate_limited/timeout/error(173-183) 분기에는 없음 |
| AC-5 | O | hit 로그 99-107, miss 로그 184-196의 `cachePut ? "miss" : "n/a"` |
| AC-6 | O | `GeminiPlaceClient.java:217-223,237-241,331-337,355-359,480-486,505-509` 5xx만 server_error, 4xx는 error. `GeminiPlaceClientServerErrorTest:77-123` |
| AC-7 | O | 213-215,234-245 외 동일 패턴. 429=rate_limited / timeout=timeout / 4xx=error |
| AC-8 | O | `ThresholdMonitorScheduler.java:96-118` effectiveTotal = totalDelta - disabledDelta, ratio > threshold 시 notifyWarning + ctx |
| AC-9 | O | 97-100 disabled 제외 후 분모 < 1 시 skip |
| AC-10 | O | 107-118, 157-161 cooldownPassed/cooldownEpochMs |
| AC-11 | O | `InstagramScraperClient.java:81-97` finally에서 recordAttempt → BLOCKED 시 recordBlocked |
| AC-12 | O | 123-142 blocked/attempts > threshold 시 notifyFailure + ctx |
| AC-13 | O | 124-127 attempts < 1 시 즉시 return |
| **AC-14** | **부분** | `InstagramBlockedRateTracker.java:37-43` flushWindow가 스냅샷+리셋 동시 수행 후 스케줄러가 반환된 Snapshot으로 판단/발송. PRD "판단 → 발송 → 리셋"과 표면적으로 어긋남. 의미상 동등하나 표현 불일치 |
| AC-15 | O | `GooglePlacesClient.java:93-98` 캐시 get 예외 → miss로 진행. Test 301-330 |
| AC-16 | O | 100-104, 186-192 메트릭 예외 swallow. Test 332-360 |
| **AC-17** | **부분** | `ThresholdMonitorScheduler.java:68-78` 각 check 개별 try-catch만 있고, runMonitoringTick 본문 최상위 try-catch(Exception)는 없음. PRD NFR-4 "본문 최상위" 명시와 어긋남 |
| AC-18 | O | `InstagramScraperClient.java:84-97` tracker 호출 개별 try-catch |
| AC-19 | O | `CacheConfig.java:62-65` maximumSize(1_000) |

**충족**: O 17건 / 부분 2건 (AC-14, AC-17) / X 0건.

## 설계 범위 이탈

설계서 "변경 범위"에는 신규 5개·수정 5개·테스트 7건만 명시. 이외 11개 문서·이미지가 함께 변경됨:

| 파일 | 변경 요약 | 이탈 사유 |
|------|----------|----------|
| README.md | 프로젝트 소개 신규 | 사용자 결정으로 PR에 포함 |
| context/README.md | Phase 2.10/2.11 상태 갱신 | status.md 동기화 |
| context/observability/architecture.md | 모니터링 레이어 + 호출 흐름 갱신 | context 환류 |
| context/observability/glossary.md | 신규 용어 9건 추가 | context 환류 |
| context/observability/status.md | FR-OBS-8/9/10/11 ✅ | status.md 동기화 |
| context/place/status.md | FR-PLC-9/10 ✅ | status.md 동기화 |
| docs/ARCHITECTURE.md | 아키텍처 대규모 갱신 | 사용자 결정 (PR-B 무관) |
| docs/ERD.md | ERD 신규 | 사용자 결정 (PR-B 무관) |
| docs/TECH.md | 기술 스택 신규 | 사용자 결정 (PR-B 무관) |
| docs/architecture-diagram.png | 이미지 자산 | 사용자 결정 (PR-B 무관) |
| docs/operations/slack-alerts.md | PR-B 알림 정책 갱신 | context 환류 |

**참고**: README.md / docs/ARCHITECTURE.md / docs/ERD.md / docs/TECH.md / docs/architecture-diagram.png 5개는 사용자가 명시적으로 PR-B에 포함시키기로 결정한 항목. context/* 및 docs/operations/slack-alerts.md는 PR-B 완료 산물(환류) 반영.

## 신규 위험

### Warning

- **[ASSUMPTION] AC-14 / BR-5 — Instagram 윈도우 리셋 순서**
  - 위치: `InstagramBlockedRateTracker.java:37-43`, `ThresholdMonitorScheduler.java:123-140`
  - 근거: PRD `BR-5: 판단 → 발송 → 리셋 순서`, `AC-14: 리셋은 판단 후`. 코드는 `flushWindow()`가 스냅샷+리셋을 단일 락에서 동시 수행 후 반환된 Snapshot으로 판단/발송 → 표면적 순서는 "리셋 → 판단 → 발송". 다만 발송은 캡처된 스냅샷 데이터로 이루어지며 리셋 후 발생한 새 카운터는 다음 윈도우로 이동하므로 **의미상 BR-5 충족**(BR-5의 본질은 "리셋 후 알림 발송 금지"). 설계서도 "원자 스왑"으로 의도적 표현 변경 — PRD ↔ 설계 ↔ 코드 표현이 미세하게 어긋남
  - 권고: PRD를 "스냅샷 캡처 → 리셋 → 캡처된 스냅샷으로 판단/발송"으로 정정(권장 — 의미적으로 동등하고 race 구조적 제거 이점 보존). 또는 tracker API를 peek/flush 2단계로 분리해 PRD 문구와 정확히 일치시킴(과한 복잡도)

### Info

- **[GAP] NFR-4 — 스케줄러 최상위 swallow 약속 부분 충족**
  - 위치: `ThresholdMonitorScheduler.java:63-80`
  - 근거: PRD NFR-4 "스케줄러 @Scheduled 본문 **최상위** try-catch(Exception) swallow". 참조 패턴(`PendingInstagramAutoSaveScheduler.java:49-58`)은 task.run() 전체를 단일 try-catch로 감쌈. 현재 `runMonitoringTick()`은 (1) `MDC.put` (2) 각 check 개별 try-catch (3) finally `MDC.clear()` 구조로, MDC put 호출 또는 check 사이 외부 예외가 발생하면 잡히지 않을 수 있음. 실제로 MDC.put 자체가 예외를 던지지 않으므로 운영상 영향 미미하나 PRD 약속의 글자와 다름
  - 권고: `runMonitoringTick()` 본문 전체를 추가 try-catch(Exception)로 감싸 PRD 약속과 정확히 일치시킬 것. 1줄 추가로 해소 가능

## 총평

- 강점: Google Places 캐시/메트릭 분리가 코드와 테스트로 잘 맞물려 있고, Gemini 4xx/5xx 체인과 테스트가 명확히 닫혔음. Instagram/Gemini 모두 알림 컨텍스트와 쿨다운이 구현되어 운영 관측성은 설계 의도에 도달
- 합산: Critical 0건 / Warning 1건 / Info 1건. AC 17/19 O, 2건 부분 충족
- 권고: PRD `BR-5` 표현을 코드 동작과 일치시키거나(권장), `runMonitoringTick()`에 최상위 try-catch를 1줄 추가하여 NFR-4와 일치시킴

---

## 처리 결과 (사용자 결정: 둘 다 수정)

| # | 항목 | 처리 |
|---|------|------|
| 1 | Warning [ASSUMPTION] AC-14/BR-5 표현 불일치 | **PRD 정정** — `prd.md`의 BR-5 / FR-11-5 / AC-14 세 곳을 "스냅샷 캡처 → 리셋 → 캡처된 스냅샷으로 판단/발송"으로 갱신. 코드 동작은 그대로 (의미상 BR-5 충족) |
| 2 | Info [GAP] NFR-4 최상위 try-catch 미적용 | **코드 수정** — `ThresholdMonitorScheduler.runMonitoringTick()` 본문 전체를 `try-catch(Exception)`로 감싸 MDC put이나 finally 외부 예외도 swallow. 1회 실패가 다음 1h tick 차단 금지 보장 |

**검증**: `./gradlew :apps:wherewego-api:test` Green (exit 0).
**커밋**: 676976a `fix: ThresholdMonitorScheduler runMonitoringTick 본문 최상위 try-catch 추가 — NFR-4 충족 (cross-review)`.
**PR**: https://github.com/rnqhstmd/wherewego/pull/29 에 자동 반영.

cross-review에서 발견한 2건이 모두 해소되어 AC 19건 전부 O 충족 + NFR-4 명시적 충족 상태로 정렬됨.
