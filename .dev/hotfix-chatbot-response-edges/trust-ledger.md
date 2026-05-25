## Trust Ledger

### Hotfix 긴급 감사

#### CRITICAL
없음

#### HIGH
- **[RISK/HIGH] FR-3 echo back utterance 길이 가드 누락 — 카카오 SimpleText 1000자 초과 시 5xx 가능**
  - 위치: `InstagramPendingMemoHandler.java:66`
  - 근거: 응답 텍스트 = 고정 접두/접미(~55자) + utterance. utterance가 945자를 초과하면 1000자 초과로 카카오가 응답을 거부할 수 있음. `InstagramContentService.truncate()`는 caption 처리용이며 utterance에는 적용되지 않음.
  - 권고: 메시지 조립 직전 `utterance`를 약 900자로 절단하는 가드 추가.

#### 점검 요약
1. utterance echo back 인젝션: SimpleText는 HTML 렌더링 없음 → XSS 없음. 단, 길이 초과 (HIGH).
2. botUserKey 신뢰성: KakaoSkillSecretFilter가 MessageDigest.isEqual로 timing-safe 검증. 위조 차단.
3. FR-4 정확 매칭 우회: trim() + equals() — 공백 변형 차단. 변형 유니코드 메모 저장은 정책상 정상.
4. FR-2 fallback 적재 누적/DoS: 단일 키, 덮어씀, TTL 7일. 위험 없음.
5. 로깅 PII: 변경 3개 파일에 신규 로그 없음.
6. 세션 race: PendingInstagramSession.peek은 읽기 전용 atomic. 경쟁 없음.

**CRITICAL 0 / HIGH 1**

---

### Hotfix 긴급 감사 (재호출 1회차)

#### HIGH 해소 여부
- 이전 HIGH-1 (InstagramPendingMemoHandler.java:64-66): **해소**
  - `utterance.length() > 900` 절단 + "…" 첨가 → safeUtterance 최대 901자.
  - 고정 접미 62자 포함 최대 963자 < 1000자 카카오 SimpleText 제한.
  - 컴파일 Green 확인.

#### 신규 CRITICAL/HIGH
- 없음

**최종: CRITICAL 0 / HIGH 0**
