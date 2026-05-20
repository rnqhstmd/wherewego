# 2026-05-19 작업 요약 — 챗봇 N개 추출 + UX/보안 개선

> 한 세션에서 진행된 큰 작업을 카테고리별로 정리. 운영 배포 전 체크리스트는 마지막 섹션 참고.

## 0. 로컬 환경 셋업

| 항목 | 변경/조치 |
|---|---|
| Java | IntelliJ 번들 `~/.jdks/openjdk-21.0.1` 사용 (시스템 Java 17 우회) |
| Docker | Desktop 실행 후 `backend/docker/infra-compose.yml`로 PostgreSQL 17 기동 |
| Backend port | 8080이 Oracle TNS Listener와 충돌 → `application.yml`에 `server.port: ${SERVER_PORT:8080}` 추가, 로컬은 **8081**로 기동 (`SERVER_PORT=8081`) |
| Frontend env | `frontend/.env.local` 생성 (Mapbox token + style + `BACKEND_BASE_URL=http://localhost:8081`) |

## 1. 인증/토큰 변경

### JWT TTL 연장
- `backend/.env`
  - `JWT_ACCESS_TTL_SECONDS`: 3600 → **86400** (1h → 24h)
  - `JWT_REFRESH_TTL_SECONDS`: 1209600 → **2592000** (14d → 30d)

### 카카오 OAuth redirect URI 인라인 코멘트 정리
- `.env`는 `.properties` 형식으로 로드되어 인라인 `#` 코멘트가 값에 포함되는 문제 → 코멘트를 줄 위로 이동

## 2. 프론트엔드 UI/UX 개편

### Mapbox 지도
| 항목 | 변경 |
|---|---|
| Fallback 스타일 | `mapbox/light-v11` → `mapbox/standard` + theme=faded + lightPreset=day + show3dObjects + POI 라벨 OFF |
| 운영 스타일 URL | `NEXT_PUBLIC_MAPBOX_STYLE_URL=mapbox://styles/bonseung/cmpc2kbec001p01pt2q1s2ut5` |
| 진입 시점 | globe(zoom 2~3) → `flyTo` → pitch:0, zoom:15 (수직 top-down) |
| Layer 미존재 처리 | `setPaintProperty` 호출 전 `getLayer` 확인 (background/water/landuse-park 비치명적 오류 제거) |
| 비치명적 에러 | `isFatalMapError` 가드 — `'meshes is not iterable'` 등 일시 오류는 UI 전환 안 함 |

### 본인 위치 마커
- mapbox 기본 `.mapboxgl-user-location-dot` 숨김 + 자체 div 마커 (`.user-location-marker`) 사용
- 색: `#555555` (진한 회색), 크기: 10×10, 펄스 애니메이션 (`@keyframes user-location-pulse`)
- 정확도 원: `showAccuracyCircle: false` + CSS `display:none !important` (이중 안전망)
- 초기 mount 시 `navigator.geolocation.getCurrentPosition` 콜백에서 자체 마커도 `addTo`

### 핀 마커
- PLACE: 18×18 원형, 흰 테두리 제거, 단색 `#7BB3E8` + shadow
- MEMORY: 22×22 표준 material 하트 SVG (viewBox 24×24 정사각형, 종횡비 보정)

### Crosshair (핀 추가 picker)
- 원-점 → **십자선 + 중앙 점** (오렌지 CTA 색)

### PinPopup 전면 재설계
- mode: `view` / `menu` / `edit` 3단계
- **점3개 메뉴**: 클릭 시 popup 우상단에 흰색 dropdown popover (`수정` / `삭제` / 인스타 핑크색)
- **수정 모드**: body 숨김 (`collapseBody=true`) + 탭 (`장소` / `태그` / `메모`) + 폼 + 좌표수정/닫기
- 작성자 표시: `2026.05.19 *written by* **닉네임**` (italic prefix + margin)
- 인스타 URL 있는 핀: 주소 아래 `📷 릴스 보기 ↗` 링크 (`SpeechBubblePopup.instagramUrl` prop)
- Strict Mode dev mountedRef bug 수정 (setup에서 true reset)

### Search Panel
- debounce 제거 → **Enter 또는 돋보기 클릭**으로 명시적 검색
- `IconSearch` 컴포넌트 사용, input 우측 absolute 배치

### Roulette
- 반경 `[1, 5, 10]` → **`[10]` 단일** (10km 고정)
- 항상 PLACE+MEMORY 함께 검색 (toggle 제거)
- exhausted/geo-error UI에 "다시 시도" 버튼 추가
- reRoll: 후보 2+ 시 직전 핀 제외 (같은 곳만 반복 방지)
- `setHasCluster` functional setState로 안정화 (Maximum update depth 방지)

### 게이트(Basic Auth) — 2인 비공개 보안
- middleware `/src/middleware.ts` 추가 — `maygo-gate` HMAC 쿠키 검증, 미통과 시 `/gate?returnUrl=...` redirect
- `/gate` 페이지 + `/api/auth/gate` POST/DELETE route
- `lib/auth/gate.ts` — HMAC-SHA256 서명 (Edge runtime 호환, timingSafeEqual)
- 환경변수 3종 필수: `BASIC_AUTH_USER`, `BASIC_AUTH_PASSWORD`, `GATE_COOKIE_SECRET`
- 로그아웃 시 카카오 logout + `DELETE /api/auth/gate`로 게이트 쿠키도 함께 만료

### 로그인 흐름 변경
- 로그인 후 redirect: `/map` → **`/groups`** (그룹 목록 먼저)
- `/login/page.tsx`의 `redirectIfAuthed("/map")` → `/groups`
- `/login/callback/page.tsx`의 target도 `/groups`

### `/map` 화면
- 좌측 사이드바 하단에 **닉네임 첫 글자 원형 아바타** (gradient, 호버 시 풀 닉네임) → 클릭 시 `/settings`
- 상단 `< IconBack` → `/groups` 이동

### `/settings` (마이페이지)
- 헤더: "설정" → **"마이페이지"**
- 사용자 카드: 🙂 아바타 이모지 제거 + 닉네임 옆 작은 회색 **"님"**
- 챗봇 연동/친구 초대 섹션에 설명 텍스트 추가
- 전체 폭 `maxWidth: 520px` + 중앙 정렬

### `/onboarding/nickname`
- 한글 IME composition 버그 수정 (`composingRef` 활용, 자모만 들어와도 sanitize 보류)
- maxWidth 460px 중앙 정렬

### 다크 모드 자동 감지 비활성화
- `globals.css`의 `@media (prefers-color-scheme: dark)` 제거 — 시스템이 다크라도 우리 light 톤 유지

## 3. 백엔드 도메인 변경

### PinSummary에 작성자 닉네임
- `ActiveGroupInfo`/`ActiveGroupResponse`에 `memberCount: long` 추가
- `PinSummary`에 `createdByNickname: String` 추가
- `UserRepository.findNicknamesByIds(ids)` 추가 (N+1 회피 batch lookup)
- `PinService`에 `toSummary` / `toSummaries` 헬퍼

### 좌표 수정 (frontend)
- `MapClient.handleConfirmCoordinateEdit`에서 `Number(lat.toFixed(7))`로 round → 백엔드 max-scale=7 통과

### 장소 검색 외부 API 정책
- 카카오 Local API 호출 코드 제거 (PlaceSearchService에서 `KakaoLocalClient` 의존성 제거, Google 직진)
- Google Places 호출에 `languageCode: "ko"` + `regionCode: "KR"` 추가
- Google Places API (New) Console 활성화 필요

### Flyway V005 migration
- `V005__relax_pins_unique_to_include_place_name.sql`
- `uq_pins_group_instagram (group_id, instagram_url)` → `uq_pins_group_instagram_place (group_id, instagram_url, place_name)` — 같은 URL+다른 장소 N개 허용

### Place 도메인 N개 흐름
- `PlaceCandidate` record 신규 (`name`, `confident`)
- `InstagramExtraction`/`ParsedContent`에 `List<PlaceCandidate>` candidates 추가
- `InstagramContentService.extract` → `GeminiPlaceClient.extractPlaceCandidates(caption, userId, 10)` 호출
- `GeminiPlaceClient` 신규 메서드 + JSON 응답 (`{"places":[{"name","confident"}]}`) + few-shot 프롬프트

### Gemini 모델/설정
- `gemini-2.0-flash` (deprecated 404) → `gemini-2.5-flash` (thinking 모델로 응답 잘림) → **`gemini-flash-latest`** + `thinkingConfig.thinkingBudget: 0`
- `maxOutputTokens`: 300 → **1024**
- responseMimeType 제거 (모델 호환성)

### InstagramLinkHandler 흐름 재설계
- `candidates` 기반 새 흐름:
  - `confident=true` → Google 검색 후 첫 결과 자동 등록
  - `confident=false` → 처음 `MAX_CONFIRMATION_CARDS=2`개만 카드, 초과분은 이름 안내문
  - Empty → autoFailed 안내문
- 한 SkillResponse `outputs` 배열에 `simpleText` + `basicCard` 여러 개 합치기
- `PlaceCardBuilder.buildCardOutput` / `simpleTextOutput` helper 추가
- 비동기 callback 흐름: `callbackUrl` 있으면 즉시 `useCallback` 응답 + `asyncCandidatesExecutor` (FixedThreadPool 4)에서 50초 deadline으로 처리 → `KakaoCallbackClient.push`
- 시간 부족 시 처리 못한 candidates도 안내문에 누적

## 4. 운영 배포 전 확인 사항

| 체크 | 확인 방법 |
|---|---|
| ☐ V005 migration 운영 DB에 적용 가능한지 | Supabase SQL Editor에서 `flyway_schema_history` 확인 |
| ☐ Google Places API (New) Console 활성화 | API 키 제한에 "Places API (New)" 포함 |
| ☐ Mapbox 커스텀 스타일 URL 공개 권한 | Mapbox Studio → Share → public |
| ☐ Basic Auth Gate 환경변수 EC2 주입 | `BASIC_AUTH_USER`, `BASIC_AUTH_PASSWORD`, `GATE_COOKIE_SECRET` |
| ☐ JWT TTL 운영에도 86400/2592000 적용 | 운영 `.env` 동기화 |
| ☐ 카카오 i 오픈빌더 URL = 운영 HTTPS | `https://wherewego.win/api/v1/chatbot/webhook` |
| ☐ `KAKAO_REDIRECT_URI` 운영 도메인 | `https://wherewego.win/login/callback` |
| ☐ CORS_ALLOWED_ORIGINS 운영 도메인 | 동일 |
| ☐ WEB_SECURITY_COOKIE_SECURE | 운영은 `true` |

세부 환경변수 업데이트 방법은 `docs/SECRETS_GUIDE.md` 참고.
