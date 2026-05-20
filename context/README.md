# 프로젝트 컨텍스트 — 우리가갈지도 (MayGo)

> 카카오톡 챗봇과 글로벌 3D 지도를 통해 커플(향후 N인 그룹)이 **가고 싶은 장소와 추억의 장소**를 아카이빙하는 서비스의 도메인 지식 저장소입니다.

## 도메인

| 도메인 | 설명 | 상세 |
|--------|------|------|
| auth | 카카오 OAuth2 로그인 + JWT 세션 | [상세](auth/README.md) |
| group | 그룹 매칭 (MVP: 2인 커플 / 스키마: N:M 확장 가능) | [상세](group/README.md) |
| chatbot | 카카오톡 Skill Webhook (6자리 연동 코드 TTL 10분) | [상세](chatbot/README.md) |
| place | 외부 장소 API 연동 + 인스타 메타 파싱 | [상세](place/README.md) |
| pin | 핀 CRUD + 중복 방지 (visited 제거, tag 도입) | [상세](pin/README.md) |
| memo | 메모 (2초 룰, 수동 우선) | [상세](memo/README.md) |
| **tag** | **MVP 핵심**: PLACE(파란 동그라미) / MEMORY(핑크 하트) 카테고리 | [상세](tag/README.md) |
| map | Mapbox 3D 지도 + 파스텔 핀 UI | [상세](map/README.md) |
| recommendation | 위치 기반 룰렛 (Haversine 거리) | [상세](recommendation/README.md) |
| observability | 외부 API 사각지대 가시화 + 임계값 기반 Slack 알림 (횡단) | [상세](observability/README.md) |

## 공통

- [공통 용어 사전](glossary.md)

## 출처 문서

- `requirements/wherewego_mvp.md` — MVP 기획서 (도메인 분할의 근거)

## 주요 설계 결정

- **visited 기능 제거** — 미방문/방문완료 구분 없음. 대신 `tag`로 핀 의미 구분
- **JWT 세션** — 1인 개발·단일 EC2 환경에 적합
- **Group 추상화** — `Couple_ID` 대신 `group_id`, MVP는 비즈니스 레이어에서 1인 1그룹 제약
- **Haversine 거리** — PostGIS 없이 애플리케이션 레벨 계산 (~50핀 규모에 충분)
- **디자인 핸드오프 워크플로 (Phase 6+)** — 시각적 UI 변경이 큰 Phase에서는 [Claude Design](https://claude.ai/design)에서 와이어프레임/하이파이를 만들고, 핸드오프 번들(tar.gz, `tokens.jsx`/`screens-*.jsx`/`icons/` 포함)을 `.dev/{branch}/design-bundle/`에 보관 후 PRD에 첨부한다. coder는 `tokens.jsx`의 색상/폰트/공용 컴포넌트(`PinDot`/`PinTag`/`SpeechBubblePopup` 등)와 `screens-*.jsx`의 화면별 사양을 React/TypeScript로 1:1 변환한다. 디자인 토큰은 `frontend/src/lib/design/tokens.ts` + `globals.css @theme` 양쪽에 정의해 클래스명/JS 참조 모두 지원. Phase 6 적용 사례 — PR [#13](https://github.com/rnqhstmd/wherewego/pull/13).

## 구현 로드맵 (Phase 진행도)

| Phase | 범위 | 상태 | PR |
|-------|------|------|-----|
| Phase 0 | DB 스키마(V001) + 인프라 기반(Spring Boot, Gradle multi-module, Testcontainers, Flyway, observability) | ✅ 완료 | [#1](https://github.com/rnqhstmd/wherewego/pull/1) |
| Phase 1 | 카카오 OAuth2 + JWT 세션 (auth 도메인) | ✅ 완료 | [#3](https://github.com/rnqhstmd/wherewego/pull/3) |
| **Phase 2** | **카카오톡 챗봇 Skill Webhook + 인스타 장소 파이프라인** (bot/chatbot/place/pin/group read-only/memo) | ✅ 완료 | [#5](https://github.com/rnqhstmd/wherewego/pull/5) |
| Phase 2.5 | 장소명 추출 regex → **Gemini 2.0 Flash** 전환 ([상세](place/gemini-migration.md)) | ✅ 완료 | [#15](https://github.com/rnqhstmd/wherewego/pull/15) |
| Phase 3 | 그룹 생성/초대/탈퇴 + 활성 GroupMember 권한 검사 (group 도메인 본 구현) | ✅ 완료 | [#7](https://github.com/rnqhstmd/wherewego/pull/7) |
| Phase 4 | 웹 UI 핀 CRUD (목록/수정/삭제) + 핀 직접 등록 웹 API | ✅ 완료 | [#9](https://github.com/rnqhstmd/wherewego/pull/9), [#13](https://github.com/rnqhstmd/wherewego/pull/13) |
| Phase 5 | **Google Places API 비동기 폴백** + 카카오 콜백 푸시 (해외 장소 지원) | ✅ 완료 | [#11](https://github.com/rnqhstmd/wherewego/pull/11) |
| **Phase 6** | **Mapbox 3D 지도 + 파스텔 핀 UI + 위치 기반 룰렛 (Haversine)** + 디자인 시스템 신설 + 핀 직접 등록 웹 API | ✅ 완료 | [#13](https://github.com/rnqhstmd/wherewego/pull/13) |
| **Phase 2.6 PR-A** | **UX 완성**: 웹 메모 수동 편집(FR-MMO-2/4), 룰렛 MEMORY 토글(FR-REC-6), `tokens.ts` self-host 주석 정리 | ✅ 완료 | [#17](https://github.com/rnqhstmd/wherewego/pull/17) |
| **Phase 2.6 PR-B** | **보안·운영 안정화**: SameSite Lax(보안 HIGH), `@RefreshScope`+Actuator `/refresh`(localhost 제한), Bucket4j 챗봇 레이트 리밋(분당 10회), 그룹 탈퇴 시 BotUserMapping cascade | ✅ 완료 | [#18](https://github.com/rnqhstmd/wherewego/pull/18) |
| Phase 2.7 | **신뢰 인프라**(테스트 자동화 완성): PLACE_SELECTION E2E 5건, 그룹 동시성 통합 3종, `GeminiPlaceClient` WireMock(BASE_URL 외부화), PR-A 이월 frontend Vitest 6건, map 디자인 번들 컴포넌트 Vitest 7건 (총 28 케이스) | ✅ 완료 | [#20](https://github.com/rnqhstmd/wherewego/pull/20) |
| Phase 2.8 | **핀 도메인 완성**(사용자 가시 UX 잔여 부분): 핀 등록 시 `instagramUrl` 입력 UI, 핀 장소 정보(`place_name`/`address`) 텍스트 수정, map ⋮ 메뉴 삭제 액션. 좌표 수정/삭제 복원은 분리 → Phase 2.10 | ✅ 완료 | [#21](https://github.com/rnqhstmd/wherewego/pull/21) |
| Phase 2.9 | **규모 대응**: 핀 목록 API 페이지네이션 계약 준비(`page`/`size` + `totalCount`/`hasNext` 선택 응답, 부분 전달/비숫자 400 매핑) + DOM Marker→Mapbox GL symbol layer 마이그레이션 사전 분석 문서(`context/map/gl-migration-plan.md`). 실제 GL 전환과 `/pins` UI 페이지네이션은 임계치 도달 시 별도 Phase | ✅ 완료 | [#22](https://github.com/rnqhstmd/wherewego/pull/22) |
| Phase 2.10 | **잔여 후속 통합**(MVP 운영 잔여): ① 핀 좌표 수정(지도 picker 재사용) + 삭제 핀 복원 UI([pin](pin/status.md)), ② 카카오 i 오픈빌더 PLACE_SELECTION 버튼 `action="message"` + `extra.placeId` 동작 검증([chatbot](chatbot/status.md) — Phase 2.6 PR-C 이월), ③ Pretendard 폰트 self-host 전환 + Mapbox 토큰 회전 SOP 운영자 가이드([map](map/status.md)) | ⬜ 계획 | — |
| **Phase 2.11** | **observability foundation**(외부 API 사각지대 해소 + 운영 로그 적재): Phase 1 — MDC RequestId 필터, 외부 API 공통 구조화 로그(`api/op/duration_ms/outcome/cache`), Google Places Micrometer 메트릭+Caffeine 캐시(Gemini 패턴 복제), **일별 로그 회전+90일 보관(Logback `TimeBasedRollingPolicy`, `/var/log/wherewego/spring-%d.log.gz`, gzip 압축, Docker volume mount + json-file 이중 적재 150MB 상한)**. Phase 2 — `@Scheduled` 일일 합계 임계값 스케줄러(Google Places 80%/95% 2단계, Gemini 5xx 10%/1h), Instagram scraper 3-stage 차단 감지(1h 윈도우 50% 임계), Slack 본문 RequestId 동봉. [observability](observability/README.md) | ⬜ 계획 | — |
| Phase 3.0 | **MVP 후 확장**(비즈니스 정책, 도메인별 별도 PRD): Group N인 확장(1인 1활성 제약 해제), 재가입 허용 정책(`uq_group_members_pair` 변경), BotUserMapping 회원 탈퇴 cascade(회원 탈퇴 PRD 선행) | ⬜ 장기 | — |

도메인별 구현 상태는 각 `context/{도메인}/status.md` 참조.

## 주요 ADR

- [ADR-0001 Redis/Kafka 도입 검토](../docs/adr/0001-redis-kafka-usage.md) (폐기)
- [ADR-0002 Redis 제거 + Caffeine 전환](../docs/adr/0002-redis-removal-caffeine.md)
