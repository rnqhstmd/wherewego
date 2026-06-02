# wherewego EC2 인스턴스 이전 가이드

> 목적: AWS 프리 티어 계정 교체 시 신규 EC2 인스턴스 셋업 + Grafana 모니터링 + 로그 압축 적재  
> 최종 작성: 2026-05-26

---

## 목차

1. [사전 작업: 기존 계정에서 정보 수집](#1-사전-작업-기존-계정에서-정보-수집)
2. [기존 인스턴스 종료](#2-기존-인스턴스-종료)
3. [신규 AWS 계정 EC2 생성](#3-신규-aws-계정-ec2-생성)
4. [IAM 설정 (Role + 사용자)](#4-iam-설정-role--사용자)
5. [EC2 서버 초기 세팅](#5-ec2-서버-초기-세팅)
6. [Grafana + Prometheus 모니터링 설치](#6-grafana--prometheus-모니터링-설치)
7. [로그 일별 압축 적재 설정](#7-로그-일별-압축-적재-설정)
8. [Cloudflare DNS 변경](#8-cloudflare-dns-변경)
9. [GitHub Secrets 업데이트](#9-github-secrets-업데이트)
10. [배포 검증](#10-배포-검증)

---

## 1. 사전 작업: 기존 계정에서 정보 수집

> ⚠️ 기존 인스턴스 종료 전에 반드시 완료해야 합니다.

### 1-1. SSM Parameter Store 값 복사

**AWS 콘솔 경로:** `Systems Manager` → `파라미터 스토어`

| 파라미터 이름 | 작업 |
|-------------|------|
| `/wherewego/env` | 클릭 → `[값 표시]` 버튼 → 전체 내용 메모장에 붙여넣기 |
| `/wherewego/gh-pat` | 클릭 → `[값 표시]` 버튼 → GitHub PAT 복사 |

> 이 두 값이 없으면 새 서버에서 환경변수를 처음부터 다시 세팅해야 합니다.

### 1-2. 현재 탄력적 IP 메모

**AWS 콘솔 경로:** `EC2` → `네트워크 및 보안` → `탄력적 IP`

현재 IP: `54.116.3.177` (새 계정에서는 다른 IP 발급됨)

---

## 2. 기존 인스턴스 종료

### 2-1. 탄력적 IP 연결 해제

**AWS 콘솔 경로:** `EC2` → `탄력적 IP`

```
기존 탄력적 IP 선택
→ [작업] → [탄력적 IP 주소 연결 해제]
→ 확인
```

> 연결 해제 후 반드시 [탄력적 IP 주소 릴리스]도 해야 과금이 멈춥니다.  
> (탄력적 IP는 인스턴스에 연결되지 않은 상태로 보유 시 시간당 과금)

```
탄력적 IP 선택 → [작업] → [탄력적 IP 주소 릴리스] → 릴리스
```

### 2-2. EC2 인스턴스 종료

**AWS 콘솔 경로:** `EC2` → `인스턴스`

```
인스턴스 선택 (wherewego-api, i-0c92f620e54fbf6ef)
→ [인스턴스 상태] → [인스턴스 종료]
→ "종료" 입력 → [종료]
```

> **중지(Stop)** 와 **종료(Terminate)** 는 다릅니다.  
> - 중지: 인스턴스 보관 (EBS 스토리지 과금 계속 발생)  
> - 종료: 인스턴스 + 스토리지 완전 삭제 → **종료를 선택해야 과금 없음**

---

## 3. 신규 AWS 계정 EC2 생성

### 3-1. 리전 확인

콘솔 우상단 리전이 **`아시아 태평양(서울) ap-northeast-2`** 인지 확인 후 시작.

### 3-2. EC2 인스턴스 시작

**AWS 콘솔 경로:** `EC2` → `인스턴스` → `[인스턴스 시작]`

| 항목 | 설정값 |
|------|-------|
| 이름 | `wherewego-api` |
| AMI | `Ubuntu Server 22.04 LTS` (64비트 x86) 검색 선택 |
| 인스턴스 유형 | `t3.micro` ← 프리 티어 대상, 반드시 이것으로 |
| 키 페어 | **`키 페어 없이 계속`** (SSM으로 접속하므로 불필요) |
| 스토리지 | `30 GiB gp3` |

**네트워크 설정 → `[보안 그룹 생성]`:**

```
보안 그룹 이름: wherewego-sg

인바운드 규칙 추가:
  ┌─────────────────────────────────────────────────────┐
  │ 유형: HTTP  │ 포트: 80  │ 소스: 0.0.0.0/0           │
  └─────────────────────────────────────────────────────┘
  
  ← 포트 0이나 SSH 22는 추가하지 않음
```

> Grafana는 나중에 SSM 포트 포워딩으로 접근 (보안 그룹에 3000 열 필요 없음)

`[인스턴스 시작]` 클릭

### 3-3. 탄력적 IP 할당 및 연결

**AWS 콘솔 경로:** `EC2` → `탄력적 IP`

```
[탄력적 IP 주소 할당] 클릭
→ 네트워크 경계 그룹: ap-northeast-2 확인
→ [할당]

→ 새로 만든 IP 선택 → [작업] → [탄력적 IP 주소 연결]
→ 인스턴스: wherewego-api 선택
→ [연결]
```

**새 탄력적 IP를 메모해두세요** — Cloudflare에 등록할 IP입니다.

---

## 4. IAM 설정 (Role + 사용자)

### 4-1. EC2용 IAM Role 생성

**AWS 콘솔 경로:** `IAM` → `역할` → `[역할 만들기]`

```
신뢰할 수 있는 엔터티: AWS 서비스
사용 사례: EC2
[다음]

권한 정책 검색: AmazonSSMManagedInstanceCore → 체크
[다음]

역할 이름: EC2-SSM-Role
[역할 생성]
```

**인라인 정책 추가 (SSM Parameter Store 읽기 권한):**

```
EC2-SSM-Role 클릭
→ [권한 추가] → [인라인 정책 생성]
→ JSON 탭 선택 → 아래 내용 붙여넣기
```

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "ssm:GetParameter",
      "Resource": "arn:aws:ssm:ap-northeast-2:새계정ID:parameter/wherewego/*"
    }
  ]
}
```

> `새계정ID` = 콘솔 우상단 계정 이름 옆 12자리 숫자

```
정책 이름: Wherewego-SSMParameterRead
[정책 생성]
```

**EC2에 Role 연결:**

```
EC2 → 인스턴스 → wherewego-api 선택
→ [작업] → [보안] → [IAM 역할 수정]
→ EC2-SSM-Role 선택 → [IAM 역할 업데이트]
```

### 4-2. GitHub Actions용 IAM 사용자 생성

**AWS 콘솔 경로:** `IAM` → `정책` → `[정책 생성]`

**정책 먼저 생성:**

```
JSON 탭 → 아래 내용 붙여넣기:
```

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ssm:GetCommandInvocation",
        "ssm:PutParameter",
        "ssm:SendCommand"
      ],
      "Resource": "*"
    }
  ]
}
```

```
정책 이름: GithubActions-Wherewego-Deploy
[정책 생성]
```

**IAM 사용자 생성:**

```
IAM → 사용자 → [사용자 생성]
이름: wherewego-deployer
[다음] → [직접 정책 연결]
→ GithubActions-Wherewego-Deploy 검색 → 체크
[다음] → [사용자 생성]
```

**액세스 키 발급:**

```
wherewego-deployer 클릭
→ [보안 자격 증명] 탭
→ [액세스 키 만들기]
→ 사용 사례: 서드 파티 서비스
→ [액세스 키 만들기]

⚠️ 이 화면에서만 Secret Access Key 확인 가능 → 반드시 저장!
ACCESS_KEY_ID: AKIA...
SECRET_ACCESS_KEY: ...
```

---

## 5. EC2 서버 초기 세팅

### 5-1. SSM Session Manager로 서버 접속

**AWS 콘솔 경로:** `Systems Manager` → `Session Manager` → `[세션 시작]`

```
인스턴스: wherewego-api 선택
→ [시작]
```

> IAM Role 연결 후 인스턴스가 Session Manager에 뜨기까지 1~2분 소요될 수 있습니다.

### 5-2. 기본 패키지 설치

```bash
# 업데이트
sudo apt-get update -y && sudo apt-get upgrade -y

# Docker 설치
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker ubuntu
sudo systemctl enable docker && sudo systemctl start docker

# Swap 1GB 추가 (t3.micro 1GB RAM OOM 방어)
sudo fallocate -l 1G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# Nginx 설치
sudo apt-get install -y nginx

# AWS CLI v2 설치
# deploy.yml의 "Deploy to EC2 via SSM" 단계가 EC2 안에서 직접
# `aws ssm get-parameter`로 Parameter Store(/wherewego/env, /wherewego/gh-pat)를
# 읽어오므로 필수. Ubuntu는 AWS CLI가 기본 설치되어 있지 않다.
sudo apt-get install -y unzip
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip -o awscliv2.zip
sudo ./aws/install
# SSM RunShellScript는 비로그인 셸이라 PATH에 /usr/local/bin이 없을 수 있다.
# (aws가 깔려 있어도 배포 스크립트가 `aws: not found`로 실패) → /usr/bin에 링크.
sudo ln -sf /usr/local/bin/aws /usr/bin/aws
aws --version
rm -rf awscliv2.zip aws
```

### 5-3. Nginx 설정

```bash
sudo tee /etc/nginx/sites-available/api.wherewego.win > /dev/null <<'EOF'
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
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl enable nginx && sudo systemctl start nginx
```

### 5-4. 디렉토리 초기화

```bash
# 앱 로그 디렉토리
sudo mkdir -p /var/log/wherewego
sudo chown 1000:1000 /var/log/wherewego

# 압축 로그 보관 디렉토리
sudo mkdir -p /var/log/wherewego/archive
sudo chown 1000:1000 /var/log/wherewego/archive

# 환경변수 디렉토리
sudo mkdir -p /etc/wherewego
sudo chmod 700 /etc/wherewego
```

---

## 6. Grafana + Prometheus 모니터링 설치

> Spring Boot Actuator가 `/actuator/prometheus`로 메트릭을 노출하고,  
> Prometheus가 수집, Grafana가 시각화합니다.

### 6-1. docker-compose 파일 생성

```bash
sudo mkdir -p /opt/monitoring
sudo tee /opt/monitoring/docker-compose.yml > /dev/null <<'EOF'
version: '3.8'

services:
  prometheus:
    image: prom/prometheus:latest
    container_name: prometheus
    restart: unless-stopped
    volumes:
      - /opt/monitoring/prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus_data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.retention.time=15d'
    ports:
      - "127.0.0.1:9090:9090"

  grafana:
    image: grafana/grafana:latest
    container_name: grafana
    restart: unless-stopped
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=wherewego2026
      - GF_USERS_ALLOW_SIGN_UP=false
    volumes:
      - grafana_data:/var/lib/grafana
    ports:
      - "127.0.0.1:3000:3000"
    depends_on:
      - prometheus

volumes:
  prometheus_data:
  grafana_data:
EOF
```

### 6-2. Prometheus 설정 파일

```bash
sudo tee /opt/monitoring/prometheus.yml > /dev/null <<'EOF'
global:
  scrape_interval: 30s
  evaluation_interval: 30s

scrape_configs:
  - job_name: 'wherewego-api'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']
    # Spring Boot 컨테이너와 같은 호스트이므로 localhost로 수집
EOF
```

### 6-3. 모니터링 스택 시작

```bash
cd /opt/monitoring
sudo docker compose up -d

# 상태 확인
sudo docker compose ps
```

### 6-4. 로컬 PC 사전 준비 (Mac / Windows)

#### Mac

```bash
# 1. AWS CLI 설치
brew install awscli

# 2. Session Manager Plugin 설치
brew install --cask session-manager-plugin

# 3. AWS CLI 자격증명 설정
aws configure
# AWS Access Key ID: wherewego-deployer의 Access Key
# AWS Secret Access Key: Secret Key
# Default region: ap-northeast-2
# Default output format: json

# 설치 확인
aws --version
session-manager-plugin --version
```

#### Windows (PowerShell)

```powershell
# 1. AWS CLI 설치
# https://awscli.amazonaws.com/AWSCLIV2.msi 다운로드 후 실행
# 또는 winget으로 설치:
winget install Amazon.AWSCLI

# 2. Session Manager Plugin 설치
# https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html
# 위 링크에서 SessionManagerPluginSetup.exe 다운로드 후 실행

# 3. AWS CLI 자격증명 설정
aws configure
# AWS Access Key ID: wherewego-deployer의 Access Key
# AWS Secret Access Key: Secret Key
# Default region: ap-northeast-2
# Default output format: json

# 설치 확인
aws --version
session-manager-plugin
```

### 6-5. Grafana 접속 (SSM 포트 포워딩)

> 보안 그룹에 포트를 열지 않고 안전하게 접속하는 방법입니다.  
> EC2 인스턴스는 Linux이므로 명령어 차이 없음 — 로컬 PC 터미널에서만 OS 구분.

#### Mac (터미널)

```bash
aws ssm start-session \
  --target i-새인스턴스ID \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["3000"],"localPortNumber":["3000"]}' \
  --region ap-northeast-2
```

#### Windows (PowerShell)

```powershell
aws ssm start-session `
  --target i-새인스턴스ID `
  --document-name AWS-StartPortForwardingSession `
  --parameters '{\"portNumber\":[\"3000\"],\"localPortNumber\":[\"3000\"]}' `
  --region ap-northeast-2
```

터미널에 `Waiting for connections...` 메시지가 뜨면 성공.  
브라우저에서 `http://localhost:3000` 접속  
- ID: `admin`  
- PW: `wherewego2026`

> 포트 포워딩은 터미널을 닫으면 연결이 끊깁니다. 볼 때만 실행하면 됩니다.

### 6-6. Grafana 초기 설정

**Prometheus 데이터소스 추가:**

```
좌측 메뉴 → Connections → Data sources → Add data source
→ Prometheus 선택
→ URL: http://prometheus:9090
→ [Save & test] → "Successfully queried" 확인
```

**JVM 대시보드 임포트:**

```
좌측 메뉴 → Dashboards → Import
→ Dashboard ID: 4701 입력 → [Load]
→ Prometheus 데이터소스 선택
→ [Import]
```

### 6-7. Grafana 대시보드 읽는 법

> t3.micro (1 vCPU, 1GB RAM) 기준으로 어떤 수치를 어떻게 봐야 의미 있는지 설명합니다.

---

#### 메모리 (가장 중요)

| 패널 이름 | 위치 | 정상 범위 | 위험 신호 |
|----------|------|---------|---------|
| JVM Heap Used | 상단 | 200~400MB | 500MB 초과 지속 |
| Non-Heap Used | 상단 | 80~120MB | 150MB 초과 |
| JVM Memory Used (전체) | 상단 | 350~500MB | 700MB 근접 |

**보는 방법:**
- 힙 메모리가 **톱니 패턴(올라갔다 뚝 떨어짐)** → 정상. GC가 잘 작동 중
- 힙 메모리가 **계단식으로 계속 오름** → 메모리 누수 의심, 재시작 필요
- 전체 메모리가 **700MB 이상 지속** → OOM 위험, JVM 옵션 `-Xmx512m` 확인

```
[정상]          [누수 의심]
▲ 400MB ┐      ▲ 700MB         ←── 위험
        │↘     │          ┌
        └ 200MB│      ────┘
               │  ────
               └ 200MB
```

---

#### CPU

| 패널 이름 | 정상 범위 | 위험 신호 |
|----------|---------|---------|
| CPU Usage (Process) | 5% 이하 (평상시) | 80% 이상 지속 |
| System CPU | 20% 이하 | 90% 이상 지속 |

**보는 방법:**
- API 요청 중 순간 스파이크는 정상
- **평상시에도 50% 이상** → 무한루프 또는 과도한 쿼리 의심
- t3.micro는 CPU 크레딧 소진 시 성능 제한됨 → CloudWatch `CPUCreditBalance` 별도 확인 권장

---

#### HTTP 요청

| 패널 이름 | 확인 포인트 |
|----------|-----------|
| HTTP Server Requests | 요청 수, 상태코드별 분포 |
| HTTP Response Time (p99) | 99번째 백분위 응답 시간 |

**보는 방법:**
- **p99 응답 시간 500ms 이하** → 정상
- **p99 1초 초과** → 특정 API 슬로우쿼리 또는 Neon DB 레이턴시 확인
- **5xx 응답 증가** → 즉시 `docker logs wherewego-api` 확인
- **4xx 응답 급증** → 클라이언트 오류 또는 인증 토큰 만료 이슈

---

#### GC (Garbage Collection)

| 패널 이름 | 정상 범위 | 위험 신호 |
|----------|---------|---------|
| GC Pause Duration | 50ms 이하/회 | 500ms 이상/회 |
| GC Collections | 분당 1~5회 | 분당 20회 이상 |

**보는 방법:**
- GC가 **잦고 오래 걸림** → 힙 사이즈 부족. `-Xmx` 값 조정 필요
- t3.micro에서 `-Xmx512m`으로 설정되어 있으므로 이 이상 늘리기 어려움

---

#### 스레드

| 패널 이름 | 정상 범위 | 위험 신호 |
|----------|---------|---------|
| Live Threads | 20~60개 | 200개 이상 |
| Daemon Threads | 15~40개 | - |

**보는 방법:**
- 스레드가 **계속 증가하고 줄지 않음** → 스레드 누수, DB 커넥션 풀 고갈 의심
- Neon DB Session Pooler 사용 중이므로 커넥션 수 확인 중요

---

#### 실전 모니터링 루틴

```
배포 직후 (5분간):
  1. JVM Heap → 기동 후 안정화되는지 확인 (보통 300MB 이하 안착)
  2. HTTP Requests → 첫 요청 들어오는지 확인
  3. 5xx 없는지 확인

평상시 (주 1회):
  1. 메모리 트렌드 → 계단식 상승 없는지
  2. p99 응답 시간 → 500ms 이내인지
  3. /var/log/wherewego 용량 → du -sh

이상 발생 시:
  1. 5xx 급증 → docker logs wherewego-api --tail 100
  2. 메모리 700MB 근접 → docker restart wherewego-api (응급)
  3. CPU 90% 지속 → 요청 패턴 확인, 슬로우쿼리 의심
```

---

## 7. 로그 일별 압축 적재 설정

> Spring Boot가 `/var/log/wherewego/`에 날짜별 `.log.gz` 파일을 생성하고,  
> cron으로 매일 자정에 `/archive/` 폴더로 이동합니다.

### 7-1. 현재 로그 구조

```
/var/log/wherewego/
  ├── spring-2026-05-26.log        ← 오늘 로그 (실시간)
  ├── spring-2026-05-25.log.gz     ← Logback이 자동 압축
  ├── spring-2026-05-24.log.gz
  └── archive/
        ├── spring-2026-05-25.log.gz  ← cron이 이동
        └── spring-2026-05-24.log.gz
```

### 7-2. 로그 아카이브 cron 설정

```bash
# crontab 편집
sudo crontab -e
```

아래 내용 추가 (매일 오전 1시 실행):

```cron
# wherewego 로그 아카이브 (매일 01:00)
0 1 * * * find /var/log/wherewego -maxdepth 1 -name "spring-*.log.gz" -mtime +0 -exec mv {} /var/log/wherewego/archive/ \; 2>&1 | logger -t wherewego-archive

# 90일 지난 아카이브 삭제
0 2 * * * find /var/log/wherewego/archive -name "*.log.gz" -mtime +90 -delete 2>&1 | logger -t wherewego-cleanup
```

### 7-3. 로그 확인 명령어

```bash
# 오늘 로그 실시간
tail -f /var/log/wherewego/spring-$(date +%Y-%m-%d).log

# 압축 로그 내용 확인 (압축 해제 없이)
zcat /var/log/wherewego/spring-2026-05-25.log.gz | tail -100

# 아카이브 목록
ls -lh /var/log/wherewego/archive/

# 특정 날짜 에러 검색
zcat /var/log/wherewego/archive/spring-2026-05-25.log.gz | grep "ERROR"

# 용량 확인
du -sh /var/log/wherewego/
du -sh /var/log/wherewego/archive/
```

### 7-4. logrotate 설정 (추가 안전망)

```bash
sudo tee /etc/logrotate.d/wherewego > /dev/null <<'EOF'
/var/log/wherewego/*.log {
    daily
    rotate 90
    compress
    delaycompress
    missingok
    notifempty
    dateext
    dateformat -%Y-%m-%d
    olddir /var/log/wherewego/archive
    createolddir 755 root root
}
EOF

# 설정 테스트
sudo logrotate --debug /etc/logrotate.d/wherewego
```

---

## 8. Cloudflare DNS 변경

**Cloudflare 콘솔 경로:** `wherewego.win 선택` → `DNS` → `레코드`

```
api.wherewego.win  A 레코드 찾기
→ [수정] 클릭
→ IPv4 주소: 54.116.3.177 → 새 탄력적 IP 입력
→ [저장]
```

| 항목 | 기존값 | 새값 |
|------|-------|-----|
| 이름 | api | api (동일) |
| 유형 | A | A (동일) |
| IPv4 주소 | 54.116.3.177 | 새 탄력적 IP |
| 프록시 상태 | Proxied (주황) | Proxied (주황) 유지 |

> **⚠️ SSL 모드는 절대 변경하지 마세요**  
> `SSL/TLS` 탭 → 개요 → `유연(Flexible)` 그대로 유지  
> Full/Full Strict로 바꾸면 EC2가 HTTPS를 처리 못해 **522 오류** 발생

DNS 전파: 보통 1~5분 (최대 24시간)

---

## 9. GitHub Secrets 업데이트

**GitHub 경로:** `레포지토리` → `Settings` → `Secrets and variables` → `Actions`

각 Secret 이름 클릭 → `[Update]`:

| Secret 이름 | 기존값 | 새로 입력할 값 |
|------------|-------|-------------|
| `EC2_INSTANCE_ID` | `i-0c92f620e54fbf6ef` | 새 인스턴스 ID (i-xxxx) |
| `AWS_ACCESS_KEY_ID` | 기존 키 | STEP 4-2에서 발급한 Access Key ID |
| `AWS_SECRET_ACCESS_KEY` | 기존 키 | STEP 4-2에서 발급한 Secret Access Key |
| `AWS_REGION` | `ap-northeast-2` | `ap-northeast-2` (변경 없음) |
| `ENV_FILE` | 기존값 | STEP 1-1에서 복사한 `/wherewego/env` 값 |
| `GH_PAT` | 기존값 | STEP 1-1에서 복사한 GitHub PAT |

---

## 10. 배포 검증

### 10-1. GitHub Actions 트리거

```bash
# 빈 커밋으로 배포 트리거
git commit --allow-empty -m "chore: trigger deployment to new instance"
git push origin main
```

### 10-2. 배포 상태 확인

**GitHub 경로:** `Actions` 탭 → 최신 워크플로우 클릭

배포 성공 시 로그 마지막에:
```
Deployment completed successfully
```

### 10-3. 헬스체크

```bash
# API 응답 확인
curl https://api.wherewego.win/actuator/health

# 기대 응답:
# {"status":"UP","components":{"db":{"status":"UP"}}}
```

### 10-4. Grafana 확인

```bash
# 로컬 PC에서 SSM 포트 포워딩
aws ssm start-session \
  --target i-새인스턴스ID \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["3000"],"localPortNumber":["3000"]}' \
  --region ap-northeast-2
```

`http://localhost:3000` → Spring Boot 메트릭 수집 확인

### 10-5. 로그 확인

```bash
# SSM Session Manager에서
docker logs wherewego-api --tail 50
tail -f /var/log/wherewego/spring-$(date +%Y-%m-%d).log
```

---

## 빠른 참조: 전체 체크리스트

```
[사전 작업 — 기존 계정]
  ☐ SSM /wherewego/env 값 메모장에 복사
  ☐ SSM /wherewego/gh-pat 값 복사

[기존 계정 정리]
  ☐ 탄력적 IP 연결 해제
  ☐ 탄력적 IP 릴리스
  ☐ EC2 인스턴스 종료 (Terminate)

[신규 계정 — EC2]
  ☐ 리전: ap-northeast-2 확인
  ☐ EC2 t3.micro 생성 (Ubuntu 22.04, 30GiB gp3)
  ☐ 보안 그룹: 포트 80만 오픈
  ☐ 탄력적 IP 할당 및 연결

[신규 계정 — IAM]
  ☐ IAM Role: EC2-SSM-Role 생성
      └─ AmazonSSMManagedInstanceCore (AWS 관리형)
      └─ Wherewego-SSMParameterRead (인라인, ssm:GetParameter)
  ☐ EC2에 EC2-SSM-Role 연결
  ☐ IAM 사용자: wherewego-deployer 생성
      └─ GithubActions-Wherewego-Deploy 정책 연결
  ☐ 액세스 키 발급 및 저장

[서버 초기 세팅 — SSM Session Manager]
  ☐ Docker 설치
  ☐ Swap 1GB 설정
  ☐ Nginx 설치 및 설정
  ☐ AWS CLI v2 설치 (+ /usr/bin/aws 심볼릭 링크 — SSM PATH 대응)
  ☐ 디렉토리 생성 (/var/log/wherewego, /etc/wherewego)

[모니터링]
  ☐ /opt/monitoring/docker-compose.yml 생성
  ☐ /opt/monitoring/prometheus.yml 생성
  ☐ docker compose up -d (prometheus + grafana)
  ☐ cron 로그 아카이브 설정

[외부 서비스]
  ☐ Cloudflare: api.wherewego.win A 레코드 → 새 탄력적 IP
  ☐ GitHub Secrets 4개 업데이트 (EC2_INSTANCE_ID, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, ENV_FILE)

[검증]
  ☐ main 빈 커밋 푸시 → GitHub Actions 성공
  ☐ https://api.wherewego.win/actuator/health → {"status":"UP"}
  ☐ SSM 포트 포워딩 → Grafana 메트릭 수집 확인
  ☐ docker logs wherewego-api → 에러 없음
```

---

## 트러블슈팅

### Session Manager에 인스턴스가 안 보일 때

```
원인: IAM Role이 EC2에 아직 적용 안 됨
해결: 1~2분 대기 후 새로고침
      또는 EC2 재시작 (중지 → 시작)
```

### 배포 후 502 Bad Gateway

```
원인: Spring Boot 컨테이너가 아직 기동 중 (보통 30~60초 소요)
해결: docker logs wherewego-api 로 기동 로그 확인
      "Started WherewegоApplication" 메시지 나올 때까지 대기
```

### Grafana 메트릭 수집 안 될 때

```
원인: Spring Boot 컨테이너가 아직 미기동 상태
해결: docker ps 로 컨테이너 상태 확인
      컨테이너 실행 후 Prometheus가 자동으로 재수집 시작
```

### Cloudflare 522 에러

```
원인: SSL 모드가 Full 또는 Full Strict로 변경됨
해결: Cloudflare → SSL/TLS → 개요 → "유연(Flexible)"으로 변경
```
