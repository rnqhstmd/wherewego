# 문서 인덱스

우리가갈지도(MayGo) 프로젝트의 기술 문서 모음입니다.

---

## 프로젝트 문서 (읽기용)

| 문서 | 설명 |
|------|------|
| [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md) | 프로젝트 전체 정리 — 기능, API, 화면, 인프라, Phase 진행도 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 시스템 아키텍처 — 모듈 구조, 시퀀스 다이어그램 |
| [TECH.md](TECH.md) | 기술 스택 상세 — 버전, 의존성 선택 배경 |
| [ERD.md](ERD.md) | 데이터베이스 ERD |
| [CHATBOT_SETUP_GUIDE.md](CHATBOT_SETUP_GUIDE.md) | 카카오 i 오픈빌더 챗봇 설정 단계별 가이드 |
| [INFRA_SETUP.md](INFRA_SETUP.md) | EC2 · Nginx · Cloudflare · GitHub Actions 인프라 구축 가이드 |

---

## ADR (Architecture Decision Records)

| 문서 | 결정 |
|------|------|
| [adr/0001-redis-kafka-usage.md](adr/0001-redis-kafka-usage.md) | Redis/Kafka 도입 검토 (폐기) |
| [adr/0002-redis-removal-caffeine.md](adr/0002-redis-removal-caffeine.md) | Redis 제거 → Caffeine 전환 |

---

## 운영 가이드 & 런북

| 문서 | 설명 |
|------|------|
| [operations/README.md](operations/README.md) | 운영 가이드 목차 |
| [operations/grafana-monitoring.md](operations/grafana-monitoring.md) | Grafana 대시보드 설정 및 메트릭 |
| [operations/logging.md](operations/logging.md) | 로그 구조 · 파일 롤링 · MDC RequestId |
| [operations/slack-alerts.md](operations/slack-alerts.md) | Slack 알림 채널 · 임계값 설정 |
| [operations/notification-scaling-roadmap.md](operations/notification-scaling-roadmap.md) | 알림 인프라 진화 로드맵 (SSE 재도입 조건) |
| [operations/phase-7-rollback.md](operations/phase-7-rollback.md) | 태그 V006 마이그레이션 롤백 플레이북 |
| [operations/phase-8-notifications.md](operations/phase-8-notifications.md) | Phase 8 알림 도메인 운영 참고 |

---

## 아키텍처 심화

| 문서 | 설명 |
|------|------|
| [architecture/notification-sse-archive.md](architecture/notification-sse-archive.md) | Phase 8 SSE 인프라 아카이브 — SSE 재도입 시 참고 |

---

## 개발 세션 산출물

개발 중 생성된 변경 로그 및 작업 노트입니다. 직접 읽을 일은 적으며, 의사결정 추적이 필요할 때 참고합니다.

| 문서 | 내용 |
|------|------|
| [sessions/SESSION_2026_05_19_CHANGES.md](sessions/SESSION_2026_05_19_CHANGES.md) | 2026-05-19 작업 변경 내역 |
| [sessions/SESSION_2026_05_20_CHANGES.md](sessions/SESSION_2026_05_20_CHANGES.md) | 2026-05-20 작업 변경 내역 |
| [sessions/SESSION_2026_05_20_INFRA_MIGRATION.md](sessions/SESSION_2026_05_20_INFRA_MIGRATION.md) | 2026-05-20 인프라 마이그레이션 작업 노트 |
