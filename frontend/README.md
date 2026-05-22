# 우리가갈지도 — Frontend

Next.js 16 (App Router) 기반의 프론트엔드. 카카오 로그인, Mapbox 3D 지도, 핀 관리, 인앱 알림, 공유 카드 UI를 담당합니다.

## 기술 스택

| 항목 | 버전 |
|------|------|
| Framework | Next.js 16.2.6 (App Router) |
| UI Library | React 19.2.4 |
| Styling | Tailwind CSS 4 |
| Language | TypeScript 5 |
| 지도 | Mapbox GL JS 3.8.0 |
| 클러스터링 | Supercluster 8.0.1 |
| 폰트 | Pretendard (self-host), Gowun Batang, Noto Serif KR |
| 테스트 | Vitest + React Testing Library |

## 실행

```bash
npm install
npm run dev      # 개발 서버 → http://localhost:3000
npm run build    # 프로덕션 빌드
npm test         # Vitest 테스트
```

## 환경변수

`.env.local` 파일을 생성하고 아래 변수를 입력합니다.

```
NEXT_PUBLIC_MAPBOX_TOKEN=          # Mapbox 공개 토큰
NEXT_PUBLIC_MAPBOX_STYLE_URL=      # Mapbox 커스텀 스타일 URL
BACKEND_BASE_URL=                  # 백엔드 API 주소 (서버 컴포넌트 전용)
GATE_INVITE_CODE=                  # 서비스 게이트 초대 코드
GATE_COOKIE_SECRET=                # 게이트 쿠키 HMAC 시크릿
```

## 화면 라우트

| 경로 | 화면 | 상태 |
|------|------|------|
| `/` | Splash | ✅ |
| `/login` | 카카오 로그인 | ✅ |
| `/login/callback` | OAuth2 콜백 처리 | ✅ |
| `/onboarding/nickname` | 닉네임 입력 | ✅ |
| `/onboarding/group-start` | 그룹 시작 (새로 만들기 / 초대 코드) | ✅ |
| `/onboarding/notification` | 알림 권한 요청 | ✅ |
| `/groups` | 그룹 선택 | ✅ |
| `/map` | 메인 Mapbox 3D 지도 (핀 · 룰렛 · 알림) | ✅ |
| `/pins` | 핀 목록 | ✅ |
| `/invite/[token]` | 초대 수락 | ✅ |

## 디렉토리 구조

```
frontend/
├── app/                        # Next.js App Router 페이지
│   ├── (auth)/                 # 로그인 · 온보딩 레이아웃
│   ├── map/                    # 메인 지도 페이지
│   └── pins/                   # 핀 목록 페이지
├── components/
│   ├── ui/                     # 범용 UI 컴포넌트
│   │   ├── PinDot              # 핀 마커 (REEL / WISH / MEMORY)
│   │   ├── PinTag              # 태그 배지
│   │   ├── SpeechBubblePopup  # AMOU 스타일 핀 정보창
│   │   ├── NotificationBell    # 알림 벨 + 빨간 점
│   │   └── NotificationPanel  # 알림 목록 패널
│   └── map/                    # 지도 전용 컴포넌트
└── lib/
    ├── design/tokens.ts        # 색상 · 폰트 디자인 토큰
    ├── pin/markers.tsx         # SVG 핀 마커 3종
    └── share/                  # Canvas 공유 카드 생성
```

## 디자인 토큰

```typescript
// lib/design/tokens.ts
colors = {
  bg:         "#FAF8F5",  // 앱 배경
  panel:      "#FFFFFF",  // 카드 / 패널
  mapBg:      "#EAE4D4",  // 지도 배경
  pinReel:    "#C5B4E3",  // REEL 핀 (발견, 연보라)
  pinWish:    "#A8E6CF",  // WISH 핀 (설렘, 민트)
  pinMemory:  "#FFB3C6",  // MEMORY 핀 (추억, 핑크)
  cta:        "#C4622D",  // 주요 CTA (오렌지)
  kakao:      "#FEE500",  // 카카오 노랑
  ink:        "#1A1A2E",  // 기본 텍스트
  inkSoft:    "#8B8B9E",  // 보조 텍스트
  hairline:   "#E8E4DE",  // 구분선
}

fonts = {
  emo:  "Gowun Batang",   // 워드마크, 헤딩 (감성)
  sans: "Pretendard",     // 본문 (self-host)
  mono: "JetBrains Mono", // 코드용
}
```

## 핀 마커

```typescript
// lib/pin/markers.tsx — REEL / WISH / MEMORY 3종 SVG 마커
// 색상: #C5B4E3 / #A8E6CF / #FFB3C6
// 아이콘: 인스타 스타일 / 동그라미 / 하트
```
