## AC 충족 매트릭스

| AC | 판정 | 근거 파일:라인 |
|---|---|---|
| AC-1 | O | PRD: `.dev/feat-phase-2-10/prd.md:123`; 백엔드 좌표 갱신 IT: `PinV1ControllerIntegrationTest.java:435`; 프론트 즉시 이동: `MapClient.tsx:394`, `MapboxView.tsx:233` |
| AC-2 | O | PRD: `.dev/feat-phase-2-10/prd.md:125`; 범위 검증: `PinUpdateCommand.java:62`; 에러 코드: `ErrorType.java:58`; IT: `PinV1ControllerIntegrationTest.java:461` |
| AC-3 | O | PRD: `.dev/feat-phase-2-10/prd.md:127`; 활성 멤버십 검증: `PinService.java:115`; IT: `PinV1ControllerIntegrationTest.java:478` |
| AC-4 | O | PRD: `.dev/feat-phase-2-10/prd.md:129`; 메뉴 진입: `PinPopup.tsx:288`; picker/십자선: `MapClient.tsx:963`, `MapClient.tsx:1002`; optimistic patch/롤백 경로: `MapClient.tsx:394`, `MapClient.tsx:422`; 에러 자동 펼침: `PinPopup.tsx:104` |
| AC-5 | O | PRD: `.dev/feat-phase-2-10/prd.md:131`; 좌표만 변경: `Pin.java:213`, `PinService.java:135`; 불변 검증 IT: `PinV1ControllerIntegrationTest.java:454` |
| AC-6 | O | PRD: `.dev/feat-phase-2-10/prd.md:133`; 좌표 미제공 처리: `PinV1Dto.java:201`; IT: `PinV1ControllerIntegrationTest.java:495` |
| AC-7 | 부분 | PRD: `.dev/feat-phase-2-10/prd.md:137`; status에는 완료 기재: `context/chatbot/status.md:28`; 실제 콘솔 증적은 repo에서 확인 불가이며 trust-ledger 기보고 |
| AC-8 | 부분 | PRD: `.dev/feat-phase-2-10/prd.md:139`; 정상 등록 IT: `ChatbotV1ControllerIntegrationTest.java:442`; status에는 실기기/PR 본문 기록 완료로 기재: `context/chatbot/status.md:28`; 실제 PR 본문 증적은 repo에서 확인 불가이며 trust-ledger 기보고 |
| AC-9 | O | PRD: `.dev/feat-phase-2-10/prd.md:141`; PLACE_SELECTION 5케이스: `ChatbotV1ControllerIntegrationTest.java:442`, `:459`, `:476`, `:491`, `:508`; self-check test exit 0: `.dev/feat-phase-2-10/self-check.md:4` |
| AC-10 | O | PRD: `.dev/feat-phase-2-10/prd.md:145`; status 갱신: `context/map/status.md:23` |
| AC-11 | O | PRD: `.dev/feat-phase-2-10/prd.md:147`; SOP 절차: `context/map/mapbox-token-sop.md:14`; cross-link: `context/map/status.md:24` |
| AC-12 | O | PRD: `.dev/feat-phase-2-10/prd.md:149`; body 폰트: `globals.css:48`; `--font-sans` 주입: `layout.tsx:7`, `layout.tsx:54`; 폰트 파일: `frontend/public/fonts/README.md:7` |
| AC-13 | O | PRD: `.dev/feat-phase-2-10/prd.md:153`; Phase 2.8 주요 PATCH/DELETE 회귀 IT: `PinV1ControllerIntegrationTest.java:239`, `:329`, `:550`; `/pins` 편집은 좌표 없음: `PinEditDialog.tsx:12` |
| AC-14 | O | PRD: `.dev/feat-phase-2-10/prd.md:155`; page/size 분기: `PinV1Controller.java:66`; `totalCount/hasNext`: `PinV1Dto.java:51`; IT: `PinV1ControllerIntegrationTest.java:663`, `:689` |
| AC-15 | O | PRD: `.dev/feat-phase-2-10/prd.md:157`; reducer: `MapClient.tsx:143`; supercluster 재생성: `MapboxView.tsx:371`; 말풍선 `map.project`: `PinPopup.tsx:123` |

## 설계 범위 이탈

범위 기준: 설계서 §1은 “신규 파일: 2개”(`design.md:10`), “수정 파일: 13개”(`design.md:14`), “테스트: 백엔드 2”(`design.md:39`)를 명시한다.

| 파일 경로 | 변경 요약 | 이탈 사유 추정 |
|---|---|---|
| `.claude/settings.local.json` | Claude 로컬 권한 설정 신규 추가 | 개발자 로컬 테스트 설정 유입 |
| `.gitignore` | `.dev/` ignore 제거 | ttutak 산출물 추적 정책 변경 |
| `backend/.../config/env/PlaceProperties.java` | Gemini base-url 속성 추가 | Gemini HTTP 테스트/환경설정 후속 |
| `backend/.../domain/pin/PinListResult.java` | 페이지네이션 결과 record 신규 | Phase 2.9 페이지네이션 누적 |
| `backend/.../domain/pin/PinRepository.java` | paged/count 포트 추가 | Phase 2.9 페이지네이션 누적 |
| `backend/.../infrastructure/gemini/GeminiPlaceClient.java` | base-url 설정 사용 | Gemini 테스트 가능화 후속 |
| `backend/.../infrastructure/pin/PinJpaRepository.java` | Pageable 조회/count 추가 | Phase 2.9 페이지네이션 누적 |
| `backend/.../infrastructure/pin/PinRepositoryImpl.java` | 페이지 조회와 tie-breaker sort 추가 | Phase 2.9 페이지네이션 누적 |
| `backend/.../interfaces/api/pin/PinV1Controller.java` | page/size 목록 분기 추가 | Phase 2.9 페이지네이션 누적 |
| `backend/.../support/error/ErrorType.java` | 좌표/페이지 에러 코드 추가 | Phase 2.8~2.10 누적 에러 정합화 |
| `backend/.../resources/application.yml` | `GEMINI_BASE_URL` 추가 | Gemini HTTP 테스트/환경설정 후속 |
| `backend/.../test/domain/group/GroupMemberServiceIT.java` | 그룹 멤버 서비스 회귀 테스트 보강 | Phase 2.6/2.9 누적 회귀 |
| `backend/.../test/domain/pin/PinServiceIT.java` | 핀 서비스 테스트 보강 | Phase 2.8/2.9 누적 회귀 |
| `backend/.../test/infrastructure/gemini/GeminiPlaceClientHttpTest.java` | Gemini HTTP 테스트 신규 | Gemini 도메인 후속 |
| `backend/.../test/interfaces/api/chatbot/ChatbotV1ControllerIntegrationTest.java` | PLACE_SELECTION IT 보강 | Phase 2.7 회귀 증적 |
| `backend/gradle.properties` | Gradle JDK auto-download 추가 | 로컬/CI 빌드 편의 후속 |
| `context/README.md` | 문서 색인 갱신 | context 문서 정합화 확대 |
| `context/group/status.md` | 상태 문서 갱신 | 관련 도메인 상태 동기화 |
| `context/map/architecture.md` | map 아키텍처 문서 갱신 | Phase 2.8/2.9 문서 누적 |
| `context/map/gl-migration-plan.md` | GL 전환 계획 신규 | Phase 2.9 사전 분석 누적 |
| `context/map/mapbox-env.md` | Mapbox 환경변수 가이드 신규 | SOP 보강이지만 §1 신규 2개 외 추가 |
| `context/memo/status.md` | memo 상태 문서 갱신 | 관련 도메인 상태 동기화 |
| `context/pin/architecture.md` | pin 아키텍처 문서 갱신 | Phase 2.8/2.9 누적 |
| `context/pin/glossary.md` | 용어 문서 갱신 | 문서 정합화 확대 |
| `context/place/status.md` | place 상태 문서 갱신 | 관련 도메인 상태 동기화 |
| `context/recommendation/status.md` | 추천 상태 문서 갱신 | 관련 도메인 상태 동기화 |
| `context/tag/status.md` | tag 상태 문서 갱신 | 관련 도메인 상태 동기화 |
| `frontend/src/app/map/_components/MemoTagPanelContent.tsx` | 등록 패널 검증/상수 적용 보강 | Phase 2.8 웹 등록 후속 |
| `frontend/src/app/map/_components/PinPopupMemoEditor.test.tsx` | 메모 편집 테스트 신규 | Phase 2.6/2.8 회귀 보강 |
| `frontend/src/app/map/_components/PinPopupMemoEditor.tsx` | 메모 편집 소폭 수정 | Phase 2.6/2.8 후속 |
| `frontend/src/app/map/_components/RouletteResultContent.test.tsx` | 룰렛 결과 테스트 신규 | 추천/룰렛 회귀 보강 |
| `frontend/src/app/pins/PinListClient.tsx` | 핀 목록 상태 갱신 보강 | Phase 2.8 `/pins` 후속 |
| `frontend/src/app/pins/_components/PinCard.tsx` | 카드 렌더링 보강 | Phase 2.8 보안/표시 후속 |
| `frontend/src/app/pins/_components/PinEditDialog.tsx` | 장소명/주소 편집 확장 | Phase 2.8 누적 |
| `frontend/src/components/ui/PinDot.test.tsx` | 디자인 컴포넌트 테스트 신규 | Phase 2.7 회귀 보강 |
| `frontend/src/components/ui/PinTag.test.tsx` | 디자인 컴포넌트 테스트 신규 | Phase 2.7 회귀 보강 |
| `frontend/src/components/ui/SpeechBubblePopup.test.tsx` | 디자인 컴포넌트 테스트 신규 | Phase 2.7 회귀 보강 |
| `frontend/src/lib/api/types.ts` | `PinListResponse` metadata 추가 | Phase 2.9 페이지네이션 누적 |
| `frontend/src/lib/pin/constants.ts` | 핀 입력 상수 추가 | Phase 2.8 UI 검증 후속 |

## 신규 위험

- [Warning] [ASSUMPTION] PRD의 좌표 “소수점 7자리 이하” 서버 검증 약속과 설계/코드가 불일치한다.
  - 위치: `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinUpdateCommand.java:62`
  - 근거: PRD는 “좌표 유효성(위도 -90~90, 경도 -180~180, 소수점 7자리 이하)을 서버에서 검증”한다고 한다(`prd.md:48`). 반면 설계서는 “소수점 7자리 제약 ... 명시 검증은 ... 생략”이라고 해석한다(`design.md:83`). 실제 코드는 null/range만 검증하고 scale 검증은 없다(`PinUpdateCommand.java:62-71`).
  - 권고: 서버에서 `latitude.stripTrailingZeros().scale() <= 7` 및 longitude 동일 검증을 추가하거나, PRD/AC를 DB scale 위임 정책으로 명시 수정한다.

- [Info] [POLICY] 설계 범위 밖의 로컬 Claude 권한 파일이 추적 대상에 들어왔다.
  - 위치: `.claude/settings.local.json:3`
  - 근거: 설계서 §1은 “신규 파일: 2개”로 `PinCoordinateEditPicker.tsx`, `mapbox-token-sop.md`만 든다(`design.md:10-12`). 실제 신규 파일은 Windows 로컬 JDK 경로가 포함된 `.claude/settings.local.json`이다(`.claude/settings.local.json:4`).
  - 권고: 의도된 팀 설정이면 설계 범위와 저장소 정책에 반영하고, 개인 로컬 설정이면 PR에서 제외하거나 ignore 정책을 추가한다.

## references 위반

위반 없음

## 총평

- 강점: 좌표 수정 핵심 흐름은 백엔드 Provided 패턴, 권한 검증, `useOptimistic` 즉시 반영/롤백까지 PRD의 주요 AC와 잘 맞는다.
- 강점: Mapbox SOP는 토큰 발급, URL Restriction, 환경변수, 배포, 롤백 흐름을 포함해 NFR-4를 코드 맵 수준에서 충족한다.
- 신규 위험 합산: Critical 0 / Warning 1 / Info 1.
- 머지 전 권고: 좌표 scale 검증 정책을 코드로 보강할지 PRD를 수정할지 먼저 결정하고, 설계 범위 밖 39개 파일은 PR 본문에서 별도 배경을 설명해야 한다.
