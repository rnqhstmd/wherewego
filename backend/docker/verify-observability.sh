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

echo "[verify-observability] 모든 검증 통과 (AC-14, AC-15)"
