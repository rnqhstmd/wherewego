# wherewego-api Backend

우리가갈지도(MayGo) 백엔드 멀티모듈 프로젝트.

- **런타임:** Spring Boot 3.4.4 + Java 21 + PostgreSQL 17 + Redis 7
- **빌드:** Gradle (Kotlin DSL)
- **모듈 구성:** `apps/wherewego-api`, `modules/{jpa,redis}`, `supports/{jackson,logging,monitoring}`, `spike/instagram-meta-scraper`

---

## Quick Start (로컬 30분 기동)

신규 개발자가 클론 직후 30분 내에 `/actuator/health` 200을 받는 절차이다.

1. **저장소 클론**
   ```bash
   git clone <repo-url>
   cd wherewego/backend
   ```

2. **`.env` 생성**
   ```bash
   cp .env.example .env
   ```

3. **Supabase 값 입력** (`.env` 편집)
   - `POSTGRES_*`: Supabase 대시보드의 Project Settings > Database 에서 `Connection string` 확인. **port는 5432(direct)** 사용. 6543(PgBouncer transaction) 금지.
   - `KAKAO_*`, `MAPBOX_TOKEN`, `GOOGLE_PLACES_API_KEY`: 각 서비스 콘솔 발급.
   - `JWT_SECRET`: 32자 이상 랜덤 문자열.
   - **로컬 전용**: `POSTGRES_HOST=localhost`, `POSTGRES_PORT=5432`, `POSTGRES_DB=wherewego`, `POSTGRES_USER=wherewego`, `POSTGRES_PASSWORD=wherewego` (infra-compose 기본값)

4. **로컬 인프라 기동** (PostgreSQL + Redis)
   ```bash
   docker compose -f docker/infra-compose.yml up -d
   ```

5. **앱 기동**
   ```bash
   ./gradlew :apps:wherewego-api:bootRun
   ```
   기동 시 Flyway가 `V001__init_schema.sql`을 자동 적용한다.

6. **헬스 체크**
   ```bash
   curl http://localhost:8080/actuator/health
   # → {"status":"UP"}
   ```

---

## Profile 구조

| Profile | 용도 | DB | 시크릿 |
|---------|------|----|--------|
| `local` | 로컬 개발 | infra-compose PostgreSQL 17 | `.env` |
| `test` | 테스트 (CI 포함) | Testcontainers postgres:17-alpine | 테스트 properties |
| `dev`   | EC2 개발 서버 | Supabase `wherewego-dev` | EC2 systemd `EnvironmentFile` |
| `prod`  | 운영 | Supabase `wherewego-prod` | GitHub Actions secrets → EC2 env |

`qa` profile은 사용하지 않는다.

---

## 환경별 시크릿 주입

### local
- 파일: `backend/.env`
- Git 추적: 제외 (`.gitignore`)
- 로딩: `application.yml`의 `spring.config.import: optional:file:.env[.properties]`

### dev (EC2 개발 서버)
- 파일: `/etc/wherewego/.env`
- 권한: `chown wherewego:wherewego /etc/wherewego/.env && chmod 600 /etc/wherewego/.env`
- 적용: systemd unit
  ```ini
  [Service]
  EnvironmentFile=/etc/wherewego/.env
  ExecStart=/usr/bin/java -jar /opt/wherewego/wherewego-api.jar
  ```

### prod
- 저장: GitHub Actions secrets
- 적용: 배포 워크플로우에서 EC2 환경변수로 주입 (`/etc/systemd/system/wherewego-api.service`의 `Environment=` 또는 `EnvironmentFile=`로 렌더)
- **`.env` 파일 사용하지 않음**. SSM Parameter Store는 MVP 단계에서 미사용.

---

## Flyway 재실행 절차

Flyway Community Edition은 자동 rollback을 지원하지 않는다. V001 실패 또는 스키마 초기화가 필요할 때 아래 절차를 사용한다.

### 최초 배포 전 검증 (baseline-on-migrate 안전성)

`baseline-on-migrate: true` 설정 때문에 기존 데이터가 있는 DB에 적용 시 현재 상태가 V001로 baseline 처리되어 마이그레이션 SQL이 실행되지 않을 수 있습니다. 최초 배포 전 반드시 다음을 확인하세요.

1. Supabase SQL Editor에서 빈 DB인지 확인:
   ```sql
   SELECT table_name FROM information_schema.tables WHERE table_schema='public';
   ```
   결과가 빈 행이거나 `flyway_schema_history`만 있어야 합니다.
2. 기존 테이블이 있다면 배포 중단 후 원인 파악

### local (infra-compose)
```bash
# 1. 컨테이너 + volume 함께 제거
docker compose -f docker/infra-compose.yml down -v

# 2. 인프라 재기동
docker compose -f docker/infra-compose.yml up -d

# 3. 앱 재기동 → Flyway가 V001부터 다시 적용
./gradlew :apps:wherewego-api:bootRun
```

대안:
```bash
./gradlew :apps:wherewego-api:flywayClean
./gradlew :apps:wherewego-api:flywayMigrate
```

### dev (Supabase `wherewego-dev`)
1. **현재 접속 중인 Supabase 인스턴스 URL을 반드시 확인** — prod 인스턴스에 실행하지 않도록 주의.
2. Supabase 대시보드 SQL Editor에서 신중하게 실행:
   ```sql
   DROP SCHEMA public CASCADE;
   CREATE SCHEMA public;
   ```
   **개발 데이터 손실을 인지한 상태에서만 실행한다.**
3. 앱 재배포로 Flyway 자동 적용.

### prod (Supabase `wherewego-prod`)
- **`DROP SCHEMA public CASCADE;` 등 파괴적 SQL 직접 실행 금지.**
- **Supabase 지원 티켓을 통한 복구만 허용. 직접 SQL 실행 금지.**
- **복구 전 현재 접속 중인 Supabase 인스턴스 URL을 반드시 확인.**
- V002+ 파괴적 변경 시에는 해당 마이그레이션 파일 상단 주석에 백업 절차를 명시한다.

**PgBouncer 주의:** dev/prod jdbc-url은 **port 5432 강제**이다. Supabase Connection Pooler(6543, transaction mode)는 Flyway가 사용하는 `pg_advisory_lock`과 호환되지 않아 마이그레이션이 실패한다.

---

## 자주 쓰는 Gradle 명령

```bash
# 전체 빌드
./gradlew build

# 앱 모듈만
./gradlew :apps:wherewego-api:build

# 테스트
./gradlew :apps:wherewego-api:test

# 인스타 스크래핑 spike 실행
./gradlew :spike:instagram-meta-scraper:runSpike
```

---

## 디렉토리 구조

```
backend/
├── apps/wherewego-api/           # 메인 애플리케이션 (Spring Boot)
│   └── src/main/resources/db/migration/V001__init_schema.sql
├── modules/
│   ├── jpa/                      # Hikari + JPA + QueryDSL + Flyway 설정
│   └── redis/                    # Lettuce master/replica
├── supports/
│   ├── jackson/                  # ObjectMapper 표준화
│   ├── logging/                  # 로그 포맷
│   └── monitoring/               # Actuator + Prometheus
├── spike/instagram-meta-scraper/ # 인스타 메타 스크래핑 spike
└── docker/
    ├── infra-compose.yml         # PostgreSQL + Redis
    └── monitoring-compose.yml    # Prometheus + Grafana
```
