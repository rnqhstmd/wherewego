## Trust Ledger — Phase 14 로그인 콜드 스타트 안정화

### 통합 감사 (review)

결론: **CRITICAL 0건, HIGH 0건.** 변경은 안정성 개선 목적이며 새 공격 표면을 의미 있게 추가하지 않음.

- [동시성·리소스 / MEDIUM] connection-timeout 10s 상향 × bulkhead 상호작용
  - 근거: bulkhead permit 5개 + @Retryable maxAttempts=2. Neon 완전 장애 시 로그인 스레드가 최대 ~20.5s permit 점유 → 동시 로그인 처리량이 기존(10.5s) 대비 약 절반으로 하락. 일반 API는 별도 풀이라 영향 없음(bulkhead 격리).
  - 권고: 수용. Neon 완전 장애 시 로그인 적체 가능성을 운영 문서에 명시. 추후 bulkhead permit·timeout 동반 튜닝.
- [가시성 / MEDIUM] keep-warm 예외 삼킴이 지속 장애 은폐
  - 근거: keep-warm 연속 실패해도 WARN 로그만 남고 알림 없음(PRD FR-4 [Should] 미구현). 기본 OFF라 현재 영향 없음.
  - 권고: keep-warm 활성화 시점에 FR-4(연속 실패 Slack 알림) 또는 메트릭(`db.keepwarm.failure`) 추가를 후속 과제로.
- [정확성 / LOW] 활성 윈도우 zone 의존: `Clock.system(Asia/Seoul)`로 서버 TZ 무관하게 KST 정확 동작 확인. 반열림 [start,end) 구간이라 자정 교차 윈도우(예: 22~6시)는 미지원(현 07~23 설정엔 무관).
- [정보 노출 / LOW] 카카오 타임아웃 예외는 일반화 메시지로 변환(AUTH_KAKAO_API_FAILED), 내부 상세 미노출. 양호.

검증된 가정(이상 없음): 시크릿 평문 추가 없음 / keep-warm SQL은 하드코딩 "SELECT 1"(인젝션 불가) / worst ~20.5s < Tomcat 60s < Cloudflare 100s / KakaoOAuthClient 생성자 시그니처 불변·정상 경로 무변화 / UserLoginPersistence 로직 불변 / @ConditionalOnProperty 기본 OFF로 운영 영향 0.

### QA 리뷰 (review)
- [Warning→수정완료] NeonKeepWarmScheduler 예외 포착을 `catch (SQLException)` → `catch (Exception)`으로 확대 (설계 정합). 테스트에 런타임 예외 케이스 추가.
- [Info→수정완료] 테스트 중복 호출 제거.
- 수용 기준 AC-1~AC-5 충족 확인.

### 후속 과제 (이번 PR 범위 밖)
1. keep-warm 활성화 시 FR-4(연속 실패 알림) + 컴퓨트 소비 모니터링.
2. Neon 완전 장애 시 로그인 적체 운영 가이드.
3. (선택) bulkhead permit·connection-timeout 동반 튜닝.
