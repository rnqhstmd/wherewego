## 코드 맵: Phase 7 — 태그 3종 리뉴얼 (PLACE → REEL/WISH 분리, MEMORY 유지)

### 핵심 파일

**Backend**
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/Pin.java:67-69, 95-125` → `PinTag` 컬럼 + `autoFromInstagram()`/`fromSelection()`(기본값 PLACE → REEL 변경 대상) + `createManual()`(웹 등록, 호출자가 태그 지정)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinTag.java` → enum 정의 (PLACE/MEMORY → REEL/WISH/MEMORY)
- `backend/apps/wherewego-api/src/main/resources/db/migration/V001__init_schema.sql` → `pins.tag` + `chk_pins_tag` CHECK 제약 원본 (V006/V007/V008 마이그레이션 추가 필요)

**Frontend**
- `frontend/src/lib/api/types.ts` → `PinTag` 타입(API 응답/요청 계약)
- `frontend/src/components/ui/PinTag.tsx` → 태그 칩 컴포넌트 (`"place" | "memory"` → `"reel" | "wish" | "memory"`)
- `frontend/src/components/ui/PinDot.tsx` → 지도 마커 (REEL 인스타 아이콘 신규 추가 필요)
- `frontend/src/app/map/_components/MemoTagPanelContent.tsx` → 웹 등록 UI 태그 선택 (REEL 제외, WISH/MEMORY 2종)
- `frontend/src/app/map/_lib/roulette.ts` → 룰렛 후보 풀 로직 (PLACE → REEL+WISH 갱신)
- `frontend/src/app/map/MapClient.tsx:520-576` → `runRoulette` 내부 `tagsAllowed` 구성 + `includeMemoryAtPick` 상태 저장
- `frontend/src/app/map/MapClient.tsx:880-933` → "다시" 클릭 시 `includeMemory` 토글 변경 감지 + 재추첨 분기
- `frontend/src/app/map/_components/MapboxView.tsx:95-120` → `renderPinDotInto` 마커 DOM 분기 (3종 분기 최대 변경 지점)
- `frontend/src/app/pins/PinListClient.tsx:91-103` → 필터/카운트 useMemo (TagFilter props 동기화 지점)

### 참조 파일 (추가)
- `frontend/src/components/ui/SpeechBubblePopup.tsx:141-175` → PinDot 사용처 (PinDotType 확장 자동 동기화)
- `frontend/src/app/map/_components/RouletteSpinContent.tsx:38-51` → 룰렛 스핀 장식용 PinDot
- `frontend/src/components/ui/GlobeBg.tsx:105-107` → 글로벌 화면 도트 (pinPlace 직접 참조, pinWish/pinReel로 치환)
- `frontend/src/app/gate/page.tsx:170-172`, `app/login/LoginClient.tsx:134-136`, `app/onboarding/group-start/GroupStartClient.tsx:83` → 장식용 PinDot
- `frontend/src/app/groups/GroupsClient.tsx:115` → linear-gradient pinPlace 참조
- `frontend/src/app/map/_components/MobileTopNav.tsx:83` → pinMemory 참조 (#F4A8B0→#FFB3C6 영향)
- `backend/.../interfaces/api/pin/PinV1Controller.java:57-60` → `?tag=PLACE` 쿼리 처리 (V006 후 IllegalArgumentException → 400)
- `backend/.../interfaces/api/pin/PinV1ApiSpec.java:14` → Swagger 설명 "PLACE/MEMORY 필터링" 문자열 (Step 9에서 갱신 필요)

### 참조 파일

**Backend**
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinService.java` → 핀 생성/수정 서비스 (태그 변경 API 영향)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/PlaceSelectionHandler.java` → 챗봇 장소 선택 핸들러 (REEL 기본값 적용)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/ChatbotWebhookService.java` → 챗봇 자동 등록 흐름
- `backend/apps/wherewego-api/src/test/java/com/wherewego/domain/pin/PinTest.java`, `PinV1ControllerIntegrationTest.java`, `PinCreateIT.java`, `PlaceSelectionHandler` 관련 테스트 → enum 변경 시 동기 갱신

**Frontend**
- `frontend/src/app/map/_components/PinPopup.tsx`, `MapboxView.tsx`, `RouletteResultContent.tsx` → 태그별 마커/팝업 분기
- `frontend/src/app/pins/_components/TagFilter.tsx`, `PinCard.tsx`, `PinEditDialog.tsx` → 핀 목록/편집 UI 태그 분기
- `frontend/src/lib/design/tokens.ts` (+`globals.css @theme`) → `pinPlace`/`pinMemory` 색상 토큰 (REEL 색상 추가)
- `frontend/src/components/ui/PinTag.test.tsx`, `PinDot.test.tsx`, `frontend/src/app/map/_lib/roulette.test.ts` → 테스트 갱신

### 설정 / 컨텍스트
- `context/tag/README.md` (Phase 7 범위), `status.md` (FR-TAG-7~11), `architecture.md`/`glossary.md` (2종 기준 → 3종으로 동기화 필요)
- `frontend/AGENTS.md` → Next.js 16 breaking changes 주의 (coder 단계에서 `node_modules/next/dist/docs/` 참조 필수)
