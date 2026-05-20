# observability 관련 프로젝트

| 레포 | 역할 | 담당 |
|------|------|------|
| rnqhstmd/wherewego | Spring Boot Actuator + Micrometer + `SlackNotifier` + Caffeine 캐시 | rnqhstmd |
| (외부) Prometheus | 메트릭 스크레이프 (`backend/docker/grafana/prometheus.yml`, port 8081 `/actuator/prometheus`) | — |
| (외부) Grafana | 대시보드 시각화 (`backend/docker/grafana/provisioning/`, JVM overview) | — |
| (외부) Slack Incoming Webhook | 알림 전송 채널 (`SLACK_WEBHOOK_URI`) | — |
