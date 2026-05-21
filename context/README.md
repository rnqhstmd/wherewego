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
| **tag** | **Phase 7 갱신**: REEL·발견(연보라 인스타아이콘) / WISH·설렘(민트 동그라미) / MEMORY·추억(핑크 하트) 3종 카테고리 | [상세](tag/README.md) |
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
| Phase 2.10 | **잔여 후속 통합**(MVP 운영 잔여): ① 핀 좌표 수정(지도 picker 재사용) + 삭제 핀 복원 UI([pin](pin/status.md)), ② 카카오 i 오픈빌더 PLACE_SELECTION 버튼 `action="message"` + `extra.placeId` 동작 검증([chatbot](chatbot/status.md) — Phase 2.6 PR-C 이월), ③ Pretendard 폰트 self-host 전환 + Mapbox 토큰 회전 SOP 운영자 가이드([map](map/status.md)) | ✅ 완료 | [#24](https://github.com/rnqhstmd/wherewego/pull/24) |
| **Phase 2.11 PR-A** | **observability foundation**: MDC RequestId 필터, 외부 API 공통 구조화 로그(`api/op/duration_ms/outcome/cache`), 일별 로그 회전+90일 보관(Logback `TimeBasedRollingPolicy`, gzip 압축, Docker volume mount + json-file 이중 적재 150MB 상한), Slack 본문 RequestId 동봉. [observability](observability/README.md) | ✅ 완료 | [#28](https://github.com/rnqhstmd/wherewego/pull/28) |
| **Phase 2.11 PR-B** | **외부 API 관제 자산**: Google Places Micrometer 메트릭 + 24h Caffeine 캐시(SHA-256(keyword), maximumSize 1000), Gemini onStatus 4xx/5xx 분리(`server_error` outcome), `ThresholdMonitorScheduler` 1h 윈도우(Gemini server_error 10%/Instagram 차단율 50%) + 5분 쿨다운, `InstagramBlockedRateTracker`(synchronized 단일 락 원자 스왑). 관측 코드 장애 격리 NFR-1~6 | ✅ 완료 | [#29](https://github.com/rnqhstmd/wherewego/pull/29) |
| **Phase 7** | **태그 3종 리뉴얼**: PLACE→REEL(발견/연보라 `#C5B4E3`/인스타아이콘)·WISH(설렘/민트 `#A8E6CF`/동그라미) 분리, MEMORY(추억/핑크 `#FFB3C6`/하트) 유지. **V006 단일 합본 Flyway 마이그레이션**(ALTER+UPDATE+ALTER 단일 트랜잭션), 챗봇 기본값 REEL, 웹 등록 UI WISH/MEMORY 2종 선택(REEL 챗봇 전용·핀 편집은 3종 모두 허용), 지도 마커 3종 신설(공통 SVG 모듈 `lib/pin/markers.tsx`), 룰렛 후보 풀 REEL+WISH + MapClient 토글 부분버그(PR #17 잔존) 정합화 | ✅ 완료 | [#38](https://github.com/rnqhstmd/wherewego/pull/38) |
| **Phase 8** | **인앱 알림함**: 그룹원 핀 등록 시 상대방 알림 생성. 릴스 1건 = 알림 1건(N개 핀 묶음). 알림 유형: `CHATBOT_PINS`(릴스 기반, 복수 핀) / `MANUAL_PIN`(웹 직접 등록, 단건). **실시간(SSE)**: `SseEmitter` 기반 서버→클라이언트 푸시, 추가 인프라 없음. **프론트 UX**: ① 벨 아이콘 우상단 빨간 점(미읽음, 알림함 열어 읽을 때까지 유지) ② 새 알림 수신 시 벨 아이콘 옆에 기존 `SpeechBubblePopup` 스타일 말풍선 1회 노출(알림당 딱 1번, 외부 탭 시 자동 닫힘) ③ 벨 클릭 → 알림 목록 패널 → 상세(장소 N개 리스트) → 클릭 시 지도 `flyTo` + 핀 팝업. 백엔드: `notifications` 테이블 + `notification_pins` 조인 + `GET /api/v1/notifications/stream` SSE 엔드포인트 | ⬜ 예정 | — |
| **Phase 9** | **핀 공유 카드**: 지도 말풍선 팝업 ⋮ 메뉴에 "공유하기" 버튼 추가 → Canvas 기반 카드 이미지 클라이언트 생성 → PNG 다운로드/기기 공유. 카드 구성: Mapbox Static API 지도 이미지(흐림 처리 backdrop-filter) 배경 + 메모 + 장소명 + 핀 등록일 + `written by {작성자 닉네임}` + 좌측 하단 "우리가갈지도" 워터마크. S3 불필요, 서버리스 완전 클라이언트 처리 | ⬜ 예정 | — |

도메인별 구현 상태는 각 `context/{도메인}/status.md` 참조.

## 고도화 로드맵

서비스 안정화 이후 별도 PRD 기반으로 진행하는 대규모 확장 작업.

| 고도화 | 범위 | 상태 |
|--------|------|------|
| **고도화 1.0** | **그룹 확장**: Group N인 확장(1인 1활성 제약 해제), 재가입 허용 정책(`uq_group_members_pair` 변경), BotUserMapping 회원 탈퇴 cascade(회원 탈퇴 PRD 선행) | ⬜ 장기 |

## 주요 ADR

- [ADR-0001 Redis/Kafka 도입 검토](../docs/adr/0001-redis-kafka-usage.md) (폐기)
- [ADR-0002 Redis 제거 + Caffeine 전환](../docs/adr/0002-redis-removal-caffeine.md)
