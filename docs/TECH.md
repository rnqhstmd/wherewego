# 기술 스택

> 최종 업데이트: 2026-05-20

---

## Frontend

| 항목 | 버전 / 내용 |
|------|------------|
| Framework | Next.js 16.2.6 (App Router) |
| UI | React 19.2.4 |
| 스타일 | Tailwind CSS 4 |
| 언어 | TypeScript 5 |
| 지도 | Mapbox GL JS 3.8.0 |
| 클러스터링 | Supercluster 8.0.1 |
| 폰트 | Pretendard (self-host), Gowun Batang, Noto Serif KR, JetBrains Mono |
| 테스트 | Vitest + React Testing Library |
| 배포 | Vercel |

### 디자인 토큰

```typescript
colors = {
  bg:        "#FAF8F5",  // 앱 배경
  pinPlace:  "#7BB3E8",  // PLACE 핀 (파랑)
  pinMemory: "#F4A8B0",  // MEMORY 핀 (핑크)
  cta:       "#C4622D",  // 주요 CTA (오렌지)
  kakao:     "#FEE500",  // 카카오 노랑
  ink:       "#1A1A2E",  // 기본 텍스트
}

fonts = {
  emo:  "Gowun Batang",  // 워드마크, 헤딩 (감성)
  sans: "Pretendard",    // 본문
  mono: "JetBrains Mono",
}
```

---

## Backend

| 항목 | 버전 / 내용 |
|------|------------|
| Language | Java 21 (Eclipse Temurin) |
| Framework | Spring Boot 3.4.4 |
| Build | Gradle 8.13 (Kotlin DSL, 멀티모듈) |
| DB | PostgreSQL 17 (Neon 관리형) |
| DB 마이그레이션 | Flyway |
| ORM | Spring Data JPA + QueryDSL |
| 캐시 | Caffeine (로컬 인메모리) — Redis 제거 (ADR-0002) |
| 인증 | Spring Security + JJWT 0.12.x |
| 레이트 리밋 | Bucket4j |
| HTML 스크래핑 | JSoup |
| API 문서 | SpringDoc OpenAPI |
| 테스트 | JUnit 5 + Mockito + Testcontainers |
| 컨테이너 | Docker (eclipse-temurin:21-jre-alpine) |
| 배포 | AWS EC2 t3.micro |

### 멀티모듈 구조

```
backend/
├── apps/
│   └── wherewego-api/      # 메인 애플리케이션
├── modules/
│   └── jpa/                # BaseEntity, JPA 공통 설정
└── supports/
    ├── jackson/             # JSON 직렬화 설정
    ├── logging/             # Logback + 파일 롤링 + Slack 알림
    └── monitoring/          # Micrometer Prometheus + Brave 트레이싱
```

---

## 외부 API

| 서비스 | 용도 |
|--------|------|
| Kakao OAuth2 | 소셜 로그인 인증 |
| Kakao i 오픈빌더 | 챗봇 Skill Webhook |
| Kakao Local API | 국내 장소 좌표 검색 |
| Google Places API | 해외 장소 검색 (Kakao 폴백) |
| Google Gemini 2.0 Flash | 인스타 캡션 → 장소명 AI 추출 |
| Mapbox GL JS | 3D 지도 렌더링 |
| Slack Webhook | 운영 알림 (에러, 비동기 처리 결과) |

---

## DevOps / 인프라

| 항목 | 내용 |
|------|------|
| CI/CD | GitHub Actions (`deploy.yml`) |
| 이미지 저장소 | GitHub Container Registry (`ghcr.io`) |
| 백엔드 호스팅 | AWS EC2 t3.micro (서울 리전) |
| 프론트엔드 호스팅 | Vercel |
| DB | Neon (PostgreSQL 17 관리형, 싱가포르 리전) |
| DNS / CDN | Cloudflare |
| Reverse Proxy | Nginx (EC2) — `:80` → `:8080` |
| 시크릿 관리 | AWS SSM Parameter Store → EC2 `/etc/wherewego/.env` |
| 모니터링 | Micrometer → Prometheus + Grafana (로컬) |

### 도메인 구조

| 도메인 | 대상 | 비고 |
|--------|------|------|
| `wherewego.win` | Vercel (프론트엔드) | Cloudflare DNS Only |
| `api.wherewego.win` | EC2 (백엔드) | Cloudflare Proxied (HTTPS 종단) |

---

## 보안 계층

| 클래스 | 역할 |
|--------|------|
| `JwtAuthenticationFilter` | JWT 쿠키 검증 |
| `KakaoSkillSecretFilter` | 챗봇 Webhook 서명 헤더 검증 |
| `RequestIdFilter` | 요청별 고유 ID 부여 (로그 추적) |
| `ChatbotRateLimiter` | 챗봇 분당 10회 제한 (Bucket4j) |
| `AuthCookieFactory` | Set-Cookie (HttpOnly, SameSite=Lax) |
| `ActuatorIpRestrictionFilter` | `/actuator` localhost 전용 제한 |

---

## 의존성 선택 배경

| 결정 | 이유 |
|------|------|
| Redis → Caffeine | t3.micro 메모리 제약 (1GB), 사용자 2명 규모에서 Redis 불필요 (ADR-0002) |
| Supabase → Neon | Supabase 7일 비활성 일시정지 문제 제거, PostgreSQL 17 |
| SSH → SSM | EC2 인바운드 22번 포트 불필요, 키 관리 간소화 |
