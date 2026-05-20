# 2026-05-20 인프라 마이그레이션 — DB · 프론트 배포 · 도메인 · Nginx

> 이 문서는 운영 배포 전 인프라 전반을 정비한 세션을 기록한다.
> 주요 작업: Supabase → Neon 마이그레이션, Vercel 프론트 배포, Cloudflare 도메인 구성, EC2 Nginx 설치.

---

## 1. Supabase 운영 DB 데이터 초기화

### 배경
운영 배포 전 테스트 과정에서 운영 DB에 더미 데이터가 유입됨.
스키마(테이블 구조)는 유지하되 전체 데이터만 삭제하고 ID 시퀀스를 초기화해야 했음.

### 실행한 SQL
```sql
TRUNCATE TABLE
    pins,
    bot_link_codes,
    bot_user_mappings,
    invite_links,
    group_members,
    groups,
    users
RESTART IDENTITY CASCADE;
```

- `RESTART IDENTITY`: BIGSERIAL 시퀀스를 1부터 재시작
- `CASCADE`: 외래키 참조 순서를 자동 처리
- `flyway_schema_history`는 건드리지 않아 마이그레이션 상태 유지

---

## 2. RLS(Row Level Security) 검토 및 비활성화

### RLS란?
PostgreSQL의 행 단위 접근 제어 기능. 테이블에 RLS를 활성화하면
연결된 Role에 따라 쿼리 결과가 필터링된다.

```
RLS ON + Policy 있음  → Policy 조건에 맞는 행만 반환
RLS ON + Policy 없음  → 슈퍼유저 외 모든 접근 차단 (기본 deny)
RLS OFF               → 권한만 있으면 전체 행 접근 가능
```

### 현재 상태
- Supabase 대시보드에서 `rls_auto_enable` 이벤트 트리거가 설정되어 있었음
- `public` 스키마에 `CREATE TABLE` 발생 시 자동으로 RLS를 활성화
- 모든 테이블에 RLS가 켜져 있었지만 Policy가 하나도 없는 상태였음

### Spring 백엔드에 영향이 없었던 이유
`jpa.yml` 주석 기준으로 Spring은 `postgres.<project-ref>` 계정으로 연결.
이 계정은 Supabase의 슈퍼유저이므로 RLS를 완전히 우회.
따라서 Policy 없는 RLS는 Spring 백엔드에 아무 영향도 없었음.

### 비활성화 결정 이유
- 모든 DB 접근이 Spring 백엔드 경유 → 애플리케이션 레벨에서 인증/인가 처리
- DB 레벨 RLS 불필요한 복잡도
- Policy 없는 RLS는 추후 새 Role 추가 시 디버깅 어려움
- 이벤트 트리거를 제거하지 않으면 미래 Flyway 마이그레이션으로 생성된 테이블에도 자동 적용

### 비활성화 SQL
```sql
ALTER TABLE users             DISABLE ROW LEVEL SECURITY;
ALTER TABLE groups            DISABLE ROW LEVEL SECURITY;
ALTER TABLE group_members     DISABLE ROW LEVEL SECURITY;
ALTER TABLE invite_links      DISABLE ROW LEVEL SECURITY;
ALTER TABLE bot_link_codes    DISABLE ROW LEVEL SECURITY;
ALTER TABLE bot_user_mappings DISABLE ROW LEVEL SECURITY;
ALTER TABLE pins              DISABLE ROW LEVEL SECURITY;

DROP EVENT TRIGGER IF EXISTS rls_auto_enable_trigger;
DROP FUNCTION IF EXISTS rls_auto_enable();
```

---

## 3. DB 플랫폼 Supabase → Neon 마이그레이션

### 마이그레이션 결정 배경

| 항목 | Supabase 무료 | Neon 무료 |
|------|--------------|----------|
| 비용 | 영구 무료 | 영구 무료 |
| 일시정지 | **7일 비활성 시 프로젝트 자동 정지** | 없음 |
| 콜드 스타트 | 재개까지 수십 초 | 연결 시 ~500ms |
| PostgreSQL | 15 | 17 |
| 스토리지 | 500MB | 512MB |

사이드 프로젝트 특성상 트래픽이 간헐적임.
Supabase는 7일 비활성 시 서비스가 완전 중단되는 리스크가 있었음.
2명 사용자 기준 데이터는 평생 수십 MB를 넘지 않으므로 Neon 무료 티어로 충분.

EC2(t3.micro) 동일 인스턴스 PostgreSQL 방안은 Spring Boot JVM(~400MB) + PostgreSQL(~200MB) + OS(~150MB) 합산 시 1GB RAM 초과로 OOM 위험이 있어 제외.

### Neon 프로젝트 설정
- PostgreSQL 버전: 17 (기존 Supabase와 동일)
- Region: AWS Asia Pacific 1 (Singapore) — 서울 리전 미제공, 인접 리전 선택
- Pooler 방식: Session Pooler (port 5432) — Flyway `pg_advisory_lock` 호환

### jpa.yml 변경 (`backend/modules/jpa/src/main/resources/jpa.yml`)

prod 프로필 주석 업데이트 및 `channel_binding` 추가:

```yaml
# 변경 전
# Supabase Session Pooler 사용 ...
# POSTGRES_USER: postgres.<project-ref> 형식

# 변경 후
# Neon Pooler 사용 (port 5432, Flyway pg_advisory_lock 호환).
# POSTGRES_HOST: ep-<name>-pooler.c-2.ap-southeast-1.aws.neon.tech 형식
# POSTGRES_USER: neondb_owner

data-source-properties:
  reWriteBatchedInserts: true
  ssl: true
  sslmode: require
  channel_binding: require   # ← 추가 (Neon 연결 문자열 요구사항)
```

### 환경변수 변경 내용 (`.env.prod`)

```properties
# 변경 전 (Supabase)
POSTGRES_HOST=aws-<n>-<region>.pooler.supabase.com
POSTGRES_USER=postgres.<project-ref>

# 변경 후 (Neon)
POSTGRES_HOST=ep-dry-field-aopba2ke-pooler.c-2.ap-southeast-1.aws.neon.tech
POSTGRES_PORT=5432
POSTGRES_DB=neondb
POSTGRES_USER=neondb_owner
POSTGRES_PASSWORD=<Neon 대시보드에서 발급>
```

---

## 4. 환경변수 관리 구조 정비

### 배포 파이프라인 구조
```
.env.prod (로컬, gitignore)
    ↓ 수동으로 내용 복사
GitHub Secret: ENV_FILE
    ↓ deploy.yml
AWS SSM Parameter Store: /wherewego/env
    ↓ EC2 SSM Send-Command
/etc/wherewego/.env
    ↓ docker run --env-file
Spring Boot 컨테이너
```

### GitHub Secrets 현황 및 필요 조치

| Secret | 상태 | 비고 |
|--------|------|------|
| `ENV_FILE` | ✅ 있음 → 교체 필요 | Neon 값으로 업데이트 |
| `GH_PAT` | ✅ 있음 | 변경 없음 |
| `AWS_ACCESS_KEY_ID` | ❌ 없음 → 추가 필요 | deploy.yml SSM 인증 |
| `AWS_SECRET_ACCESS_KEY` | ❌ 없음 → 추가 필요 | deploy.yml SSM 인증 |
| `AWS_REGION` | ❌ 없음 → 추가 필요 | `ap-northeast-2` |
| `EC2_INSTANCE_ID` | ❌ 없음 → 추가 필요 | `i-0c92f620e54fbf6ef` |
| `EC2_HOST` | 잔재 (미사용) | SSH→SSM 전환으로 불필요 |
| `EC2_SSH_KEY` | 잔재 (미사용) | 삭제 가능 |
| `EC2_USER` | 잔재 (미사용) | 삭제 가능 |

> `EC2_HOST`, `EC2_SSH_KEY`, `EC2_USER`는 이전 SSH 기반 배포 방식의 잔재.
> deploy.yml이 AWS SSM 방식으로 전환(`63ad431`)되면서 더 이상 참조되지 않음.

### 프론트엔드 환경변수
프론트(Next.js)는 Vercel에 배포되므로 GitHub Secrets 불필요.
Vercel 대시보드 → Environment Variables에서 직접 관리.

| 변수명 | 설명 |
|--------|------|
| `NEXT_PUBLIC_MAPBOX_TOKEN` | Mapbox 공개 토큰 (번들에 포함) |
| `NEXT_PUBLIC_MAPBOX_STYLE_URL` | Mapbox 스타일 URL |
| `BACKEND_BASE_URL` | `https://api.wherewego.win` (서버 컴포넌트 전용) |
| `GATE_INVITE_CODE` | 2인 게이트 초대 코드 |
| `GATE_COOKIE_SECRET` | 게이트 쿠키 HMAC 시크릿 |

---

## 5. 프론트엔드 Vercel 배포

### Vercel 선택 이유
- 영구 무료 (Hobby 플랜)
- Next.js 공식 호스팅 플랫폼 — 빌드 최적화 자동
- 2명 트래픽에서 100GB/월 대역폭 제한 소진 불가능
- main 브랜치 푸시 시 자동 배포

### 설정 핵심
- **Root Directory: `frontend`** 필수 — 모노레포 구조이므로 루트를 그대로 쓰면 빌드 실패
- Framework Preset: Next.js (자동 감지)
- 환경변수 5개 등록 (위 표 참고)

---

## 6. 도메인 구조 설계 (Cloudflare + Vercel + EC2)

### 최종 DNS 구조

| 도메인 | Type | Target | Proxy | 용도 |
|--------|------|--------|-------|------|
| `wherewego.win` | CNAME | `*.vercel-dns-017.com` | DNS Only (회색) | 프론트엔드 (Vercel) |
| `api.wherewego.win` | A | `54.116.3.177` | Proxied (주황) | 백엔드 (EC2) |
| `www.wherewego.win` | — | — | — | 사용 안 함 (삭제) |

### Proxy 설정이 다른 이유

**`wherewego.win` → DNS Only**
Vercel은 자체 CDN과 DDoS 보호를 가지고 있음.
Cloudflare Proxy를 앞에 두면 이중 SSL 처리로 성능 저하 + Vercel의 봇 미티게이션 비활성화.
Vercel "1-click fix"가 자동으로 Proxied → DNS Only로 교체하고 전용 CNAME 레코드를 추가함.

**`api.wherewego.win` → Proxied**
EC2에는 자체 SSL 인증서가 없음.
Cloudflare Proxy가 클라이언트와 HTTPS를 처리하고 EC2로는 HTTP(80)로 포워딩.
Nginx가 Cloudflare로부터 80 포트 요청을 받아 Spring Boot(8080)로 프록시.

### Vercel 도메인 설정 주의사항
Vercel에 루트 도메인 추가 시 자동으로 `www.서브도메인`을 Production으로 설정하고
루트 → www 307 리다이렉트를 기본으로 생성함.
이를 막으려면 도메인 추가 시 **"Redirect to www" 체크박스를 해제**해야 함.

---

## 7. EC2 Nginx 설치 및 설정

### 필요한 이유
Cloudflare Proxy는 EC2로 HTTP(80) 요청을 포워딩함.
Spring Boot는 8080 포트로 실행 중이므로 80 → 8080 포워딩을 위한 리버스 프록시가 필요.

### 설치 및 설정 (Session Manager 터미널)
```bash
sudo apt update && sudo apt install -y nginx

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
sudo nginx -t
sudo systemctl enable nginx
sudo systemctl start nginx
```

### 요청 흐름
```
클라이언트 HTTPS
    ↓
Cloudflare (SSL 종료, DDoS 보호)
    ↓ HTTP 80
EC2 Nginx
    ↓ HTTP 8080
Spring Boot 컨테이너
```

### 검증
```
nginx: configuration file /etc/nginx/nginx.conf test is successful
Active: active (running)
```

---

## 8. 운영 배포 체크리스트

> 이 문서 작성 시점 기준 미완료 항목

- [ ] Neon 비밀번호 재발급 (채팅에서 노출됨 — 최우선)
- [ ] `.env.prod` `<기존값>` 모두 채우기
- [ ] GitHub Secrets 추가: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`, `EC2_INSTANCE_ID`
- [ ] GitHub Secrets 업데이트: `ENV_FILE` → Neon 값으로 교체
- [ ] Vercel 환경변수 `BACKEND_BASE_URL` → `https://api.wherewego.win` 확인
- [ ] `main` 브랜치 푸시 → deploy.yml 자동 실행
- [ ] 배포 후 `https://api.wherewego.win/actuator/health` → `{"status":"UP"}` 확인
- [ ] `https://wherewego.win` 프론트 정상 렌더링 확인
- [ ] Neon DB에 Flyway 마이그레이션 V001~V005 전체 적용 확인

---

## 9. 트러블슈팅 기록

### 9-1. Vercel `www` 자동 생성 문제
루트 도메인 추가 시 Vercel이 `www.wherewego.win`을 Production으로 자동 설정.
루트 도메인이 www로 307 리다이렉트되고 www는 DNS 미설정으로 Invalid Configuration.
→ `www.wherewego.win` 도메인 삭제 + 루트 도메인을 Production으로 직접 지정.

### 9-2. Cloudflare Proxy와 Vercel 충돌
`wherewego.win` CNAME을 Proxied(주황)으로 설정하면 Vercel이 "Proxy Detected" 경고 표시.
Vercel 자체 CDN과 Cloudflare CDN이 이중으로 동작해 성능 저하 및 봇 보호 기능 비활성화.
→ Vercel "1-click fix" 버튼으로 DNS Only(회색)로 자동 변환 + 전용 CNAME 레코드로 교체.

### 9-3. systemctl 페이저 문제
Session Manager 터미널에서 `&&`로 연결된 명령어 체인 중 `systemctl` 명령이 페이저(목록 표시)를 열어 나머지 명령어가 페이저 입력으로 처리됨.
→ 명령어를 분리하여 순차 실행으로 해결.

### 9-4. Neon DB 자격증명 채팅 노출
Neon 연결 문자열을 채팅 메시지로 직접 붙여넣어 비밀번호 노출.
→ Neon 대시보드에서 즉시 비밀번호 재발급 필요. 향후 연결 문자열은 절대 채팅/이슈/코드에 직접 입력 금지.
