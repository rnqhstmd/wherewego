# Cross-Review 결과

- advisor: claude (qa-manager + security-auditor, cross-review 미션)
- 브랜치: feat/ios-native-p1-auth (base: develop)
- DEV_DIR: .dev/feat-ios-native-p1-auth
- 실행: 2026-06-01 (gx-dev 완료 후 단발 검증, PR #86 생성 이후)

## AC 충족 매트릭스
**[Must] AC-1~22 전부 충족 (22/22).** 각 AC가 단위·RetryIT·통합·마이그레이션 4계층 테스트로 독립 검증됨.
- AC-1~5 필터(Bearer/쿠키/동시/만료/위변조), AC-6~8 Kakao native(+app_id), AC-9~14 Apple JWKS·aud·exp·nonce, AC-15 탈퇴자(Kakao+Apple), AC-16~18 refresh(body·쿠키회귀), AC-19~20 V014 백필·UNIQUE 유지, AC-21 oauth 조회, AC-22 임시닉네임.

## 설계 범위 이탈
**이탈 없음.** 설계 미명시 7파일 전부 정당:
- `AppleLoginCommand.java` — NativeLoginCommand에서 Apple 입력 분리(구조 선택, codemap 기록).
- `KakaoAccessTokenInfoResponse.java`, `KakaoApiProperties.appId` — review 1회차 사용자 결정(app_id 검증) 추가.
- `KakaoSkillSecretFilterTest`, `AuthServiceTest`, `test/resources/application.yml` — 시그니처/의존성 변경에 따른 컴파일·인프라 픽스.
- `context/auth/*` — 도메인 환류(산출물 자체).

## 신규 위험 (trust-ledger 중복 제외)

### Warning
1. **AC-8 테스트 커버리지 부분 불완전** — `kakaoNative_kakao4xx_returns502`는 access_token_info 성공 후 `/v2/user/me` 4xx만 검증. **access_token_info 자체가 4xx인 위변조 경로**의 명시 테스트 없음(동작은 동일 502). trust-ledger 2회차 "access_token_info 4xx→502 = AC-8 정합(수용)"의 테스트 보강 차원.

### MEDIUM
2. **[ASSUMPTION] `NativeLoginCommand.toNewUser()` KAKAO `Long.valueOf(oauthId)` 미방어** — 정상 팩토리 경로는 안전하나, KAKAO인데 oauthId가 비숫자인 객체를 직접 생성 시 `NumberFormatException`→500(@Recover 미포착). 계약 명확화 권고.
3. **[RISK] Kakao native 2회 HTTP(access_token_info→user/me) 만료 레이스** — 두 호출 사이 토큰 만료 시 502(`AUTH_KAKAO_API_FAILED`)로 "위변조"와 동일 코드 → 클라 재시도 판단 오도. AC-8이 "위변조→502" 명시라 세분화 시 AC 수정 동반.

### LOW
4. **[GAP] `AppleLoginCommand.nonce` 불변식 미강화** — DTO에 `@NotBlank` 있고 verifier 진입부 가드도 있으나, 도메인 record 자체엔 null 거부 없음. 방어 심도 차원.

## trust-ledger 신선도
2회차 기록 항목 **재발/미해소 0** (outageTolerant·503·nonce·@Recover·AC-15 Kakao·V014 실DB 전부 해소 확인).
- 잔여(기결정 이월): `KAKAO_APP_ID:0` 로컬 기본값 — `@NotNull`만 적용(0 통과), `@Positive` 미적용. prod는 `${KAKAO_APP_ID}` fail-fast로 실위험 낮음. review 2회차 "진행+이월" 결정.
- P2 이월 유지: 기존 웹 콜백 Bulkhead/@Recover 502.

## 총평
- **강점**: AC 22/22 4계층 테스트, V014 격리스키마 실DB 백필 검증, 1회차 CRITICAL/HIGH 7건 전부 대응+테스트.
- **합산(신규)**: Critical 0, HIGH 0, Warning 1, MEDIUM 2, LOW 1. trust-ledger 재발 0.
- **권고**: P1 배포 차단 사유 없음(PR #86). 신규 항목은 경미한 하드닝으로, 1·2·4는 저비용 수정 가능, 3·잔여 @Positive는 P2 권고.

## 처리 결과 (사용자: 1·2·4 수정)
- **1번 [Warning] AC-8 테스트**: 수정됨 — `AuthV1ControllerIntegrationTest.kakaoNative_accessTokenInfo4xx_returns502` 추가(access_token_info 자체 4xx→502).
- **2번 [MEDIUM] Long 파싱 방어**: 수정됨 — `NativeLoginCommand.toNewUser()` KAKAO 분기 `NumberFormatException`→`CoreException(BAD_REQUEST)`(식별자 형식 불량은 400이 의미상 정확).
- **4번 [LOW] AppleLoginCommand 불변식**: 수정됨 — compact constructor에서 `nonce`+`identityToken` null/blank 거부(`AUTH_APPLE_TOKEN_INVALID`, verifier와 동일 코드).
- **3번 (만료 레이스 에러코드)·잔여 @Positive**: P2 이월(미수정).
- 빌드: compile + 영향 테스트 EXIT=0(Docker 통합 포함). 커밋·PR #86 반영.
