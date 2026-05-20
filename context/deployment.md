# 배포 전략

> 최종 동기화: 2026-05-20 (PR #25 SSH→SSM 전환, Neon DB 전환, Vercel 프론트 분리 반영)

## 개요

| 항목 | 내용 |
|------|------|
| 백엔드 인프라 | AWS EC2 t3.micro (단일 인스턴스, Nginx 리버스 프록시) |
| 프론트 인프라 | Vercel (Next.js 16, Hobby 플랜) |
| 컨테이너 | Docker (단일 컨테이너, `ghcr.io/rnqhstmd/wherewego-api:latest`) |
| 이미지 저장소 | GitHub Container Registry (ghcr.io) |
| CI/CD | GitHub Actions — `main` 브랜치 푸시 시 자동 배포 (`.github/workflows/deploy.yml`) |
| EC2 명령 채널 | **AWS SSM Send-Command** (이전 SSH 방식 폐기) |
| 환경변수 | GitHub Secrets → SSM Parameter Store → EC2 `/etc/wherewego/.env` → Docker `--env-file` |
| DB | **Neon PostgreSQL 17** (Singapore 리전, Session Pooler 5432, 영구 무료) |
| 도메인 | Cloudflare DNS (구매처 Cloudflare, 만료 2027-05-18) |
| SSL | Cloudflare Universal SSL (Let's Encrypt). **SSL 모드: Flexible** (Cloudflare ↔ EC2 는 HTTP 80) |
| 도메인 매핑 | `wherewego.win` → Vercel (CNAME, DNS only), `api.wherewego.win` → EC2 (A, Proxied) |

---

## 배포 흐름

### 백엔드 (`main` 푸시 자동)

```
main 브랜치 푸시
  → GitHub Actions (deploy.yml) 트리거
  → Gradle bootJar 빌드 (-x test)
  → Docker 이미지 빌드 → ghcr.io 푸시 (latest + {git-sha})
  → AWS Configure Credentials (AWS_ACCESS_KEY_ID/SECRET/REGION)
  → SSM put-parameter: ENV_FILE → /wherewego/env (SecureString), GH_PAT → /wherewego/gh-pat
  → SSM Send-Command "AWS-RunShellScript" (--instance-ids ${EC2_INSTANCE_ID})
       set -e
       GH_PAT=$(aws ssm get-parameter --name /wherewego/gh-pat --with-decryption ...)
       aws ssm get-parameter --name /wherewego/env --with-decryption ... > /tmp/wherewego_env
       mkdir -p /etc/wherewego && chmod 600 /tmp/wherewego_env && mv /tmp/wherewego_env /etc/wherewego/.env
       echo "$GH_PAT" | docker login ghcr.io -u rnqhstmd --password-stdin
       docker pull ghcr.io/rnqhstmd/wherewego-api:latest
       docker stop wherewego-api || true
       docker rm wherewego-api || true
       sudo mkdir -p /var/log/wherewego && sudo chown 1000:1000 /var/log/wherewego
       docker run -d --name wherewego-api --env-file /etc/wherewego/.env \
           -e JAVA_TOOL_OPTIONS="-Xmx512m -Xms256m" \
           -p 8080:8080 --memory 700m --restart unless-stopped \
           --log-driver=json-file --log-opt max-size=50m --log-opt max-file=3 \
           -v /var/log/wherewego:/var/log/wherewego \
           ghcr.io/rnqhstmd/wherewego-api:latest
       docker image prune -f
  → 30회 (10초 간격) Status 폴링 → Success/Failed/TimedOut 판정
```

### 프론트 (`main` 푸시 자동, Vercel)

```
main 브랜치 푸시
  → Vercel 자동 빌드 (Root Directory: frontend)
  → Next.js 빌드 (next build)
  → Vercel CDN 배포
```

---

## 요청 경로

```
사용자 브라우저
   ↓ HTTPS
wherewego.win (Cloudflare DNS only → Vercel CDN)
   ↓ /api/v1/* 만 (next.config.ts rewrites)
api.wherewego.win (Cloudflare Proxied → EC2:80)
   ↓ HTTP 80
EC2 Nginx (server_name api.wherewego.win)
   ↓ proxy_pass http://localhost:8080
Spring Boot (Docker 컨테이너)
   ↓ JDBC SSL (channel_binding=require)
Neon PostgreSQL (ap-southeast-1)
```

`next.config.ts` rewrites:
- `source: "/api/v1/:path*"` → `destination: "${BACKEND_BASE_URL}/api/v1/:path*"`
- 클라이언트는 same-origin 으로 인식 → 쿠키 자동 포함, CORS 무관

서버사이드 RSC 호출: `apiFetchServer` 가 `BACKEND_BASE_URL` 로 직접 호출하고 `access_token`/`refresh_token` 쿠키만 화이트리스트 포워딩.

---

## 파일 위치

| 파일 | 경로 |
|------|------|
| Dockerfile | `backend/Dockerfile` |
| GitHub Actions workflow | `.github/workflows/deploy.yml` |
| Next.js rewrites | `frontend/next.config.ts` |
| EC2 환경변수 파일 | `/etc/wherewego/.env` (EC2 내부, chmod 600, SSM Send-Command 가 매 배포마다 갱신) |
| Nginx 설정 | EC2 `/etc/nginx/sites-available/api.wherewego.win` |
| Logback 파일 회전 | `backend/supports/logging/src/main/resources/appenders/file-rolling-appender.xml` |
| EC2 로그 마운트 | `/var/log/wherewego/spring-%d{yyyy-MM-dd}.log.gz` (90일 보관, 5GB cap) |

---

## GitHub Secrets

> GitHub → Repository → Settings → Secrets and variables → Actions

| Secret 이름 | 설명 | 비고 |
|------------|------|------|
| `ENV_FILE` | `backend/.env` 파일 내용 전체 | 실제 값으로 채워진 것. Neon 연결문자열/JWT_SECRET/외부 API 키 포함 |
| `GH_PAT` | GitHub Personal Access Token | `read:packages` 권한. ghcr.io pull 용 |
| `AWS_ACCESS_KEY_ID` | SSM Send-Command IAM 사용자 키 | `ssm:SendCommand`/`ssm:PutParameter`/`ssm:GetParameter` 권한 |
| `AWS_SECRET_ACCESS_KEY` | 위 IAM 사용자 시크릿 | |
| `AWS_REGION` | `ap-northeast-2` | EC2 + SSM 리전 |
| `EC2_INSTANCE_ID` | `i-0c92f620e54fbf6ef` | SSM 대상 인스턴스 |

`GITHUB_TOKEN` 은 Actions 자동 제공 — 별도 등록 불필요.

> 이전 SSH 기반 배포의 잔재(`EC2_HOST`/`EC2_USER`/`EC2_SSH_KEY`)는 SSM 전환 후 미사용. Secret 정리 권장.

### Vercel 환경변수 (프론트, GitHub Secret 불필요)

| 변수 | 설명 |
|------|------|
| `NEXT_PUBLIC_MAPBOX_TOKEN` | Mapbox 공개 토큰 (번들 포함) |
| `NEXT_PUBLIC_MAPBOX_STYLE_URL` | Mapbox 스타일 URL |
| `BACKEND_BASE_URL` | `https://api.wherewego.win` (서버 컴포넌트 + rewrites destination) |
| `GATE_INVITE_CODE` | 2인 게이트 초대 코드 |
| `GATE_COOKIE_SECRET` | 게이트 쿠키 HMAC 시크릿 |

---

## EC2 최초 셋업 (1회)

```bash
# Docker (Ubuntu 22.04 기준)
sudo apt-get update && sudo apt-get upgrade -y
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker ubuntu

# Swap 1GB (t3.micro 1GB RAM OOM 방어)
sudo fallocate -l 1G /swapfile
sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# Nginx 80 → 8080 프록시
sudo apt-get install -y nginx
sudo tee /etc/nginx/sites-available/api.wherewego.win << 'EOF'
server {
    listen 80;
    server_name api.wherewego.win;
    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
EOF
sudo ln -s /etc/nginx/sites-available/api.wherewego.win /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl enable nginx && sudo systemctl start nginx

# SSM Agent 는 Amazon Ubuntu AMI 에 기본 설치되어 있음 (IAM Role 만 부여하면 됨)
# 인스턴스 IAM Role: AmazonSSMManagedInstanceCore 정책 필요
```

### EC2 Security Group 인바운드

| 포트 | 소스 | 용도 |
|------|------|------|
| 80 | 0.0.0.0/0 | Cloudflare → Nginx |
| 22 | (사용 안 함) | SSH 폐기, SSM 으로 접속 |

> SSM Session Manager 로 접속 가능하므로 22번 포트는 열 필요 없음.

---

## 로컬 개발 환경

```bash
# 백엔드
cd backend && ./gradlew :apps:wherewego-api:bootRun        # http://localhost:8080
# backend/.env 자동 로드 (spring.config.import: optional:file:.env[.properties])

# 프론트
cd frontend && npm run dev                                  # http://localhost:3000
# .env.local 에 BACKEND_BASE_URL 등 설정. next.config.ts rewrites 가 /api/v1 을 백엔드로 프록시

# DB만 Docker 로
cd backend/docker && docker-compose -f infra-compose.yml up # PostgreSQL 17 localhost:5432
```

---

## 주의사항 / 운영 기록

- `backend/.env` 및 `frontend/.env.local` 절대 커밋 금지 (`.gitignore` 확인)
- EC2 `/etc/wherewego/.env` 는 `chmod 600` 유지. SSM Send-Command 가 매 배포마다 `mkdir -p` 로 디렉토리 보장 + 갱신 (PR #31 fix)
- GH_PAT 만료 시 배포 실패 — 주기적 갱신 필요 (1년 권장)
- 현재 배포는 컨테이너 교체 방식 — 약 5~10초 다운타임 발생
- **Cloudflare SSL 모드 = Flexible 고정**. Full / Full Strict 로 변경하면 Cloudflare 가 EC2:443 으로 접속을 시도하다가 522 Connection Timeout 발생 (PR #33 트러블슈팅 기록)
- `next.config.ts` rewrites 가 없으면 `wherewego.win/api/v1/*` 가 Vercel 에서 404 처리되어 모든 클라이언트 API 호출이 실패 (PR #33 fix)
- Neon 무료 티어: 영구 무료, 일시정지 없음. 비밀번호 노출 시 즉시 재발급 가능 (대시보드)
- Slack 알림 webhook 은 `SLACK_WEBHOOK_*` 미설정 시 no-op (`SlackNotifier`)
