# 프로젝트 컨텍스트 — 우리가갈지도 (MayGo)

> 카카오톡 챗봇과 글로벌 3D 지도를 통해 커플(향후 N인 그룹)이 **가고 싶은 장소와 추억의 장소**를 아카이빙하는 서비스의 도메인 지식 저장소입니다.

## 도메인

| 도메인 | 설명 | 상세 |
|--------|------|------|
| auth | 카카오 OAuth2 로그인 + JWT 세션 | [상세](auth/README.md) |
| group | 그룹 매칭 (MVP: 2인 커플 / 스키마: N:M 확장 가능) | [상세](group/README.md) |
| chatbot | 카카오톡 Skill Webhook (5자리 연동 코드 TTL 10분) | [상세](chatbot/README.md) |
| place | 외부 장소 API 연동 + 인스타 메타 파싱 | [상세](place/README.md) |
| pin | 핀 CRUD + 중복 방지 (visited 제거, tag 도입) | [상세](pin/README.md) |
| memo | 메모 (2초 룰, 수동 우선) | [상세](memo/README.md) |
| **tag** | **MVP 핵심**: PLACE(파란 동그라미) / MEMORY(핑크 하트) 카테고리 | [상세](tag/README.md) |
| map | Mapbox 3D 지도 + 파스텔 핀 UI | [상세](map/README.md) |
| recommendation | 위치 기반 룰렛 (Haversine 거리) | [상세](recommendation/README.md) |

## 공통

- [공통 용어 사전](glossary.md)

## 출처 문서

- `requirements/wherewego_mvp.md` — MVP 기획서 (도메인 분할의 근거)

## 주요 설계 결정

- **visited 기능 제거** — 미방문/방문완료 구분 없음. 대신 `tag`로 핀 의미 구분
- **JWT 세션** — 1인 개발·단일 EC2 환경에 적합
- **Group 추상화** — `Couple_ID` 대신 `group_id`, MVP는 비즈니스 레이어에서 1인 1그룹 제약
- **Haversine 거리** — PostGIS 없이 애플리케이션 레벨 계산 (~50핀 규모에 충분)
