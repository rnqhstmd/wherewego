#!/usr/bin/env bash
# ============================================================
# verify-observability.sh
# Phase 0: Wave 4 관측성 검증 스크립트
#
# 검증 항목:
#   AC-14: Prometheus가 spring-boot-app target을 정상 scrape 한다
#   AC-15: JVM heap memory 지표(jvm_memory_used_bytes{area="heap"})를 조회할 수 있다
#
# 사전 조건:
#   1. docker compose -f backend/docker/infra-compose.yml up -d
#   2. docker compose -f backend/docker/monitoring-compose.yml up -d
#   3. ./gradlew :apps:wherewego-api:bootRun (8081 포트)
#   4. 로컬에 curl, jq 설치
#
# 사용법: bash backend/docker/verify-observability.sh
# ============================================================

set -e

PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"

echo "[verify-observability] Prometheus URL: ${PROMETHEUS_URL}"

APPLICATION_URL="${APPLICATION_URL:-http://localhost:8080}"

# ------------------------------------------------------------
# AC-14: Prometheus targets up 상태 확인
# ------------------------------------------------------------
echo "[verify-observability] AC-14 검증 시작: spring-boot-app target up 상태"

TARGETS_RESPONSE="$(curl -sf "${PROMETHEUS_URL}/api/v1/targets")"

if echo "${TARGETS_RESPONSE}" | jq -e '.data.activeTargets[] | select(.labels.job == "spring-boot-app") | select(.health == "up")' > /dev/null; then
  echo "[verify-observability] AC-14 PASS: spring-boot-app target이 'up' 상태입니다"
else
  echo "[verify-observability] AC-14 FAIL: spring-boot-app target을 찾을 수 없거나 'up' 상태가 아닙니다"
  echo "${TARGETS_RESPONSE}" | jq '.data.activeTargets[] | {job: .labels.job, health: .health, lastError: .lastError}'
  exit 1
fi

# ------------------------------------------------------------
# AC-15: JVM heap memory 지표 조회
# ------------------------------------------------------------
echo "[verify-observability] AC-15 검증 시작: jvm_memory_used_bytes{area=\"heap\"} 조회"

QUERY_RESPONSE="$(curl -sf -G "${PROMETHEUS_URL}/api/v1/query" --data-urlencode 'query=jvm_memory_used_bytes{area="heap"}')"

if echo "${QUERY_RESPONSE}" | jq -e '.data.result | length > 0' > /dev/null; then
  echo "[verify-observability] AC-15 PASS: heap memory 지표 조회 성공"
  echo "${QUERY_RESPONSE}" | jq '.data.result[] | {id: .metric.id, application: .metric.application, value: .value[1]}'
else
  echo "[verify-observability] AC-15 FAIL: heap memory 지표를 조회할 수 없습니다"
  echo "${QUERY_RESPONSE}" | jq '.'
  exit 1
fi

# ------------------------------------------------------------
# AC-1 (Phase 2.11 PR-A): X-Request-Id 응답 헤더 echo
#   - 모든 HTTP 요청에 RequestIdFilter가 UUID를 발급하고 응답 헤더로 echo한다.
#   - /actuator/health는 인증 없이 접근 가능하므로 검증 entry point로 사용.
# ------------------------------------------------------------
echo "[verify-observability] AC-1 검증 시작: X-Request-Id 응답 헤더 UUID 형식"

REQUEST_ID_HEADER="$(curl -sfI "${APPLICATION_URL}/actuator/health" \
  | tr -d '\r' \
  | grep -i '^x-request-id:' \
  | sed 's/^[xX]-[rR]equest-[iI]d:[[:space:]]*//')"

if [[ -z "${REQUEST_ID_HEADER}" ]]; then
  echo "[verify-observability] AC-1 FAIL: X-Request-Id 응답 헤더를 찾을 수 없습니다"
  echo "  - APPLICATION_URL=${APPLICATION_URL}/actuator/health 응답에 헤더가 누락"
  echo "  - RequestIdFilter + RequestIdFilterConfig 등록 확인 필요"
  exit 1
fi

if ! echo "${REQUEST_ID_HEADER}" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'; then
  echo "[verify-observability] AC-1 FAIL: X-Request-Id가 UUID 형식이 아닙니다: '${REQUEST_ID_HEADER}'"
  exit 1
fi

echo "[verify-observability] AC-1 PASS: X-Request-Id=${REQUEST_ID_HEADER}"

# 연속 2회 호출 — 서로 다른 UUID인지 (AC-2: 격리)
REQUEST_ID_2="$(curl -sfI "${APPLICATION_URL}/actuator/health" \
  | tr -d '\r' \
  | grep -i '^x-request-id:' \
  | sed 's/^[xX]-[rR]equest-[iI]d:[[:space:]]*//')"

if [[ "${REQUEST_ID_HEADER}" == "${REQUEST_ID_2}" ]]; then
  echo "[verify-observability] AC-2 FAIL: 연속 2회 호출의 X-Request-Id가 동일합니다 (MDC 오염 의심)"
  exit 1
fi

echo "[verify-observability] AC-2 PASS: 연속 2회 호출 UUID 격리 (${REQUEST_ID_HEADER} != ${REQUEST_ID_2})"

# ------------------------------------------------------------
# 수동 검증 권고 (자동화 어려운 항목)
# ------------------------------------------------------------
cat <<EOF

[verify-observability] 수동 검증 권고:
  - AC-3 (외부 API 5필드 로그): docker logs wherewego-api | grep "api=" 확인
  - AC-7/8/9 (파일 회전): /var/log/wherewego/ 디렉토리 확인 (dev/prod 환경)
  - AC-15/16 (Slack 본문 requestId/SCHEDULER): 강제 에러 트리거 후 Slack 채널 확인

EOF

echo "[verify-observability] 모든 검증 통과 (AC-14, AC-15, AC-1, AC-2)"
