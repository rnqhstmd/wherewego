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
| 탄력적 IP | 54.116.3.177 (고정) |
| 스토리지 | 30GB gp3 |

**보안 그룹 (wherewego-sg) 인바운드 규칙:**
| 포트 | 용도 |
|------|------|
| 22 | SSH |
| 80 | HTTP |
| 8080 | Spring Boot API |

**EC2 초기 세팅 (접속 후 실행 필요):**
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
```

**SSH 접속:**
```bash
ssh -i ~/.ssh/wherewego-key.pem ubuntu@54.116.3.177
```

---

### 2. VPC / 네트워크 설정

- 기본 VPC: `vpc-0fe873103bc41346a` (172.31.0.0/16)
- 서브넷 생성: `wherewego-subnet-2a` (172.31.0.0/20, ap-northeast-2a)
- 인터넷 게이트웨이: `wherewego-igw` 생성 후 VPC 연결
- 라우팅 테이블: `0.0.0.0/0 → wherewego-igw` 추가

---

### 3. GitHub Actions Secrets 등록

| Secret 이름 | 용도 |
|------------|------|
| `EC2_HOST` | 54.116.3.177 |
| `EC2_USER` | ubuntu |
| `EC2_SSH_KEY` | `~/.ssh/wherewego-key.pem` 전체 내용 |
| `ENV_FILE` | 운영 환경변수 전체 (prod값으로 수정) |
| `GH_PAT` | ghcr.io pull용 (read:packages 권한) |

---

### 4. deploy.yml 개선

- EC2에 `.env` 파일을 영구 저장하지 않도록 변경
- `mktemp` 임시 파일 생성 → `docker run` 후 즉시 삭제
- JVM 메모리 제한 추가: `-Xmx512m -Xms256m`
- Docker 컨테이너 메모리 제한: `--memory 700m`

```
GitHub Secrets (ENV_FILE)
  → GitHub Actions
  → EC2 임시 파일 (mktemp, chmod 600)
  → docker run --env-file
  → 임시 파일 즉시 삭제 (rm -f)
```

---

### 5. 외부 API 키 발급

| API | 상태 |
|-----|------|
| Gemini 2.0 Flash | ✅ 발급 완료 (wherewego Gemini API Key) |
| Google Places | ✅ 발급 완료 |
| Kakao OAuth2 | ✅ 기존 사용 중 |
| Kakao Local | ✅ 기존 사용 중 |

---

### 6. 카카오 i 오픈빌더 스킬 URL 업데이트

```
스킬명: 인스타링크수집
URL: http://54.116.3.177:8080/api/v1/chatbot/webhook
헤더: X-Kakao-Skill-Secret (KAKAO_SKILL_SECRET 값)
```

---

## 남은 작업

```
[ ] EC2 Docker 설치 완료 (재접속 후 docker --version 확인)
[ ] ENV_FILE Secret — SPRING_PROFILES_ACTIVE=prod, EC2 IP로 수정 완료 확인
[ ] main 브랜치 push → GitHub Actions 첫 자동 배포
[ ] http://54.116.3.177:8080/swagger-ui.html 접속 확인
[ ] 카카오 오픈빌더 스킬 테스트 통과
[ ] 카카오 개발자 콘솔 Redirect URI 추가
     (http://54.116.3.177:8080/auth/kakao/callback)
[ ] AWS Budgets 과금 알림 설정 (0원 초과 시 이메일)
```

---

## 주요 파일

| 파일 | 설명 |
|------|------|
| `.github/workflows/deploy.yml` | GitHub Actions CI/CD 파이프라인 |
| `backend/Dockerfile` | Alpine JRE 21 경량 이미지 |
| `~/.ssh/wherewego-key.pem` | EC2 SSH 접속 키 (로컬에만 보관) |

---

## 로컬 개발 환경

```bash
# 프론트엔드
cd frontend && npm run dev          # http://localhost:3000

# 백엔드 (backend/.env 파일 필요)
cd backend && ./gradlew :apps:wherewego-api:bootRun

# 로컬 DB만 Docker로
cd backend/docker
docker-compose -f infra-compose.yml up
```
