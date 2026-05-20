# 우리가갈지도

> 우리의 장소를 지도 위에 아카이빙해요

커플 또는 소그룹이 인스타그램 릴스를 카카오톡 챗봇에 공유하면, AI가 장소를 자동 추출해 Mapbox 3D 지도 위에 핀으로 등록해주는 서비스입니다.

---

## 주요 기능

| 기능 | 설명 |
|------|------|
| **인스타 → 핀 자동 등록** | 릴스 링크를 카카오톡 챗봇에 공유하면 Gemini AI가 장소를 추출해 지도에 자동 핀 등록 |
| **3D 글로벌 지도** | Mapbox 3D 지구본 위에 PLACE / MEMORY 핀을 분류해 아카이빙 |
| **위치 기반 룰렛** | "오늘 어디 갈까?" — 현재 위치 반경 내 미방문 핀 중 랜덤 추천 |
| **그룹 아카이빙** | 커플(2인) / 소그룹(N인) 단위로 핀과 메모를 격리 공유 |
| **메모 입력 대기** | 릴스 링크 전송 후 봇이 메모 입력을 요청, 1분 내 미응답 시 메모 없이 자동 저장 |
| **카카오 소셜 로그인** | 카카오 OAuth2 기반 간편 로그인 |

---

## 서비스 흐름

```
카카오톡 챗봇에 인스타 릴스 링크 공유
  ↓
Gemini AI가 캡션에서 장소명 자동 추출
  ↓
Kakao Local API / Google Places로 좌표 검색
  ↓
Mapbox 지도 위에 핀 등록
  ↓
웹에서 핀 확인 · 룰렛으로 방문지 추천
```

---

## 프로젝트 구조

```
wherewego/
├── frontend/          # Next.js 16 (App Router) + React 19 + Mapbox
├── backend/           # Spring Boot 3 (Java 21, Gradle 멀티모듈)
│   ├── apps/          # 메인 API 애플리케이션
│   ├── modules/       # 공용 모듈 (JPA)
│   └── supports/      # 횡단 관심사 (logging, monitoring, jackson)
├── docs/              # 기술 문서, ADR, 인프라 가이드
└── .github/workflows/ # CI/CD (GitHub Actions)
```

---

## 빠른 시작

```bash
# DB 실행 (Docker 필요)
cd backend/docker
docker-compose -f infra-compose.yml up -d

# 백엔드 실행
cd backend
./gradlew :apps:wherewego-api:bootRun

# 프론트엔드 실행
cd frontend
npm install && npm run dev
# → http://localhost:3000
```

환경 변수 설정은 `backend/apps/wherewego-api/src/main/resources/application.yml` 참고.

---

## 문서

- [기술 스택](docs/TECH.md)
- [시스템 아키텍처](docs/ARCHITECTURE.md)
- [ERD](docs/ERD.md)
- [인프라 설정 가이드](docs/INFRA_SETUP.md)
- [챗봇 설정 가이드](docs/CHATBOT_SETUP_GUIDE.md)
- [프로젝트 전체 정리](docs/PROJECT_OVERVIEW.md)

---

## 기술 스택 요약

| 영역 | 기술 |
|------|------|
| Frontend | Next.js 16, React 19, TypeScript, Tailwind CSS 4, Mapbox GL JS |
| Backend | Spring Boot 3.4, Java 21, PostgreSQL 17, Flyway, Caffeine |
| AI / 외부 API | Google Gemini 2.0 Flash, Kakao OAuth2 · 챗봇 · Local API, Google Places |
| 인프라 | AWS EC2, Vercel, Neon (PostgreSQL), Cloudflare, Docker, GitHub Actions |
