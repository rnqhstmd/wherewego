# 자기점검 결과 (phase-implement)

> qa-manager 자기점검 1회. CERTAIN Critical 0건 → 자동수정 없음. 아래는 phase-review 이월분.
> AC 커버리지: **22/22 충족**(코드·테스트 확인). 빌드/단위/통합테스트 BUILD SUCCESSFUL.

## SELF_CHECK_FINDINGS (Warning/Info — phase-review 이월)

- [Warning] AuthService.java(withBulkhead) - Apple 로그인 Bulkhead 과부하/InterruptedException 시 `AUTH_KAKAO_API_FAILED`(502) 반환. loginWithKakaoNative·loginWithApple 공유 메서드라 **Apple 경로도 카카오 에러코드** 응답 → 공급자별 구분(BR-3/QE-2) 위반. 수정: withBulkhead에 overloadError 파라미터 추가, Apple은 AUTH_APPLE_JWKS_UNAVAILABLE(또는 공용 코드) 전달.
- [Warning] LoginRetryListener.java:35 - javadoc이 기존 `upsertAndIssueTokens`만 참조. 신규 `upsertByOauthAndIssueTokens`도 RETRYABLE 동기화 대상이므로 링크 추가(문서). 기능 영향 없음.
- [Info] AppleIdentityTokenVerifier.java(verify) - rawNonce null 시 `expectedNonce==null || !equals(...)` OR 조건이 먼저 평가되어 NPE 방어됨(동작 정상). 조건 순서 의도를 주석으로 명시 권장.

## SELF_CHECK_QUESTIONS (phase-review에서 확인)

1. @Recover 런타임 매칭 PoC — coder가 `UserLoginPersistenceRetryIT`(4) + 통합테스트로 신규 1인자 `@Recover`가 기존 3인자와 모호성 없이 매칭됨을 검증함. **(a) 통과 확인됨**으로 정리(이슈 없음). review에서 재확인.
2. Apple 재로그인/최초 email null — claims.email·cmd.email 모두 null이면 email=null로 신규 생성 가능(Apple private relay 미사용·미제공). BR-9("최초 1회 저장")상 의도된 동작 **(a)**. AC에 email 필수 명시 없음.
3. Controller 에러 래핑 — 글로벌 ControllerAdvice가 CoreException 일관 처리. native 핸들러도 동일 경로 **(a)**.

## AC 커버리지 (22/22)
AC-1~5 필터(JwtAuthenticationFilterTest), AC-6~8 kakao native, AC-9~15 apple native(+탈퇴자), AC-16~18 refresh body+쿠키회귀, AC-19~20 V014 백필·UNIQUE 유지, AC-21 oauth 조회, AC-22 임시닉네임. 전부 테스트 존재.
