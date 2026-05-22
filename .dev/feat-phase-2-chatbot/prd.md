# PRD: Phase 2 — 카카오톡 챗봇 Skill Webhook + 장소 파이프라인

## 배경

우리가갈지도(wherewego)는 커플·소그룹이 가고 싶은 장소를 공유·저장하는 서비스. Phase 2에서는 카카오톡 챗봇을 통해 인스타그램 링크를 공유하면 자동으로 장소 핀이 등록되는 경험을 구현한다.

**현재 제품 상태:**
- Phase 0에서 DB 스키마(`bot_link_codes`, `bot_user_mappings`, `pins`) 및 인프라 기반 구축 완료
- `bot_link_codes`: CHAR(6) **6자리** 코드, TTL 10분, 활성 코드 유저당 1개 Partial UNIQUE INDEX
- `bot_user_mappings`: botUserKey ↔ user_id 영구 매핑, user_id·bot_user_key 각각 UNIQUE
- `pins`: instagram_url + group_id UNIQUE, tag(PLACE/MEMORY), memo_source(AUTO/MANUAL) 정의됨
- Kakao Local API 키, Kakao Skill Secret, Kakao Bot ID 환경변수 정의 완료
- Redis 제거(ADR-0002): 6자리 코드 TTL 및 2초 룰 세션을 Caffeine 인메모리 캐시로 처리
- 인스타그램 메타 스크래핑 spike 코드 존재 — 운영 적용 전 법무 검토 필요
- 5초 SLA 정책: Kakao Local까지 동기, Google Places 폴백은 비동기 + 카카오 콜백

## 목표

- 카카오톡 챗봇에서 인스타그램 링크 공유 → 장소 핀 자동 등록
- 웹에서 **6자리 연동 코드** 발급 → 카카오톡 봇 계정과 웹 계정 연결
- 챗봇 2초 룰: 인스타 링크 직후 2초 이내 텍스트를 메모로 자동 저장
- 성공 지표: 베타 사용자(~100명)가 챗봇 연동 후 인스타 링크 공유만으로 핀 등록 완료

## 요구사항

### 기능 요구사항

**[연동 코드 발급 — BOT]**
- [Must] FR-BOT-1: 로그인한 웹 사용자가 챗봇 연동 메뉴에서 **6자리** 숫자 연동 코드 발급
- [Must] FR-BOT-2: 기존 미사용 활성 코드가 있으면 기존 코드 만료 후 신규 발급
- [Must] FR-BOT-3: 발급된 6자리 코드는 10분 TTL 적용 (Caffeine 캐시 + DB 이력 저장 병행)
- [Must] FR-BOT-4: 챗봇에 6자리 코드 입력 시 코드 유효성 검증 후 botUserKey ↔ user_id 영구 매핑 저장
- [Must] FR-BOT-5: 코드 만료(10분 초과) 또는 이미 사용된 코드 입력 시 재발급 안내
- [Must] FR-BOT-6: 이미 연동된 사용자가 코드 재입력 시 기존 매핑 유지 + "이미 연동됨" 안내

**[Skill Webhook 라우팅 — BOT]**
- [Must] FR-BOT-7: Skill Webhook 수신 시 메시지 내용에 따라 **6자리 코드 / 인스타 링크 / 2초 룰 텍스트 / 알 수 없음**으로 분기
- [Must] FR-BOT-8: 미연동 사용자(botUserKey 미매핑)가 인스타 링크 또는 텍스트 전송 시 "먼저 웹에서 연동해주세요" 안내
- [Should] FR-BOT-9: Skill Webhook 요청에 `KAKAO_SKILL_SECRET` 헤더 검증. 불일치 시 401

**[인스타그램 장소 파이프라인 — PLC]**
- [Must] FR-PLC-1: 인스타그램 URL 수신 시 HTML 메타 스크래핑(og:title/og:description)으로 장소명 후보 추출 (📍이모지 → 키워드 → 해시태그 우선순위)
- [Must] FR-PLC-2: 추출된 장소명으로 Kakao Local API 키워드 검색 (동기, 5초 SLA 내)
- [Must] FR-PLC-3: Kakao Local 결과 0건이면 Google Places API 비동기 검색 + 카카오 콜백 메시지 푸시
- [Must] FR-PLC-4: 검색 결과 1건이면 pins 자동 등록 (tag=PLACE, memo_source=NULL)
- [Must] FR-PLC-5: 검색 결과 복수면 리스트 카드(최대 5건)로 선택 요청
- [Must] FR-PLC-6: 검색 결과 0건이면 폴백 메시지("어느 곳인가요?")
- [Must] FR-PLC-7: 동일 그룹 내 동일 instagram_url 중복 등록 차단 + 안내 (UNIQUE 제약 활용)
- [Should] FR-PLC-8: 장소명 추출 실패(스크래핑 실패, 패턴 미매칭) 시 FR-PLC-6 폴백 분기

**[그룹 매칭 — GRP]**
- [Must] FR-GRP-1: 핀 자동 등록 시 복수 그룹 가입자는 **가장 최근 가입한 그룹**에 자동 등록 (BR-6)
- [Should] FR-GRP-2: 그룹 미가입 사용자가 인스타 링크 공유 시 "그룹에 먼저 참여해주세요" 안내

**[2초 룰 메모 — MEMO]**
- [Must] FR-MEMO-1: 인스타 링크 수신 후 2초 이내 동일 botUserKey 텍스트 메시지 수신 시 해당 핀 memo 필드에 저장 (memo_source=AUTO)
- [Must] FR-MEMO-2: MANUAL 메모가 이미 존재하면 AUTO로 덮어쓰지 않음 (수동 우선)
- [Must] FR-MEMO-3: 2초 룰 메모 저장 성공 시 **별도 챗봇 응답 없이 조용히 처리**
- [Should] FR-MEMO-4: 2초 이후 텍스트는 FR-BOT-7 "알 수 없음" 분기

### 비즈니스 규칙

- [Must] BR-1: 6자리 연동 코드는 숫자 6자리 난수. 충돌 시 재생성.
- [Must] BR-2: 활성(미사용) 코드는 유저당 최대 1개. 신규 발급 시 기존 활성 코드 자동 만료.
- [Must] BR-3: 연동 코드 TTL은 발급 시점으로부터 10분.
- [Must] BR-4: botUserKey ↔ user_id 매핑은 영구. 재연동 시 기존 매핑 유지.
- [Must] BR-5: 핀 자동 등록 기본 tag = PLACE.
- [Must] BR-6: 복수 그룹 가입자 핀 등록 대상 = 가장 최근 가입한 그룹 (자동 선택).
- [Must] BR-7: 동일 그룹 내 동일 instagram_url 중복 등록 불가 (DB UNIQUE).
- [Must] BR-8: 챗봇 응답 5초 SLA 준수. Kakao Local까지 동기, Google Places 폴백 비동기.
- [Must] BR-9: 2초 룰 세션 = Caffeine 캐시(`expireAfterWrite(2s)`, key=botUserKey).
- [Must] BR-10: 런타임 feature flag로 FR-PLC-1 비활성화 가능 — 환경변수 `INSTAGRAM_SCRAPING_ENABLED=false` 설정 시 FR-PLC-1 우회 + FR-BOT-7 폴백 직접 분기. 기본값 `true`.
- [Should] BR-11: 메모 수동 우선 정책 — memo_source=MANUAL이 존재하면 AUTO 덮어쓰기 불가.

### 품질 기대

- [Should] QE-1: 베타 사용자 ~100명, 일 트래픽 ~30건 규모에서 5초 SLA 위반 없음
- [Should] QE-2: 인스타 스크래핑 차단(403/429) 시 사용자에게 오류 노출 없이 폴백 흐름 전환
- [Should] QE-3: `INSTAGRAM_SCRAPING_ENABLED=false` 전환이 재배포 없이 즉시 적용 가능

## 사용자 시나리오

### 정상 흐름 A — 최초 연동
1. 사용자가 웹에서 "챗봇 연동" 메뉴 진입
2. 6자리 코드 발급 요청 → 코드 화면 표시 (10분 내 유효)
3. 카카오톡 챗봇에 6자리 코드 입력
4. 챗봇이 코드 검증 → 매핑 저장 → "연동 완료" 응답

### 정상 흐름 B — 인스타 링크 공유
1. 연동된 사용자가 챗봇에 인스타그램 URL 전송
2. `INSTAGRAM_SCRAPING_ENABLED=true`: 스크래핑 → 장소명 추출 → Kakao Local 검색
3. 결과 1건: 최근 가입 그룹에 자동 등록 → "○○ 장소가 [그룹명]에 저장됐어요"
4. 결과 복수: 리스트 카드(최대 5건) → 선택 → 등록
5. 결과 0건 (Kakao): Google Places 비동기 호출 → 콜백 메시지 푸시

### 정상 흐름 C — 2초 룰 메모
1. 사용자가 인스타 링크 전송 → 핀 자동 등록
2. 2초 이내 "분위기 좋은 곳" 텍스트 추가 전송
3. 서버가 2초 룰 세션 감지 → memo AUTO 저장 → **별도 챗봇 응답 없음**

### 예외 흐름
- 코드 10분 만료: "코드가 만료됐어요. 웹에서 다시 발급해주세요."
- 미연동 사용자 링크 공유: "웹에서 먼저 연동해주세요."
- 동일 URL 중복 공유: "이미 [그룹명]에 저장된 장소예요."
- `INSTAGRAM_SCRAPING_ENABLED=false`: 즉시 "어느 곳인가요?" 폴백 반환
- 스크래핑 차단(403/429): 폴백 처리, 사용자에게 오류 미노출

## 스키마 (V001 기준, 변경 없음)

| 테이블 | 핵심 컬럼 | 비고 |
|--------|----------|------|
| `bot_link_codes` | `code CHAR(6)`, `expires_at`, `used_at` | 6자리 코드, 활성 1개/유저 UNIQUE |
| `bot_user_mappings` | `bot_user_key`, `user_id` | 영구 매핑, 양방향 UNIQUE |
| `pins` | `instagram_url`, `group_id`, `memo`, `memo_source`, `tag` | 중복 방지 UNIQUE(group_id, instagram_url) |

## 에러 코드 (신규)

| 코드 | HTTP | 메시지 | 발생 조건 |
|------|------|--------|----------|
| `BOT_CODE_NOT_FOUND` | 404 | 유효하지 않은 연동 코드입니다 | 코드 미존재 또는 만료 |
| `BOT_CODE_ALREADY_USED` | 409 | 이미 사용된 연동 코드입니다 | used_at NOT NULL |
| `BOT_ALREADY_LINKED` | 409 | 이미 연동된 계정입니다 | bot_user_mappings UNIQUE 충돌 |
| `BOT_USER_NOT_LINKED` | 403 | 연동되지 않은 사용자입니다 | bot_user_mappings 미존재 |
| `BOT_WEBHOOK_UNAUTHORIZED` | 401 | 인증되지 않은 웹훅 요청 | Skill Secret 불일치 |
| `PLC_DUPLICATE_PIN` | 409 | 이미 저장된 장소입니다 | UNIQUE(group_id, instagram_url) |
| `PLC_NO_GROUP` | 403 | 그룹에 먼저 참여해주세요 | 그룹 미가입 |
| `PLC_PLACE_NOT_FOUND` | 200 | (폴백 메시지) | 검색 결과 0건 |

## 수용 기준 (AC-1 ~ AC-18)

| # | AC | 연결 |
|---|----|------|
| AC-1 | 웹 로그인 사용자가 6자리 숫자 연동 코드 발급 + 화면 표시 | FR-BOT-1, BR-1 |
| AC-2 | 활성 코드 보유 중 재발급 시 기존 코드 만료 + 새 6자리 발급 | FR-BOT-2, BR-2 |
| AC-3 | 발급된 코드는 10분 후 만료 (Caffeine TTL + DB expires_at) | FR-BOT-3, BR-3 |
| AC-4 | 챗봇 유효 6자리 코드 입력 시 bot_user_mappings 영구 매핑 | FR-BOT-4 |
| AC-5 | 만료/사용 코드 입력 시 재발급 안내 | FR-BOT-5, BR-3 |
| AC-6 | 이미 연동된 사용자 재입력 시 매핑 유지 + 안내 | FR-BOT-6, BR-4 |
| AC-7 | 미연동 사용자 인스타 링크 전송 시 연동 안내 | FR-BOT-8 |
| AC-8 | `INSTAGRAM_SCRAPING_ENABLED=true` 환경에서 og:title/og:description 스크래핑 수행 | FR-PLC-1, BR-10 |
| AC-9 | Kakao Local 1건 → 최근 가입 그룹에 tag=PLACE 자동 등록 | FR-PLC-4, FR-GRP-1, BR-5, BR-6 |
| AC-10 | Kakao Local 복수 → 리스트 카드(최대 5건) | FR-PLC-5 |
| AC-11 | Kakao Local 0건 → Google Places 비동기 + 콜백 푸시 | FR-PLC-3, BR-8 |
| AC-12 | 장소명 추출 실패 → "어느 곳인가요?" 폴백 | FR-PLC-6, FR-PLC-8 |
| AC-13 | 동일 그룹 동일 instagram_url 재공유 시 중복 안내 | FR-PLC-7, BR-7 |
| AC-14 | 인스타 링크 후 2초 내 텍스트 수신 시 memo AUTO 저장 | FR-MEMO-1, BR-9 |
| AC-15 | MANUAL 메모 존재 시 2초 룰 텍스트로 변경 안 됨 | FR-MEMO-2, BR-11 |
| AC-16 | 2초 룰 메모 저장 성공 시 챗봇 응답 메시지 미발송 | FR-MEMO-3 |
| AC-17 | 복수 그룹 가입자 핀 = 최근 가입 그룹 자동 결정 | FR-GRP-1, BR-6 |
| AC-18 | `INSTAGRAM_SCRAPING_ENABLED=false` 환경에서 스크래핑 시도 없이 폴백 메시지 즉시 반환 | FR-PLC-1, BR-10 |

## 제외 범위

- 웹 UI 핀 등록 화면 변경 (Phase 후반)
- TikTok / YouTube 링크 파이프라인 (ContentParser 인터페이스만 설계, 구현 미포함)
- Google Places API 직접 연동 (Phase 5로 분리 — 현재 환경변수만 정의)
- 챗봇 발화 분석 / NLP 의도 파악
- 그룹 선택 UI (BR-6 자동 결정으로 제외)
- glossary.md "5자리 연동 코드" → "6자리 연동 코드" 용어 통일 (후속 cross-review)

## 알려진 리스크

| 리스크 | 대응 |
|--------|------|
| 인스타그램 스크래핑 법무 미승인 | 배포 전 법무 최종 승인 필수. 미승인 시 `INSTAGRAM_SCRAPING_ENABLED=false` 배포 — 코드 변경 없이 즉시 무력화 |
| 인스타그램 IP 차단 (403/429) | HtmlFetcher 3-stage 우회 + 차단 시 폴백 흐름 자동 전환 |
| Caffeine 재시작 시 세션 손실 | 10분 코드 / 2초 메모는 손실 시 재발급/재시도 즉시 복구 (ADR-0002) |
| Kakao 5초 SLA 초과 | Google Places 폴백은 비동기. 동기 경로는 Kakao Local까지만 |
