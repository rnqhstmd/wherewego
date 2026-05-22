# 2026-05-20 작업 요약 — 게이트 통합 + 챗봇 메모 흐름 + 서브메뉴 UX 정리

> 직전(`SESSION_2026_05_19_CHANGES.md`) 이후 이어진 작업. 커밋 4개로 정리됨.

## 0. 커밋 범위

| 커밋 | 메시지 |
|---|---|
| `6a3d5a8` | feat: 게이트 6자리 코드 + 챗봇 코드 충돌 해소 + 모바일 UX 통일 |
| `23dac51` | feat: 게이트 화면 통합 + 챗봇 카드 흐름 제거 + 서브메뉴 UX 정리 |
| `2ecd7c6` | feat: 인스타 링크 메모 흐름 도입 (Pending 세션 기반) |
| `9618713` | fix: PinService.registerFromInstagram(... , memo) 오버로드 누락 보완 |

## 1. 인증/게이트 — `BASIC_AUTH_USER/PASSWORD` → `GATE_INVITE_CODE`

### 정책
2인 비공개 서비스 진입을 ID+PW(2 입력)에서 **단일 6자리 초대 코드** 입력으로 단순화. 코드 통과 시 같은 화면에서 카카오 시작하기 버튼이 등장.

### 변경
- `frontend/src/lib/auth/gate.ts`
  - HMAC seed `${user}:${password}` → `code` 단일
  - `verifyCredentials(user, password)` → `verifyInviteCode(code)`
- `frontend/src/app/api/auth/gate/route.ts`: POST body `{user, password}` → `{code}`
- `frontend/src/app/gate/page.tsx`: **로그인 화면 디자인으로 통합**
  - GlobeBg + 큰 브랜드 워드마크 + PinDot divider (로그인 화면과 동일)
  - 2단계 state(`verified`) — 입력 폼이 카카오 버튼으로 자연스럽게 전환
  - 부제 항상 노출: "우리의 장소를 지도 위에 아카이빙해요" + "초대받은 분만 입장할 수 있어요"
- `frontend/.env.local`: `BASIC_AUTH_USER`/`PASSWORD` 제거 → `GATE_INVITE_CODE=251222`

### 보호 흐름 (재확인)
`middleware.ts`가 정적 자산 외 모든 경로(`/login`, `/login/callback`, `/api/v1/auth/kakao/*`, BFF 프록시 등)를 게이트 검증으로 막음. 카카오 OAuth code를 손에 들고 콜백 URL 직접 접근해도 게이트 미통과면 거부.

## 2. 챗봇 흐름 — 3단계 리팩토링

### 2-1. 코드 입력 충돌 해소 (slot filling 기반)
- `MessageClassifier`: utterance `^\d{6}$` 정규식 제거 → `action.params.code` 기반 분류
- `LinkCodeHandler`: `params.code`에서만 추출
- 카카오 i 오픈빌더의 "그룹 연동" 블록에서 slot 변수명 `code`로 설정 필요
- 결과: 일반 6자리 숫자 메시지가 더 이상 연동 코드로 오인되지 않음

### 2-2. 카드(BasicCard) 흐름 완전 제거
정책: **어떤 경우에도 사용자가 같은 링크를 두 번 보내야 하면 안 됨**.

- `InstagramLinkHandler.handleCandidates` 새 정책:
  - `confident=true` + Google Single/Multiple → 자동 저장
  - `confident=true` + Google Empty → "직접 등록" 안내 (`manualNeeded`)
  - **`confident=false` → Google API 호출 자체 안 함**, 즉시 "직접 등록" 안내
  - deadline 초과한 잔여 candidates → "직접 등록" 안내 (재전송 요구 X)
- 응답은 simpleText 1개로 단순화 (cardOutputs 제거)
- "다시 보내주시면 이어서 등록됩니다" 문구 삭제
- `PlaceCardBuilder` / `MAX_CONFIRMATION_CARDS` 흐름은 legacy fallback에만 남음

### 2-3. 메모 흐름 도입 (Pending 세션)
사용자 요구: "인스타 링크 보내면 메모 입력받은 후 저장". 카카오톡 채널 공유 시트에 wherewego 채널이 안 나오는 정책 한계 우회.

- `PendingInstagramSession` 신설 — Caffeine cache (key=botUserKey, value=instagramUrl, TTL **10분**)
- `MessageType.INSTAGRAM_PENDING_MEMO` 추가
- `MessageClassifier`: 인스타 URL 아닌 메시지 + pending 존재 시 새 타입 분류
- `InstagramLinkHandler.handle`: candidates 처리 안 함. pending 저장 + 안내 + 빠른답장 응답
  - 안내: "📝 이 링크와 함께 저장할 메모를 보내주세요. 메모 없이 저장하거나 취소하려면 아래 버튼을 눌러주세요."
  - QuickReply: `[💾 메모 없이 저장]` `[❌ 취소]`
- `InstagramLinkHandler.processWithMemoAsync` 추출 — 메모 인자 받는 비동기 처리 (기존 candidates 로직 재활용)
- `InstagramPendingMemoHandler` 신설:
  - "취소" → pending 해제 + 안내
  - "저장" → memo=null로 처리
  - 그 외 텍스트 → 메모로 사용
  - 새 인스타 URL → MessageClassifier가 INSTAGRAM_LINK로 분류해서 이전 pending 자동 덮어씀
- `PinService.registerFromInstagram(... , memo)` 오버로드 — memo가 있으면 `applyManualMemo` 호출

### 2-4. 기타 챗봇 변경
- `UnknownHandler`: 옛 메시지 → `ChatbotWebhookService` 폴백과 동일 텍스트로 통일 + `[🔗 그룹 연동하기]` 빠른답장 동봉
- `ChatbotV1Dto.SkillResponse`: `quickReplies` 필드 + `QuickReply.message/block` helper 추가 (이미 적용된 상태에서 활용)
- `ChatbotWebhookService` 미연동 가드에 `INSTAGRAM_PENDING_MEMO` 포함

## 3. 프론트엔드 UX

### 3-1. 모바일 ActionBar 4분할 + Sheet 가림 해소
- `ActionBar.tsx`: [검색][+][룰렛] → **[검색][+][룰렛][닉네임 첫글자 아바타]** 4분할. 마이페이지 진입.
- `MapClient.tsx`: `ActionBar`에 `myNickname` prop 전달
- `Sheet.tsx`: `bottom: 0 → 64`로 보정 — 검색/룰렛 시트가 ActionBar를 더 이상 덮지 않음
- `ActionBar.tsx` zIndex 15 → 25
- `DesktopSidebar.tsx`: 상단 ← (그룹 목록으로) 링크 제거, `IconBack` import 정리

### 3-2. `/groups` 흐름 단순화 (1인 1활성 그룹 정책)
- `groups/page.tsx`: 활성 그룹 보유 시 `redirect("/map")` — 그룹이 있으면 이 화면을 보지 않음
- `GroupsClient.tsx`:
  - 그룹 카드를 `<button>` → `<div>` + nested 2 buttons로 재구성 (상단=메인 클릭, 하단=📨 초대 링크 보내기)
  - "새 그룹 만들기" 점선 카드 → 활성 그룹 보유 시 disabled + 안내 텍스트
- 백엔드는 이미 `GroupMemberService.createGroup`/`acceptInviteLink`에서 1그룹 enforce 됨 (변경 없음)

### 3-3. 핀 생성/검색 카메라 이동
- `MapClient.handlePinCreated`: 새 핀 좌표로 `map.flyTo({zoom:15})`
- `MapClient.handleSelectPlace`: 검색에서 장소 선택 시 해당 좌표로 `flyTo({zoom:15})`
- `MapClient.handleTabChange` (tab=add): 현재 zoom < 13이면 (a) `geoState.granted`면 그 좌표로, (b) 권한 없으면 인라인 `getCurrentPosition`(60s 캐시), (c) 실패면 zoom만 14로 — 좌표 picker UX 개선

### 3-4. `useGeolocation` retry (macOS Chrome 안정성)
- 1차 시도: `{ timeout: 8000, accuracy: false }` 실패 시
- 2차 자동 재시도: `{ timeout: 15000, enableHighAccuracy: true, maximumAge: 60000 }`
- `kCLErrorLocationUnknown` 일시 오류 해소율 ↑
- 룰렛 unavailable 안내: macOS 시스템 설정 / WiFi 가이드 텍스트 추가

### 3-5. 서브메뉴 화면 닉네임 스타일 통일 + 좌상단 ← 뒤로가기
- 적용 화면: `/settings`, `/bot/connect`, `/groups/invite`, `/groups/new`
- 통일 패턴:
  ```
  outer: bg + minHeight 100vh + flex center
  inner column: maxWidth 460, padding "80px 32px 32px"
  Heading: fontSize 32, lineHeight 1.3, letterSpacing -1, whiteSpace pre-wrap
  Subtitle: marginTop 12, fontSize 14, inkSoft
  ...
  하단 BtnPrimary/BtnSub
  ```
- `BackButton.tsx` 공용 컴포넌트 신설 — 36×36, 좌측 -8px 보정, 헤딩과 16px 간격
- `IconBack` 우상단 ← 닉네임 수정 화면 등 모든 서브메뉴 좌상단에 동일 모양 배치

### 3-6. 닉네임 화면 mode 분기 + `/settings/nickname` 신설
- `NicknameClient.tsx`에 `mode?: "onboarding" | "edit"` prop 추가
  - `onboarding`(default): 헤딩 "반가워요 / 이름을 알려주세요" + "다음" → `/onboarding/group-start`
  - `edit`: 헤딩 "닉네임을 입력해주세요" + "저장" → `/settings`
- `settings/nickname/page.tsx` 신설 — `mode="edit"`로 NicknameClient 재사용
- `SettingsClient.tsx`의 닉네임 수정 row가 `/settings/nickname`로 이동

### 3-7. 화면별 텍스트 / 구성 변경
| 화면 | 변경 |
|---|---|
| `/settings` 마이페이지 | 헤딩 "안녕하세요 / 마이페이지에요" → **"마이페이지"** 한 줄. 활성 그룹 카드 안에 [📨 초대 링크 보내기] 행 통합 |
| `/groups/invite` | 헤딩 "친구를 / 초대해요" → **"애인을 초대해요"** 한 줄 |
| `/bot/connect` | 전면 재작성 — 헤딩 "카카오톡 챗봇과 연동해요" 한 줄, 'MayGo' → **'wherewego'** 채널명, 노란 카카오 [💬 wherewego 채널 친구추가] 버튼, **코드값 옆 복사 아이콘**(별도 복사 버튼 제거), 하단 [확인] BtnPrimary |
| `/map` 검색 patch | placeholder "장소 검색 후 Enter 또는 돋보기 클릭" → **"장소 검색"** |

## 4. 환경 변수 추가/변경 요약

### Frontend (`.env.local`)
- ❌ 제거: `BASIC_AUTH_USER`, `BASIC_AUTH_PASSWORD`
- ✅ 추가: `GATE_INVITE_CODE=251222`
- ✅ (선택) 추가: `NEXT_PUBLIC_KAKAO_CHANNEL_URL=https://pf.kakao.com/_HxgdsX/friend` — `BotConnectClient`의 친구추가 버튼 fallback URL

### Backend (`.env`)
- 변경 없음. `KAKAO_SKILL_SECRET` 끝 `=` 패딩 누락에 주의 (44자 정확)

## 5. 카카오 챗봇 시나리오 가이드

`docs/CHATBOT_SETUP_GUIDE.md` 신설/유지 — i 오픈빌더 step-by-step. 핵심:
- 스킬 URL: `https://visiting-amused-tag.ngrok-free.dev/api/v1/chatbot/webhook` (로컬) / `https://{your-domain}/api/v1/chatbot/webhook` (운영)
- 스킬 헤더: `X-Kakao-Skill-Secret: <KAKAO_SKILL_SECRET>` (44자, 끝 `=` 누락 주의)
- 폴백 블록: 봇 응답 = "스킬데이터 사용" (정적 텍스트 제거)
- 그룹 연결 블록: `code` 슬롯 (엔티티 `sys.text`), 패턴 발화에 "그룹 연동하기" 추가
- 카카오 채팅 운영시간: 24시간 또는 OFF (운영시간 외엔 webhook 호출 자체가 막힘)

## 6. 회고 / 트러블슈팅 기록

### 6-1. 카카오 → 백엔드 호출 0건이었던 케이스 진단
ngrok Inspector(`http://localhost:4040`)로 카카오 raw 헤더 추출 → `X-Kakao-Skill-Secret`이 **43자**(`...IvE`)로 끝 `=` 누락 → `BOT_SKILL_SECRET_INVALID` 401 → 사용자 카톡에 응답 표시 안 됨. base64 패딩 `=`가 카카오 입력란 복사 시 자주 빠지는 함정. 운영 배포 시 GitHub Actions Secret `ENV_FILE`의 `KAKAO_SKILL_SECRET=...IvE=` 44자 정확 확인 필수.

### 6-2. 챗봇 응답 보이지 않는 이유 — 채팅 운영시간 자동 응답
카카오 비즈니스 관리자 센터의 채팅 운영시간 설정이 운영시간 외에 webhook을 호출하지 않고 자동 안내만 보냄. 24시간 OR OFF로 설정 + 페이지 저장 + 새로고침으로 적용 확인 필요.

### 6-3. 카드 흐름 제거 결정의 배경
사용자 인스타 사례에서 7개 candidates 모두 `confident=true`로 분류되어 카드 흐름이 발동 안 됨 + deadline 초과로 2개 누락 + "다시 보내라" 안내. 사용자 정책 "어떤 경우에도 재전송 X" → 카드 흐름 자체를 제거하고 confident=false는 Google API 호출도 안 함 + 직접 등록 안내로 통합.

### 6-4. 메모 흐름 — 카카오톡 공유 시트 한계 우회
카카오톡은 친구추가만으로 채널을 공유 시트에 노출 안 함. 사용자가 채널과 1:1 채팅방을 한 번 활성화해야 함. 인스타 → 카톡 공유가 실용적이지 않아 사용자가 링크를 챗봇에 직접 붙여넣는 흐름으로 전환. 그래서 "메모 받고 저장" UX가 더 자연스러워짐.

### 6-5. mac Chrome 위치 미작동 — macOS 시스템 권한
`kCLErrorLocationUnknown` (code=2)는 macOS 시스템 설정 → 개인정보 보호 → 위치 서비스 → 브라우저 체크 누락이 가장 흔한 원인. WiFi 꺼져 있으면 mac은 위치 잡을 수 없음. `useGeolocation` retry로 일시 오류는 완화했지만, 시스템 권한 자체는 코드로 못 고침.

## 7. 운영 배포 체크리스트

`docs/INFRA_SETUP.md` / `docs/CHATBOT_SETUP_GUIDE.md`와 함께 확인:

- [ ] GitHub Actions Secrets 6개 등록: `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`, `ENV_FILE`, `GH_PAT`, `GITHUB_TOKEN`
- [ ] `ENV_FILE`의 `KAKAO_SKILL_SECRET` 44자 정확 (`...IvE=`)
- [ ] `ENV_FILE`의 `KAKAO_REDIRECT_URI=https://{your-domain}/login/callback`
- [ ] `ENV_FILE`의 `WEB_SECURITY_COOKIE_SECURE=true`, `CORS_ALLOWED_ORIGINS=https://{your-domain}`
- [ ] 카카오 i 오픈빌더 스킬 URL을 운영 도메인으로 변경 후 **배포하기**
- [ ] 카카오 채팅 운영시간 24시간 또는 OFF 후 저장
- [ ] (Frontend) 운영 호스팅 환경변수: `GATE_INVITE_CODE`, `GATE_COOKIE_SECRET`, `BACKEND_BASE_URL=https://{your-domain}`, `NEXT_PUBLIC_MAPBOX_TOKEN`, `NEXT_PUBLIC_MAPBOX_STYLE_URL`
- [ ] (Frontend) `BASIC_AUTH_USER`/`PASSWORD` 제거 (혹시 이전 운영 값 남아있다면)
- [ ] EC2 헬스: `https://{your-domain}/actuator/health` → `{"status":"UP"}`
- [ ] 카톡 챗봇 흐름 테스트: 인스타 URL → 메모 안내 → 메모 입력 → 저장 확인
