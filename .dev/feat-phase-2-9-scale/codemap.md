## 코드 맵: Phase 2.9 — 규모 대응 (조건부)

> 범위 후보: ① DOM Marker → Mapbox GL symbol layer 마이그레이션 (그룹 핀 500+ 도입 임계치), ② 핀 페이지네이션 (1k+ 도입 임계치).
> 둘 다 "조건부"이므로 PRD 단계에서 실제 도입 여부 + 임계치 확정이 선행되어야 한다.

### 핵심 파일

#### ① GL symbol layer 마이그레이션
- `frontend/src/app/map/_components/MapboxView.tsx` → 현재 DOM Marker 인스턴스 캐시 패턴 (`new mapboxgl.Marker` + `renderPinDotInto`). GL symbol layer로 전환 시 핵심 변경 지점
- `frontend/src/app/map/MapClient.tsx` → 마커 렌더 호출부, `useOptimistic` reducer(`patch|remove`) 마커 캐시 결합. GL 전환 시 캐시/낙관적 반영 패턴 재설계
- `frontend/src/app/map/_components/PinPopup.tsx` → 정보창 좌표 계산 (현재 마커 DOM 위치 기반). GL 전환 시 마커 픽셀 좌표 산출 방식 변경
- `frontend/src/components/ui/SpeechBubblePopup.tsx` → 정보창 본체. 좌표 계산 인터페이스 의존
- `frontend/src/app/map/_lib/clusterer.ts` → supercluster 클러스터링. GL layer + cluster 통합 영향
- `frontend/src/app/map/_components/PinDot.tsx` → PLACE/MEMORY 마커 컴포넌트 (현재 DOM 렌더). GL 전환 시 sprite/icon image로 대체 필요 (DOM 보존 여부 결정 필요)

#### ② 핀 페이지네이션
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinService.java` → `listGroupPins(groupId, tag)` — Pageable 시그니처 확장 필요
- `backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/pin/PinJpaRepository.java` → JPA 메서드 (Pageable 인자 추가)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/pin/PinRepositoryImpl.java` → 리포지토리 구현체. 페이지네이션 쿼리 + total count 분기
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/pin/PinV1Controller.java` → `GET /api/v1/groups/{groupId}/pins?tag=&page=&size=` — 쿼리 파라미터 신규
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/pin/PinV1Dto.java` → `PinListResponse` 페이지 응답(items/page/size/total/hasNext) 변환 필요
- `frontend/src/app/pins/PinListClient.tsx` → 목록 UI. 페이지네이션 컨트롤 + 무한 스크롤 정책 결정 필요
- `frontend/src/lib/api/pin.ts` → API 클라이언트. `listPins(page, size)` 시그니처 확장

### 참조 파일

- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/Pin.java` → 엔티티
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinSummary.java` → 응답 매핑
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinRepository.java` → 포트 인터페이스
- `backend/apps/wherewego-api/src/test/java/com/wherewego/domain/pin/PinServiceIT.java` → 페이지네이션 통합 테스트 추가 위치
- `backend/apps/wherewego-api/src/test/java/com/wherewego/interfaces/api/pin/PinV1ControllerTest.java` → 컨트롤러 쿼리 파라미터 테스트
- `frontend/src/app/map/page.tsx` → map 진입점 (초기 핀 fetch 경로)
- `frontend/src/lib/api/types.ts` → 타입 정의 (PinListResponse 확장)
- `frontend/src/app/map/_components/ClusterBanner.tsx` → localStorage 1회 안내 (대량 핀 시 UX 메시지 재검토 필요)
- `frontend/src/app/map/actions.ts`, `frontend/src/app/pins/actions.ts` → server action (페이지네이션 시 캐시/revalidate 정책 영향)

### 설정

- `backend/apps/wherewego-api/src/main/resources/application.yml` → 페이지 size 기본값/상한 설정
- `frontend/src/lib/pin/constants.ts` → 페이지 size 상수 (신규 export 필요 시)
- `frontend/src/lib/design/tokens.ts` → 클러스터/마커 색상 토큰 (GL transition 시 참조)

### 탐색 추가 항목 (phase-requirements 발견)

- `frontend/src/app/map/MapClient.tsx` → **회귀 위험 1순위**: 룰렛 stale 재조회에서 `PinListResponse.items`를 직접 참조 (`pool = res.items`). 하위 호환 전략(AC-8) 필수 검증 지점
- `frontend/src/app/map/_components/PinPopup.tsx` → `map.project([lng, lat])` 좌표 계산 — GL 마이그레이션 사전 분석(FR-6)의 핵심 변경 지점
- `frontend/src/lib/api/types.ts` → `PinListResponse { items }` — `totalCount`/`hasNext` 선택적 확장 위치

### 탐색 추가 항목 (phase-design 발견)

- `backend/.../interfaces/api/place/PlaceV1Controller.java:28-30` → 컨트롤러 명시 검증 컨벤션(`if (...) throw new CoreException(...)`) 사례 → size 검증 위치 결정 근거
- `backend/.../interfaces/api/chatbot/ChatbotV1Dto.java:15,22,55,61` → `@JsonInclude(JsonInclude.Include.NON_NULL)` + record 조합 검증된 컨벤션 → PinListResponse 구조 결정 근거
- `backend/.../infrastructure/group/GroupMemberJpaRepository.java:17`, `GroupMemberRepositoryImpl.java:20` → `Pageable` + `PageRequest.of(...)` 인프라 단독 사용 패턴
- `backend/.../interfaces/api/ApiResponse.java`, `ApiControllerAdvice.java` → 글로벌 에러 포맷. `CoreException` → ApiResponse 변환 보장 (NFR-3 근거)
- `backend/.../test/.../PinV1ControllerIntegrationTest.java:30-99` → `TestRestTemplate` + `JwtTokenProvider` JWT 쿠키 인증 통합 테스트 setup 패턴 (AC-1~7 테스트 추가 위치)
- `context/map/architecture.md:40-43` → "주제 문서" 테이블 위치. `gl-migration-plan.md` 링크 추가 지점
- `context/map/status.md:25` → "DOM Marker → GL symbol layer 마이그레이션 (500핀 초과 시)" 메모. 사전 분석 문서에서 역참조
- `frontend/src/app/pins/page.tsx:15` → `listPins(group.groupId)` 인자 1개 호출 (FR-7 호환성 검증 지점)
- `frontend/src/app/map/MapClient.tsx:353-358` → 룰렛 stale 재조회 — AC-7 회귀 검증 1순위 호출부
