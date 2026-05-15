# 배포 전략

## 개요

| 항목 | 내용 |
|------|------|
| 인프라 | AWS EC2 (단일 인스턴스) |
| 컨테이너 | Docker (단일 컨테이너) |
| 이미지 저장소 | GitHub Container Registry (ghcr.io) |
| CI/CD | GitHub Actions — main 브랜치 머지 시 자동 배포 |
| 환경변수 | GitHub Secrets → EC2 `/etc/wherewego/.env` → Docker `--env-file` |
| DB | Supabase PostgreSQL (외부, EC2 내 DB 없음) |

---

## 배포 흐름

```
main 브랜치 머지
  → GitHub Actions 트리거
  → Gradle bootJar 빌드 (-x test)
  → Docker 이미지 빌드 → ghcr.io 푸시
      태그: latest, {git-sha}
  → EC2 SSH 접속
      → /etc/wherewego/.env 갱신 (secrets.ENV_FILE)
      → docker pull 최신 이미지
      → 기존 컨테이너 stop/rm
      → docker run (새 컨테이너 기동)
      → docker image prune (미사용 이미지 정리)
```

---

## 파일 위치

| 파일 | 경로 |
|------|------|
| Dockerfile | `backend/Dockerfile` |
| GitHub Actions workflow | `.github/workflows/deploy.yml` |
| EC2 환경변수 파일 | `/etc/wherewego/.env` (EC2 내부, chmod 600) |

---

## GitHub Secrets 목록

> GitHub → Repository → Settings → Secrets and variables → Actions → New repository secret

| Secret 이름 | 설명 | 비고 |
|------------|------|------|
| `ENV_FILE` | `backend/.env` 파일 내용 전체 | 실제 값으로 채워진 것 |
| `GH_PAT` | GitHub Personal Access Token | `read:packages` 권한 필요 |
| `EC2_HOST` | EC2 퍼블릭 IP 또는 도메인 | 예: `13.125.xxx.xxx` |
| `EC2_USER` | EC2 SSH 사용자명 | ubuntu / ec2-user |
| `EC2_SSH_KEY` | EC2 접속용 PEM 키 전체 내용 | `-----BEGIN` 포함 |

`GITHUB_TOKEN`은 Actions가 자동 제공 — 별도 등록 불필요.

---

## EC2 최초 셋업 (1회)

```bash
# Docker 설치 (Ubuntu 기준)
sudo apt-get update && sudo apt-get install -y docker.io

# sudo 없이 docker 사용
sudo usermod -aG docker ubuntu
newgrp docker

# 환경변수 디렉토리 생성
sudo mkdir -p /etc/wherewego
```

---

## 로컬 개발 환경

- `backend/.env` 파일 직접 사용 (`.gitignore`에 의해 커밋 안 됨)
- `spring.config.import: optional:file:.env[.properties]` 로 자동 로드
- `bootRun` working dir = backend root 고정 (build.gradle 설정)

---

## 주의사항

- `backend/.env`는 절대 커밋 금지 (`.gitignore` 확인)
- EC2 `/etc/wherewego/.env` 는 `chmod 600` 유지
- GH_PAT 만료 시 배포 실패 — 주기적 갱신 필요 (1년 권장)
- 현재 배포는 컨테이너 교체 방식 — 약 5~10초 다운타임 발생
