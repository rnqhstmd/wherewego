# ADR 0001: Redis 도입 / Kafka 미도입

## 상태

Accepted (2026-05-14, Phase 0)

## 맥락

- 우리가갈지도(MayGo) MVP는 일 ~30건 수준의 트래픽을 가정한다.
- 현 멀티모듈 골격에는 `modules/redis`와 `modules/kafka` 두 인프라 모듈이 준비되어 있으나, 사용 시점과 필요성이 결정되지 않아 빌드 그래프 포함 여부가 불확정 상태였다.
- Phase 1~13 설계 과정에서 비동기/캐시 성격을 가진 사용처 3가지가 논의되었다.
  1. 카카오 i 오픈빌더 연동을 위한 **5자리 연동 코드 TTL(10분)** 저장 (Phase 2)
  2. 챗봇 자유 입력 처리의 **2초 룰 세션 캐시** (Phase 9)
  3. Google Places API **폴백 처리** — 카카오 Webhook 5초 SLA 내 응답 불가 시 비동기 결과 푸시 (Phase 5)
- 각 사용처에 대해 Redis/Kafka/Spring @Async 중 적절한 수단을 선택해야 한다.

## 결정

### 1) 5자리 연동 코드 TTL(10분)

- **Redis 사용**
- 근거: TTL 자동 만료 기능으로 10분 후 코드 무효화를 애플리케이션 레이어 스케줄러 없이 처리. `SETNX` 기반 코드 충돌 원자적 검출. `modules/redis` 기존 인프라(master/replica 구조) 활용으로 추가 인프라 도입 비용 없음.

### 2) 챗봇 2초 룰 세션 캐시

- **Redis 사용** (1)과 동일 인스턴스 재사용
- 근거: 2초 룰 세션 상태는 짧은 TTL이 필요하며 애플리케이션 재시작 후에도 진행 중 세션이 유지되어야 한다. 인메모리 캐시는 재시작 시 손실되므로 부적합. 5자리 연동 코드와 동일 Redis 인스턴스에 namespace 분리로 운영.

### 3) Google Places API 폴백 처리

- **Spring `@Async` + 카카오 i 오픈빌더 콜백 메시지**
- 근거: 카카오 Webhook 5초 SLA를 초과할 가능성이 있는 외부 API 호출은 우선 동기 요청을 시도하고, 임박 시 즉시 "처리 중" 응답을 반환한 후 `@Async`로 후처리한 결과를 카카오 콜백 메시지로 사용자에게 푸시한다. Kafka 같은 메시지 브로커 없이 단순 스레드풀 기반 비동기로 충분하며, MVP 규모(일 ~30건)에서 메시지 유실/재처리 보증 요구가 없다.

### 4) Kafka 도입 여부

- **미도입 (Phase 0에서 `modules/kafka` 완전 제거)**
- 근거: 위 3가지 사용처 모두 Redis 또는 `@Async`로 해결되므로 Kafka가 제공하는 메시지 브로커 보증(지속성, 파티셔닝, 컨슈머 그룹)이 MVP에 불필요하다. 운영 비용(Kafka + ZooKeeper/KRaft, kafka-ui, 모니터링)이 트래픽 규모 대비 과도하다. `modules/kafka` 디렉토리, `settings.gradle.kts` include 선언, `docker/infra-compose.yml`의 kafka/kafka-ui 서비스를 모두 제거한다. Git 히스토리에는 보존되어 재도입 시 복원 가능하다.

## 결과

- `modules/redis`: `apps/wherewego-api` 빌드 의존성에 포함하여 정식 사용. local `infra-compose.yml`에 Redis 7 컨테이너 유지.
- `modules/kafka`: 디렉토리 완전 삭제. `settings.gradle.kts`에서 `:modules:kafka` include 제거. `docker/infra-compose.yml`에서 kafka/kafka-ui 서비스 및 볼륨 제거. 오타 패키지 `confg.kafka`도 함께 제거.
- Phase 2(5자리 연동 코드) → Redis 사용.
- Phase 9(챗봇 2초 룰 세션) → Redis 사용 (Phase 2와 동일 인스턴스).
- Phase 5(Google Places 폴백) → Spring `@Async` + 카카오 콜백 메시지.

## Kafka 재도입 검토 트리거

다음 조건 중 하나라도 충족되면 ADR-0002로 Kafka 재도입을 결정한다.

- Phase 8 인스타그램 메타 스크래핑 spike의 차단율 > 30% — 외부 의존 작업의 재시도/지연 처리에 브로커가 필요할 수 있다.
- 카카오 Webhook 일 처리 건수 > 1,000건 — `@Async` 스레드풀 기반 폴백 처리의 운영 신뢰도 한계에 도달한다.

**복원 절차:**

1. `git log --diff-filter=D -- backend/modules/kafka/` 또는 Phase 0 제거 시점 커밋 hash 확인.
2. `git checkout <hash> -- backend/modules/kafka/`로 디렉토리 복원.
3. 오타 패키지 `confg.kafka` → `config.kafka`로 정정 후 재배치.
4. `backend/settings.gradle.kts`에 `:modules:kafka` include 재추가.
5. `backend/docker/infra-compose.yml`에 kafka/kafka-ui 서비스와 볼륨 재정의.
6. `apps/wherewego-api/build.gradle.kts`에 `implementation(project(":modules:kafka"))` 추가.
