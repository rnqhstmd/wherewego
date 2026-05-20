# observability

외부 API 호출과 운영 장애를 가시화하고 임계값 기반 Slack 알림으로 사후 발견을 사전 감지로 전환하는 횡단 도메인.

## 배경

Phase 0에서 Spring Boot Actuator + Prometheus + Grafana(JVM 대시보드)로 인프라/JVM 메트릭은 관측 가능해졌다. Gemini 도메인은 [[place]] Phase 2.5에서 `GeminiUsageMetrics`(Micrometer Counter/Timer) + `GeminiUserQuotaService`(Caffeine 일일 50회) + `GeminiResponseCacheService`(SHA-256, 24h)의 메트릭/캐시/쿼터 3종 세트가 완비되었다.

그러나 같은 핀 등록 흐름에 함께 등장하는 **Google Places API**(월 $200 한도, [[place]] Phase 5), **Instagram 캡션 스크래퍼**([[place]] Phase 2), **Kakao Callback**([[chatbot]] Phase 5)은 메트릭·캐싱·재시도가 0개다. Slack 알림 5곳(`PlaceFallbackOrchestrator`, `ChatbotRateLimitFilter`, `KakaoCallbackClient`)은 모두 단건 실패 시 즉시 발송으로, 누적 임계값이나 한도 잔량 기반의 사전 경고가 없다. MDC/RequestId 미사용으로 슬랙 알림 → 로그 역추적도 불가능하다.

## 안 하면 어떻게 되는가

- Google Places 월 $200 한도 소진이 핀 등록 흐름의 사용자 모르는 실패로 표면화 — 챗봇 핀 자동 등록 70% 성공률 SLA([[chatbot]]) 위반 위험
- Instagram 차단 시 캡션 추출 자동화 가치 즉시 소실 — `InstagramScraperClient`의 3-stage 폴백(NO_UA → CHROME_UA → FULL_HEADERS) 실패율 추적 없어 차단 패턴 사후 발견
- Kakao Callback 실패는 `KakaoCallbackClient.push()`가 재시도 0회로 단발 실패 시 비동기 폴백 결과 자체가 사용자에게 유실 → "검색 결과를 받지 못함"
- 운영자 1인 체제(rnqhstmd)에서 외부 신호 없이 인지 불가 → 평균 복구 시간(MTTR) 폭증 및 신뢰 하락

## 사용자와 규모

- **알림 수신자**: rnqhstmd 1명, Slack 단일 채널(`SLACK_WEBHOOK_URI`)
- **모니터링 대상 트래픽 (추정)**:
  - 챗봇 Webhook ~100건/일 ([[chatbot]])
  - Google Places ~30건/일 (월 ~900건, 한도 약 10K req/$200) ([[place]])
  - Gemini ~100건/일, 사용자별 50회 한도 ([[place]])
  - Kakao OAuth: 로그인당 2회 호출 ([[auth]])
- **임계값 설계 기준**:
  - Google Places: 일일 누적 호출 80% (8K) → notifyWarning, 95% (9.5K) → notifyFailure
  - 외부 API 5xx/timeout 비율 ≥ 10% (1시간 윈도우) → notifyWarning
  - Instagram scraper 3-stage 최종 실패율 ≥ 50% (1시간 윈도우) → notifyFailure
- Slack 알림 쿨다운: 5~30분 ([[chatbot]] `ChatbotRateLimitFilter` 5분 쿨다운 패턴 차용)

## 성공 기준

- 외부 API 사고 인지 경로: 사용자 제보 → Slack 사전 경고로 전환 (목표 MTTR ≤ 30분)
- Google Places 일일 한도 95% 도달 시 자동 Slack 알림 정확도 ≥ 99% (오탐 ≤ 1건/월)
- 모든 외부 API 호출에 RequestId/duration_ms/outcome 구조화 로그 발행
- Slack 알림 본문에 RequestId 동봉 → 로그 역추적 1-step 가능

## 담당자

| 역할 | 이름 | 비고 |
|------|------|------|
| PM/PO | rnqhstmd | 1인 개발 |
| 개발 리드 | rnqhstmd | |

## 현재 상태

탐색 중 — Phase 2.11(가칭) observability foundation 계획 단계
