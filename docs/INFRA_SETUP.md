# 인프라 구축 작업 요약

> 작업일: 2026-05-18

---

## 완료된 작업

### 1. AWS EC2 인스턴스 구축

| 항목 | 값 |
|------|-----|
| 인스턴스 타입 | t3.micro (프리 티어, 1vCPU / 1GB RAM) |
| OS | Ubuntu Server 22.04 LTS |
| 리전 | ap-northeast-2 (서울) |
| 탄력적 IP | {EC2_PUBLIC_IP} (고정) |
| 스토리지 | 30GB gp3 |

**보안 그룹 (wherewego-sg) 인바운드 규칙:**
| 포트 | 용도 |
|------|------|
| 22 | SSH |
| 80 | HTTP (Nginx) |
| 8080 | Spring Boot API |

**SSH 접속:**
```bash
ssh -i ~/.ssh/wherewego-key.pem ubuntu@{EC2_PUBLIC_IP}
```

**PEM 키 위치:** `~/.ssh/wherewego-key.pem`

---

### 2. VPC / 네트워크 설정

- 기본 VPC: `{VPC_ID}` ({VPC_CIDR})
- 서브넷: `wherewego-subnet-2a` ({SUBNET_CIDR}, ap-northeast-2a)
- 인터넷 게이트웨이: `wherewego-igw` 생성 후 VPC 연결
- 라우팅 테이블: `0.0.0.0/0 → wherewego-igw` 추가

---

### 3. EC2 초기 세팅 (접속 후 실행 필요)

```bash
# Docker 설치
sudo apt-get update && sudo apt-get upgrade -y
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker ubuntu

# Swap 추가 (t3.micro 1GB RAM 보호)
sudo fallocate -l 1G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# 재접속 (docker 그룹 적용)
exit
```

---

### 4. Nginx 설치 & 리버스 프록시 설정

```bash
sudo apt-get install -y nginx

sudo nano /etc/nginx/sites-available/wherewego
```

설정 내용:
```nginx
server {
    listen 80;
    server_name wherewego.win www.wherewego.win;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/wherewego /etc/nginx/sites-enabled/
sudo rm /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl restart nginx
sudo systemctl enable nginx
```

---

### 5. 도메인 & HTTPS 설정

| 항목 | 값 |
|------|-----|
| 도메인 | `wherewego.win` |
| 구매처 | Cloudflare (2026-05-18 구매, 2027-05-18 만료) |
| HTTPS | Cloudflare Proxy (주황 구름) 자동 적용 |

**Cloudflare DNS 레코드:**
| Type | Name | Content | Proxy |
|------|------|---------|-------|
| A | @ | {EC2_PUBLIC_IP} | Proxied ✅ |
| CNAME | www | wherewego.win | Proxied ✅ |

**접속 흐름:**
```
사용자 → https://wherewego.win (Cloudflare HTTPS)
  → EC2:80 (Nginx)
  → localhost:8080 (Spring Boot)
```

---

### 6. GitHub Actions Secrets 등록

| Secret 이름 | 용도 |
|------------|------|
| `EC2_HOST` | {EC2_PUBLIC_IP} |
| `EC2_USER` | ubuntu |
| `EC2_SSH_KEY` | `~/.ssh/wherewego-key.pem` 전체 내용 |
| `ENV_FILE` | 운영 환경변수 전체 (prod값으로 수정) |
| `GH_PAT` | ghcr.io pull용 (read:packages 권한) |

---

### 7. deploy.yml 개선

- EC2에 `.env` 파일 영구 저장 제거 (`mktemp` 임시 파일 → 배포 후 즉시 삭제)
- JVM 메모리 제한: `-Xmx512m -Xms256m`
- Docker 컨테이너 메모리 제한: `--memory 700m`

```
GitHub Secrets (ENV_FILE)
  → GitHub Actions
  → EC2 임시 파일 (mktemp, chmod 600)
  → docker run --env-file
  → 임시 파일 즉시 삭제 (rm -f)
```

---

### 8. 외부 API 키 발급

| API | 상태 |
|-----|------|
| Gemini 2.0 Flash | ✅ 발급 완료 (wherewego Gemini API Key) |
| Google Places | ✅ 발급 완료 |
| Kakao OAuth2 | ✅ 기존 사용 중 |
| Kakao Local | ✅ 기존 사용 중 |

---

### 9. 카카오 i 오픈빌더 스킬 URL 업데이트

```
스킬명: 인스타링크수집
URL: http://{EC2_PUBLIC_IP}:8080/api/v1/chatbot/webhook
헤더: X-Kakao-Skill-Secret (KAKAO_SKILL_SECRET 값)
```

> 도메인 배포 완료 후 → `https://wherewego.win/api/v1/chatbot/webhook` 으로 변경 필요

---

## 남은 작업

```
[ ] EC2 Docker 설치 완료 (재접속 후 docker --version 확인)
[ ] ENV_FILE Secret 운영값으로 최종 확인
     - SPRING_PROFILES_ACTIVE=prod
     - KAKAO_REDIRECT_URI=https://wherewego.win/auth/kakao/callback
     - CORS_ALLOWED_ORIGINS=https://wherewego.win
     - GOOGLE_PLACES_API_KEY=실제 키
     - GEMINI_API_KEY=실제 키
[ ] feat/login-onboarding-design → main 머지
[ ] main 머지 후 GitHub Actions 첫 자동 배포
[ ] https://wherewego.win/swagger-ui.html 접속 확인
[ ] 카카오 개발자 콘솔 Redirect URI 추가
     → https://wherewego.win/auth/kakao/callback
[ ] 카카오 오픈빌더 스킬 URL 도메인으로 변경
     → https://wherewego.win/api/v1/chatbot/webhook
[ ] Mapbox 토큰 URL 제한에 wherewego.win 등록
[ ] AWS Budgets 과금 알림 설정 (0원 초과 시 이메일)
```

---

## 운영 주소

| 환경 | URL |
|------|-----|
| 프론트엔드 (예정) | https://wherewego.win |
| API | https://wherewego.win/api/v1 |
| Swagger | https://wherewego.win/swagger-ui.html |
| EC2 직접 | http://{EC2_PUBLIC_IP}:8080 |

---

## 로컬 개발 환경

```bash
# 프론트엔드
cd frontend && npm run dev          # http://localhost:3000

# 백엔드 (backend/.env 필요)
cd backend && ./gradlew :apps:wherewego-api:bootRun

# 로컬 DB만 Docker로
cd backend/docker && docker-compose -f infra-compose.yml up
```
