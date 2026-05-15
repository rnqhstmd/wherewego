# ADR 0002: Redis 제거 / Caffeine 인메모리 캐시 전환

## 상태

Accepted (2026-05-15, Phase 1). **ADR-0001의 Redis 도입 결정을 부분 수정**한다.

## 맥락

[ADR-0001](0001-redis-kafka-usage.md)에서 다음 두 사용처에 Redis 도입을 결정했다:

1. **5자리 연동 코드 TTL(10분)** — Phase 2
2. **챗봇 2초 룰 세션 캐시** — Phase 9

Phase 1 auth 구현 직후, Redis 사용처의 실제 요구사항을 재검토했다.

### 재검토 결과

| 검토 항목 | Redis | Caffeine 인메모리 |
|---------|-------|------------------|
| TTL 자동 만료 | ✅ `EXPIRE` | ✅ `expireAfterWrite(10, MINUTES)` |
| 원자적 충돌 검출 | ✅ `SETNX` | ✅ `Cache.asMap().putIfAbsent()` |
| 재시작 후 데이터 유지 | ✅ AOF/RDB 영속화 | ❌ 인메모리 (재시작 시 손실) |
| 다중 인스턴스 공유 | ✅ | ❌ |
| 운영 비용 | Redis 컨테이너 + 메모리 + 모니터링 | 0 (애플리케이션 내장) |
| 의존성 | `spring-data-redis`, redis 서버 | `com.github.ben-manes.caffeine:caffeine` 한 줄 |
| 한국어 / Java 생태계 | 기본 (Spring Data) | 기본 (Spring Cache 호환) |

### 현 시점 환경 가정 (PRD 기반)

- MVP 베타 사용자: **~100명** (커플 ~50쌍)
- 트래픽: **일 30건** (인스타 공유 + 챗봇 발화)
- **1인 개발 / 단일 EC2 t3.micro**
- 인스턴스 재시작은 배포 시점에만 발생. 사용자 흐름 중 재시작 확률 매우 낮음

### Redis가 제공하는 가치 vs 비용

- **재시작 후 데이터 유지**: 5자리 코드(TTL 10분) 또는 2초 룰 세션이 재시작 시 손실되어도 사용자는 재발급/재시도로 즉시 복구. 실질 가치 작음.
- **다중 인스턴스 공유**: 단일 EC2 환경에서 무의미.
- **인프라 비용**: Redis 컨테이너 + AOF 영속화 + master/replica 구성 + 모니터링 — **MVP 단계에서 과한 운영 부담**.

## 결정

### 1) 5자리 연동 코드 TTL → **Caffeine 인메모리 캐시**

```java
Cache<String, Long> botLinkCodes = Caffeine.newBuilder()
    .expireAfterWrite(Duration.ofMinutes(10))
    .maximumSize(10_000)
    .build();
```

- `Cache.asMap().putIfAbsent()` 로 원자적 충돌 검출
- TTL은 `expireAfterWrite`로 자동 만료
- DB `bot_link_codes` 테이블은 유지 (감사/이력용 — Phase 0 V001 그대로)

### 2) 챗봇 2초 룰 세션 → **Caffeine 인메모리 캐시**

```java
Cache<String, String> lastLink = Caffeine.newBuilder()
    .expireAfterWrite(Duration.ofSeconds(2))
    .maximumSize(1_000)
    .build();
```

- 2초 후 자동 만료. botUserKey별 1개 entry.
- 재시작 시 손실되어도 영향 미미 (2초 흐름)

### 3) Google Places 비동기 (`@Async`)

- ADR-0001 결정 그대로 유지. Redis와 무관.

### 4) Redis 모듈 / 인프라 제거

- `backend/modules/redis` 디렉토리 git history 보존하며 제거
- `apps/wherewego-api/build.gradle.kts`: `:modules:redis` 의존 제거, **Caffeine 3.1.8** 추가
- `application.yml`: `redis.yml` import 제거, redis 관련 설정 제거
- `docker/infra-compose.yml`: `redis-master`, `redis-readonly` 서비스 + 볼륨 제거
- `.env`, `.env.example`: `REDIS_*` 환경변수 제거
- `settings.gradle.kts`: `:modules:redis` include 제거

## 결과

- 인프라 단순화: Redis 컨테이너 0, 운영 모니터링 대상 1개 감소
- 의존성 ~600KB 추가 (Caffeine), Redis 클라이언트 의존성 제거 (트레이드)
- Phase 2 / Phase 9 구현 단순화: Redis 클라이언트 코드 없이 `@Component`의 final Cache 필드로 처리
- Phase 0 V001 schema의 `bot_link_codes` 테이블은 그대로 유지 (감사 기록 용도)

## Redis 재도입 검토 트리거

다음 조건 중 하나라도 충족되면 **ADR-0003**으로 Redis 재도입을 결정한다.

- **EC2 다중 인스턴스 확장** — 인메모리는 인스턴스 간 미공유이므로 5자리 코드/세션이 인스턴스 라우팅에 의존하게 됨. 로드밸런서 sticky session도 약한 보장.
- **일 트래픽 1,000건 초과** — 인메모리 메모리 사용량 증가 + GC 영향. Redis 별도 인스턴스로 분리하는 게 안정적.
- **재시작 시 활성 세션 유지 요구** — 운영 SLA 강화로 부팅 중 사용자 흐름 보장이 필요해질 경우.

### Redis 복원 절차

1. `git log --diff-filter=D -- backend/modules/redis/` 로 본 PR 제거 시점 커밋 hash 확인
2. `git checkout <hash> -- backend/modules/redis/` 로 디렉토리 복원
3. `backend/settings.gradle.kts`에 `:modules:redis` include 재추가
4. `backend/apps/wherewego-api/build.gradle.kts`에 `implementation(project(":modules:redis"))` 추가, Caffeine 제거
5. `backend/docker/infra-compose.yml`에 redis-master/redis-readonly 서비스 + 볼륨 재정의
6. `application.yml`에 `redis.yml` import 재추가
7. `.env.example`에 `REDIS_*` 환경변수 재정의
8. Phase 2/9 코드의 Caffeine `Cache` 참조를 Spring Data Redis `RedisTemplate`/`@Cacheable`로 교체
