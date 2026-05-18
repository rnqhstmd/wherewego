# 우리가갈지도 (MayGo) — 프로젝트 전체 정리

> 슬로건: "인스타 릴스 한 줄이면 완성되는 우리만의 글로벌 데이트 지도"  
> 최종 업데이트: 2026-05-18

---

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [기술 스택](#2-기술-스택)
3. [데이터 모델 & API](#3-데이터-모델--api)
4. [화면 구성 & 핵심 기능](#4-화면-구성--핵심-기능)
5. [인스타그램 릴스 → 화면까지 전체 흐름](#5-인스타그램-릴스--화면까지-전체-흐름)
6. [지도 기능 상세](#6-지도-기능-상세)
7. [인증 & 온보딩 흐름](#7-인증--온보딩-흐름)
8. [배포 & 인프라](#8-배포--인프라)
9. [개발 현황 & Phase 진행도](#9-개발-현황--phase-진행도)

---

## 1. 프로젝트 개요

**MayGo(우리가갈지도)** 는 커플 또는 소그룹이 인스타그램 릴스를 카카오톡 챗봇에 공유하면, AI가 장소를 자동 추출해 Mapbox 3D 지도 위에 핀으로 등록해주는 서비스다.

### 핵심 가치
| 기능 | 설명 |
|------|------|
| **1클릭 핀 등록** | 릴스 링크 공유 → Gemini AI 장소 추출 → 자동 핀 꽂기 |
| **글로벌 3D 지도** | Mapbox 3D 지구본 위에 PLACE / MEMORY 핀 분류 |
| **위치 기반 룰렛** | "오늘 어디 갈까?" → 반경 내 미방문 핀 중 랜덤 추천 |
| **그룹 아카이빙** | 커플(2인) / 그룹(N인) 단위로 핀·메모 격리 공유 |

### 프로젝트 구조
```
wherewego/
├── frontend/          # Next.js 16 (App Router) + React 19
├── backend/           # Spring Boot 3 (Kotlin + Java, Gradle 멀티모듈)
├── context/           # 도메인 컨텍스트 문서
├── docs/              # ADR, 프로젝트 문서
├── requirements/      # MVP 기획서
└── .github/workflows/ # CI/CD (GitHub Actions)
```

---

## 2. 기술 스택

### Frontend
| 항목 | 버전 / 내용 |
|------|------------|
| Framework | Next.js 16.2.6 (App Router) |
| UI | React 19.2.4 + TailwindCSS 4 |
| 지도 | Mapbox GL JS 3.8.0 |
| 클러스터링 | Supercluster 8.0.1 |
| 폰트 | Pretendard (self-host), Gowun Batang, Noto Serif KR, JetBrains Mono |
| 테스트 | Vitest + React Testing Library |

### Backend
| 항목 | 버전 / 내용 |
|------|------------|
| Language | Java 21 (Eclipse Temurin) |
| Framework | Spring Boot 3.4.4 |
| Build | Gradle 8.13 (Kotlin DSL, 멀티모듈) |
| DB | PostgreSQL 17 (Supabase) |
| 캐시 | Caffeine (로컬 메모리, Redis 제거 — ADR-0002) |
| Migration | Flyway |
| 테스트 | JUnit 5 + Testcontainers |

### 외부 API
| 서비스 | 용도 |
|--------|------|
| Kakao OAuth2 | 로그인 인증 |
| Kakao i 오픈빌더 | 챗봇 Skill Webhook |
| Kakao Local API | 국내 장소 검색 |
| Google Places API | 해외 장소 검색 (폴백) |
| Google Gemini 2.0 Flash | 인스타 캡션 → 장소명 AI 추출 |
| Mapbox GL JS | 3D 지도 렌더링 |

### DevOps
| 항목 | 내용 |
|------|------|
| 컨테이너 | Docker (Alpine JRE 21) |
| 이미지 저장소 | GitHub Container Registry (ghcr.io) |
| CI/CD | GitHub Actions |
| 호스팅 | AWS EC2 t3.micro |
| 모니터링 | Micrometer Prometheus + Grafana |

---

## 3. 데이터 모델 & API

### 핵심 DB 스키마 (PostgreSQL 17)

#### users
```sql
CREATE TABLE users (
    id               BIGSERIAL PRIMARY KEY,
    kakao_user_id    BIGINT UNIQUE,
    nickname         VARCHAR(100),
    profile_image_url TEXT,
    refresh_token    TEXT,          -- JWT Refresh Token SHA-256 해시
    created_at, updated_at, deleted_at TIMESTAMPTZ
);
```

#### groups / group_members
```sql
CREATE TABLE groups (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(100),
    ...
);

CREATE TABLE group_members (
    id        BIGSERIAL PRIMARY KEY,
    group_id  BIGINT REFERENCES groups,
    user_id   BIGINT REFERENCES users,
    joined_at TIMESTAMPTZ,
    left_at   TIMESTAMPTZ,   -- NULL = 활성, NOT NULL = 탈퇴
    UNIQUE (group_id, user_id),
    UNIQUE INDEX (user_id) WHERE left_at IS NULL  -- 한 유저 1활성 그룹
);
```

#### pins (핵심)
```sql
CREATE TABLE pins (
    id             BIGSERIAL PRIMARY KEY,
    group_id       BIGINT REFERENCES groups,
    place_name     VARCHAR(255),
    address        TEXT,
    latitude       NUMERIC(10,8),
    longitude      NUMERIC(11,8),
    tag            VARCHAR(50),        -- PLACE | MEMORY
    memo           TEXT,
    instagram_url  TEXT,
    created_by     BIGINT REFERENCES users,
    created_at, updated_at, deleted_at TIMESTAMPTZ,
    UNIQUE (group_id, instagram_url)   -- 그룹 내 릴스 중복 방지
);
```

#### 챗봇 연동 테이블
```sql
-- 6자리 숫자 연동 코드 (10분 TTL)
CREATE TABLE bot_link_codes (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT REFERENCES users,
    code       CHAR(6),
    expires_at TIMESTAMPTZ,
    used_at    TIMESTAMPTZ
);

-- botUserKey ↔ user_id 영구 매핑
CREATE TABLE bot_user_mappings (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT UNIQUE REFERENCES users,
    bot_user_key VARCHAR(100) UNIQUE,
    linked_at    TIMESTAMPTZ
);
```

### REST API 엔드포인트

#### Auth (`/api/v1/auth`)
```
GET  /kakao/login-url           → { loginUrl }
POST /kakao/callback            ← { code }  → Set-Cookie: access_token, refresh_token
POST /token/refresh             → (쿠키 자동 갱신)
POST /logout
```

#### Group (`/api/v1/groups`)
```
POST /                          ← { name }           → { groupId, name }
POST /{groupId}/invite-links                         → { token, expiresAt }
POST /invite-links/{token}/accept                    → { groupId, groupName, memberCount }
GET  /me                                             → { groupId, groupName, memberCount } | null
DELETE /{groupId}/members/me
```

#### Pin (`/api/v1/groups/{groupId}/pins`)
```
POST /                ← { placeName, address, latitude, longitude, tag, memo, instagramUrl }
GET  /                ← ?tag=PLACE&page=0&size=20   → { pins, totalCount, hasNext }
PATCH /{pinId}        ← { memo?, tag?, placeName?, ... }
DELETE /{pinId}       → 204 No Content
```

#### Chatbot (`/api/v1/chatbot`)
```
POST /webhook         ← KakaoSkillRequest { userKey, utterance }
                      → KakaoSkillResponse { quickReplies, ... }
```

#### 응답 포맷
```json
{
  "data": { ... },
  "code": "SUCCESS",
  "message": "사용자 친화 메시지"
}
```

---

## 4. 화면 구성 & 핵심 기능

### 구현된 화면 (✅) vs 개발 중 (⬜)

| 라우트 | 화면명 | 상태 |
|-------|-------|------|
| `/` | Splash | ⬜ 구현 필요 |
| `/login` | Screen 0 — 카카오 로그인 | ⬜ 구현 필요 |
| `/login/callback` | Screen 0a — 로딩 | ⬜ 구현 필요 |
| `/onboarding/nickname` | Screen 0b — 닉네임 입력 | ⬜ 구현 필요 |
| `/onboarding/group-start` | Screen 0c — 그룹 시작 | ⬜ 구현 필요 |
| `/onboarding/notification` | 알림 권한 요청 | ⬜ 구현 필요 |
| `/groups` | Screen 1 — 그룹 선택 | ⬜ 구현 필요 |
| `/map` | 메인 Mapbox 지도 | ✅ 완료 |
| `/pins` | 핀 목록 | ✅ 완료 |

### 화면 흐름 (전체)
```
최초 진입
  └→ [/] Splash (GlobeBg, 1.5초, 로딩점 애니메이션)
       ├─ JWT 없음 → [/login] 카카오 로그인 버튼
       └─ JWT 유효 + 그룹 있음 → [/map] 바로 이동

[/login] 카카오 로그인
  └→ 카카오 OAuth 인증 후 콜백
       └→ [/login/callback] 로딩 스피너 (자동 처리)
            ├─ 신규 유저 (nickname 없음)
            │   └→ [/onboarding/nickname] 닉네임 입력
            │        └→ [/onboarding/group-start] 새 그룹 or 초대코드
            │             └→ [/groups] 그룹 선택
            │                  └─ 첫 선택 시만 → [/onboarding/notification] 알림권한
            └─ 기존 유저 (그룹 없음) → [/onboarding/group-start]

[/map] 메인 지도 (최종 목적지)
  ├─ 핀 목록 열람 (PLACE / MEMORY 필터)
  ├─ 핀 탭 → 정보창 팝업 (장소명, 메모, 릴스 링크)
  ├─ 장소 검색 (사이드 패널)
  └─ 룰렛 → 추천 결과 카드
```

### 핵심 UI 컴포넌트
```
components/ui/
├─ BtnPrimary, BtnSub           기본 버튼
├─ BtnKakao                     카카오 노랑 버튼 (⬜ 신규)
├─ PinDot                       핀 마커 (PLACE: 파랑, MEMORY: 핑크)
├─ PinTag                       핀 카테고리 태그 (파스텔)
├─ SpeechBubblePopup            AMOU 스타일 핀 정보창
├─ GlobeBg                      Splash 배경 (3D 지구)
├─ SplashScreen                 로딩 스크린 기본형
├─ PermissionDialog             위치/알림 권한 다이얼로그
├─ Sheet                        하단 슬라이드 패널
├─ SidePanel                    우측 사이드 패널
├─ Input                        텍스트 입력 (underline 스타일)
└─ Cluster                      Supercluster 클러스터 마커
```

### 디자인 토큰
```typescript
colors = {
  bg:         "#FAF8F5",   // 앱 배경
  panel:      "#FFFFFF",   // 카드/패널
  mapBg:      "#EAE4D4",   // 지도 배경
  pinPlace:   "#7BB3E8",   // PLACE 핀 (파랑)
  pinMemory:  "#F4A8B0",   // MEMORY 핀 (핑크)
  cta:        "#C4622D",   // 주요 CTA (오렌지)
  kakao:      "#FEE500",   // 카카오 노랑
  ink:        "#1A1A2E",   // 기본 텍스트
  inkSoft:    "#8B8B9E",   // 보조 텍스트
  hairline:   "#E8E4DE",   // 구분선
}

fonts = {
  emo:  "Gowun Batang",    // 워드마크, 헤딩 (감성)
  sans: "Pretendard",      // 본문 (self-host)
  mono: "JetBrains Mono",  // 코드용
}
```

---

## 5. 인스타그램 릴스 → 화면까지 전체 흐름

### 전체 파이프라인

```
사용자
  → 카카오톡에서 인스타 릴스 링크 + (선택) 메모 텍스트를 챗봇에 공유
```

#### Step 1. Webhook 수신 & 사용자 인증
```
POST /api/v1/chatbot/webhook
  ├─ KakaoSkillSecretFilter: X-Kakao-Skill-Secret 헤더 검증
  ├─ ChatbotRateLimiter: 분당 10회 제한 (Bucket4j)
  └─ bot_user_key → user_id 조회 (bot_user_mappings)
       └─ 미등록 시 → "6자리 코드를 입력해주세요" 응답 (연동 플로우 시작)
```

#### Step 2. 인스타 콘텐츠 추출
```
InstagramContentService.extract(instagramUrl)
  ├─ feature flag: place.instagram.scraping-enabled 확인
  ├─ JSoup로 HTML 스크래핑 → og:description 메타태그 파싱
  ├─ CaptionCleaner: 해시태그, @멘션 등 정제
  └─ Gemini 2.0 Flash API 호출
       → 프롬프트: "이 캡션에서 장소명만 추출해줘"
       → 응답: { placeName: "후쿠오카 솔라리아 플라자" }
       (실패 시 → "어느 곳인가요? 직접 알려주세요" 챗봇 응답)
```

#### Step 3. 장소 좌표 검색
```
PlaceSearchService.searchByKeyword(placeName)
  ├─ Kakao Local API (국내 우선)
  │   ├─ 결과 1건 → 자동 선택 (핀 등록)
  │   ├─ 결과 2~5건 → 카톡 리스트 카드로 사용자 선택지 제공
  │   └─ 결과 0건 → Google Places 폴백
  │
  └─ Google Places API (해외/비동기 폴백)
       → Slack 알림 + 재처리 큐
       → 성공 시 핀 등록 후 챗봇 재알림
```

#### Step 4. 핀 등록
```
PinService.addPin(userId, groupId, PlaceInfo)
  ├─ 중복 검사: (group_id, instagram_url) UNIQUE 제약
  ├─ PIN 생성: tag=PLACE (기본값), memo 비어있음
  └─ DB 저장 → pins 테이블
```

#### Step 5. 메모 자동 연결 (2초 룰)
```
LinkageService
  ├─ 링크 수신 후 2초 내에 텍스트 메시지가 오면
  └─ 해당 핀의 memo 컬럼에 자동 저장
       (수동 편집이 있으면 수동 값이 항상 우선)
```

#### Step 6. 챗봇 응답
```
성공 → "핀 꽂기 완료! 지도를 확인해보세요 📍"
실패 → "처리가 지연되었어요. 다시 시도해주세요"
```

### 화면에서 어떻게 보이는가

```
[/map] 메인 지도 로드
  ↓
GET /api/v1/groups/{groupId}/pins 호출
  ↓
Supercluster로 핀 클러스터링 계산
  ↓
Mapbox GL 위에 마커 렌더링
  ├─ PLACE 핀: 파란 원형 (PinDot, #7BB3E8)
  └─ MEMORY 핀: 핑크 하트 (PinDot, #F4A8B0)

핀 클릭 시
  └─ SpeechBubblePopup 표시
       ├─ 장소명 (Gowun Batang)
       ├─ 주소
       ├─ 메모 (있으면 표시)
       ├─ 인스타 릴스 링크 아이콘 (외부 열기)
       └─ 태그 변경 버튼 (PLACE ↔ MEMORY)
```

### 타임라인 제약 (Kakao 5초 타임아웃)
| 단계 | 제한시간 |
|------|---------|
| 전체 Webhook 응답 | ≤ 5초 |
| 인스타 스크래핑 | ≤ 4초 |
| Gemini 호출 | ≤ 3초 |
| Kakao Local API | ≤ 1.5초 |
| Google Places | 비동기 (제한 없음) |

---

## 6. 지도 기능 상세

### Mapbox 3D 지도
- 줌 아웃 시 자동 3D 지구본 모드 전환
- 커스텀 스타일 URL (디자인 토큰 색상 적용)
- 무료 한도: 50,000 map loads/월

### 핀 렌더링 로직
```
줌 레벨 기준 분기:
  zoom < 5  → Cluster 컴포넌트 (여러 핀을 하나로 묶음)
  zoom ≥ 5  → 개별 PinDot 마커

클러스터 클릭 → 해당 영역 줌인
핀 클릭 → SpeechBubblePopup 표시
```

### 위치 기반 룰렛
```
사용자: "오늘 어디 갈까?" 클릭
  ↓
navigator.geolocation.getCurrentPosition()
  ↓
거리 범위 선택 (1km / 5km / 10km)
  ↓
GET /api/v1/groups/{groupId}/recommendations
    ?latitude=&longitude=&radiusKm=&tag=PLACE
  ↓
서버: Haversine 거리 계산 (PostGIS 미사용, Java 레벨)
     → 범위 내 미방문 핀 중 RANDOM(1)
  ↓
RouletteResultContent 카드 표시
  └─ 장소명, 거리, 메모, "지도로 이동" 버튼
```

---

## 7. 인증 & 온보딩 흐름

### JWT 전략
| 토큰 | 유효기간 | 저장 |
|------|---------|------|
| Access Token | 1시간 | HttpOnly Cookie |
| Refresh Token | 14일 | HttpOnly Cookie + DB(해시) |

### 라우팅 게이트
```
JWT 없음 or 만료 → /login

JWT 유효 + 그룹 있음 → /map (리다이렉트)
JWT 유효 + 그룹 없음 → /onboarding/group-start

/map, /pins 접근 시 JWT 없음 → /login?returnUrl=/map
```

### 카카오톡 챗봇 연동 코드 (6자리)
```
웹 앱 /settings에서 "연동 코드 발급" 클릭
  └─ bot_link_codes에 6자리 코드 생성 (TTL 10분)
카카오톡에서 챗봇에 코드 입력
  └─ bot_user_key + user_id 영구 매핑 (bot_user_mappings)
```

### 온보딩 화면별 세부 스펙

#### Splash (/)
- 배경: `GlobeBg` (opacity 0.4)
- 워드마크: "우리가 갈 지도" (Gowun Batang 48px)
- 로딩점 3개 (pulse animation)
- 최소 1.5초 노출 (JWT 검증이 빨라도 대기)

#### Screen 0 — 로그인 (/login)
- maxWidth 460px 카드
- 워드마크 + tagline + 핀 장식 3개
- `BtnKakao` 버튼 (#FEE500, 카카오 심볼)
- 이용약관 안내 (12px, #8B8B9E)

#### Screen 0a — 로딩 (/login/callback)
- 48px 스피너 (1초 회전, 자동 처리)
- "잠시만요 / 카카오로 로그인하고 있어요"

#### Screen 0b — 닉네임 (/onboarding/nickname)
- 헤딩: "반가워요\n이름을 알려주세요"
- underline 스타일 Input (Gowun Batang 24px)
- 유효성: 한글/영문/숫자, 2~12자
- "다음" 버튼 (하단 고정)

#### Screen 0c — 그룹 시작 (/onboarding/group-start)
- 헤딩: "어떻게 시작할까요"
- 카드 2개: "새 그룹 만들기" / "초대 코드로 합류"

#### Screen 1 — 그룹 선택 (/groups)
- 상단: 로고 + 아바타(🙂) + 닉네임
- 그룹 카드 목록 ("👥 N명 참여 중")
- "새 그룹 만들기" 점선 카드

#### 알림 권한 (/onboarding/notification)
- `IconBell` 아이콘 (cta 색)
- "알림 받아볼래요?"
- "알림 허용" / "다음에" 버튼
- localStorage `maygo:notif-asked` 플래그로 1회만 노출

---

## 8. 배포 & 인프라

### 전체 구조
```
GitHub (main 브랜치 머지)
  ↓
GitHub Actions (deploy.yml)
  ├─ 변경 감지: backend/ 디렉토리
  ├─ ./gradlew bootJar (-x test)
  ├─ Docker 이미지 빌드
  └─ ghcr.io 푸시 (tags: latest, {git-sha})
  ↓
EC2 배포 (SSH)
  ├─ /etc/wherewego/.env 갱신
  ├─ docker pull 최신 이미지
  ├─ 기존 컨테이너 stop/rm
  ├─ docker run (신규 컨테이너)
  └─ docker image prune (미사용 정리)
```

### Dockerfile
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY apps/wherewego-api/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 로컬 개발 환경
```bash
# DB만 Docker로 실행
cd backend/docker
docker-compose -f infra-compose.yml up
# → PostgreSQL 17 on localhost:5432

# 백엔드 실행
cd backend
./gradlew :apps:wherewego-api:bootRun

# 프론트엔드 실행
cd frontend
npm run dev
# → http://localhost:3000
```

### 필요한 GitHub Secrets
| Secret | 내용 |
|--------|------|
| `ENV_FILE` | backend 전체 .env 파일 내용 |
| `GH_PAT` | GitHub Personal Access Token |
| `EC2_HOST` | EC2 퍼블릭 IP 또는 도메인 |
| `EC2_USER` | SSH 사용자명 (ubuntu 등) |
| `EC2_SSH_KEY` | EC2 접속 PEM 키 |

### 필요한 환경변수 (backend/.env)
```
# DB
DATASOURCE_URL=jdbc:postgresql://...supabase.co:5432/postgres
DATASOURCE_USERNAME=postgres
DATASOURCE_PASSWORD=...

# JWT
JWT_SECRET=...

# Kakao
KAKAO_CLIENT_ID=...
KAKAO_CLIENT_SECRET=...
KAKAO_REDIRECT_URI=https://{domain}/api/v1/auth/kakao/callback
KAKAO_SKILL_SECRET=...

# Google
GOOGLE_PLACES_API_KEY=...
GOOGLE_GEMINI_API_KEY=...
```

### 모니터링
```bash
# 로컬 모니터링 스택
cd backend/docker
docker-compose -f monitoring-compose.yml up
# → Prometheus: localhost:9090
# → Grafana:    localhost:3001
```

---

## 9. 개발 현황 & Phase 진행도

### Phase 히스토리
| Phase | 범위 | 상태 | PR |
|-------|------|------|-----|
| Phase 0 | DB 스키마 + Spring Boot 기반 | ✅ | #1 |
| Phase 1 | 카카오 OAuth2 + JWT | ✅ | #3 |
| Phase 2 | 챗봇 Webhook + 인스타 파이프라인 | ✅ | #5 |
| Phase 2.5 | Gemini 2.0 Flash 전환 | ✅ | #15 |
| Phase 3 | 그룹 생성/초대/탈퇴 | ✅ | #7 |
| Phase 4 | 웹 UI 핀 CRUD | ✅ | #9, #13 |
| Phase 5 | Google Places 비동기 폴백 | ✅ | #11 |
| Phase 6 | Mapbox 3D 지도 + 룰렛 + 디자인 시스템 | ✅ | #13 |
| Phase 2.6-A | 웹 메모 편집 + 룰렛 토글 | ✅ | #17 |
| Phase 2.6-B | 보안·운영 안정화 (Bucket4j 레이트 리밋) | ✅ | #18 |
| Phase 2.7 | 신뢰 인프라 (테스트 자동화) | ✅ | #20 |
| Phase 2.8 | 핀 도메인 완성 (UX 잔여) | ✅ | #21 |
| Phase 2.9 | 규모 대응 (페이지네이션 준비) | ✅ | #22 |
| Phase 2.10 | 잔여 후속 통합 (MVP 운영) | ✅ | #24 |
| **현재** | **로그인·온보딩·그룹선택 화면 구현** | **⬜ 진행 중** | — |

### 현재 브랜치 (`feat/login-onboarding-design`)
**구현 대상:**
- 10개 미구현 화면 (Splash, 로그인, 온보딩 3단계, 그룹선택, 알림권한)
- 2개 신규 컴포넌트 (`BtnKakao`, `IconBell`)
- 라우팅 게이트 로직 (`middleware.ts`)

**미구현 상태의 현상:**
- `http://localhost:3000` → Next.js 기본 스캐폴드 화면 (To get started, edit the page.tsx)
- 실제 기능: `http://localhost:3000/map`, `http://localhost:3000/pins`에서 확인 가능

### 백엔드 멀티모듈 구조
```
backend/
├─ apps/wherewego-api/      # 메인 애플리케이션 (Spring Boot)
│   └─ domain/
│       ├─ auth/            # 카카오 OAuth2 + JWT
│       ├─ group/           # 그룹 CRUD + 초대
│       ├─ pin/             # 핀 CRUD + 중복방지
│       ├─ chatbot/         # Kakao Skill Webhook
│       ├─ place/           # 인스타 스크래핑 + AI 추출
│       └─ bot/             # botUserKey 매핑
├─ modules/
│   └─ jpa/                 # 공용 BaseEntity, JPA 설정
└─ supports/
    ├─ jackson/             # JSON 직렬화
    ├─ logging/             # Logback
    └─ monitoring/          # Prometheus 메트릭
```

### 보안 계층
| 클래스 | 역할 |
|--------|------|
| `JwtAuthenticationFilter` | JWT 쿠키 검증 |
| `KakaoSkillSecretFilter` | 챗봇 Webhook 헤더 검증 |
| `ChatbotRateLimiter` | 분당 10회 제한 (Bucket4j) |
| `AuthCookieFactory` | Set-Cookie (SameSite=Lax) |
| `ActuatorIpRestrictionFilter` | /actuator localhost 제한 |
