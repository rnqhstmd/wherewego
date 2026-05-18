## 코드 맵: Phase 2.10 — 잔여 후속 통합

> 범위: ① pin 좌표 수정 + 삭제 핀 복원 ② chatbot PLACE_SELECTION 검증 ③ map Pretendard self-host + Mapbox 토큰 SOP

### 핵심 파일

**① pin 좌표 수정 (백엔드)** — 복원 기능은 사용자 결정으로 제거
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/Pin.java → 핀 엔티티 (BaseEntity 상속, soft delete, latitude/longitude BigDecimal(10,7))
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinService.java → updatePin 좌표 분기 추가 지점 (복원 메서드 X)
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinUpdateCommand.java → 현재 8필드, 좌표 2필드 + Provided 확장 (총 10필드)
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/pin/PinV1Controller.java → PATCH 좌표 필드 확장 (restore 엔드포인트 X)
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/pin/PinV1Dto.java → 요청 DTO 좌표 필드 확장

**① pin 좌표 수정 (프론트엔드)** — 진입점은 지도 PinPopup ⋮ 메뉴
- frontend/src/app/map/_components/PinPopup.tsx → ⋮ 메뉴 '좌표 수정' 항목 추가 (핵심 진입점)
- frontend/src/app/map/_components/MapboxView.tsx → 지도 picker(십자선 UI) 좌표 수정 모드 재사용
- frontend/src/app/map/MapClient.tsx → useOptimistic patch 리듀서 좌표 반영 확장
- frontend/src/lib/api/pin.ts → updatePin API에 좌표 인자 추가
- frontend/src/lib/api/types.ts → PinUpdate 요청 타입 좌표 필드 추가

**② chatbot PLACE_SELECTION**
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/PlaceSelectionHandler.java → `extractPlaceId` clientExtra 우선·params 폴백 (이미 구현됨, 외부 빌더 동작 검증이 본 작업)
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/chatbot/ChatbotV1Dto.java → Action.clientExtra/params 매핑
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/PlaceCardBuilder.java → 카카오 빌더로 보내는 카드 페이로드 (button extra 구조 검증 기준)

**③ map Pretendard + Mapbox SOP**
- frontend/src/app/globals.css:51 → **HIGH** body font-family Arial 하드코딩 (`var(--font-sans)` 미적용) — 본 Phase 핵심 수정 지점
- frontend/public/fonts/README.md → 이미 self-host 완료 기재됨 (사실 정합)
- frontend/public/fonts/PretendardVariable.woff2 → 폰트 바이너리 (존재 확인됨)
- frontend/src/app/layout.tsx → next/font/local Pretendard + next/font/google Noto/Gowun/JetBrains/Geist (CDN 의존 — 별도 Phase로 분리됨)
- frontend/src/lib/design/tokens.ts → fonts.sans = "var(--font-sans)" 토큰 참조

### 참조 파일

- backend/apps/wherewego-api/src/main/java/com/wherewego/support/error/ErrorType.java → 새 에러 코드 `PIN_COORDINATE_INVALID` 추가
- frontend/src/lib/pin/constants.ts → 좌표/메모/길이 상수
- context/pin/status.md → FR-PIN 추적 (좌표/복원 후 갱신 대상)
- context/chatbot/status.md → PLACE_SELECTION cross-link 갱신 대상
- context/map/status.md → Pretendard/Mapbox SOP 항목 갱신 대상

### 설정

- backend/apps/wherewego-api/build.gradle.kts → 백엔드 의존성
- backend/supports/jackson/.../JacksonConfig.java → `WRITE_BIGDECIMAL_AS_PLAIN` 활성 (BigDecimal → JSON number plain 직렬화 보장)
- frontend/AGENTS.md → Next.js 변경 주의 (구현 단계에서 node_modules/next/dist/docs/ 참조 필요)
- .claude/config.json → 프로젝트 컨벤션 (branchTypes, commitFormat, contextLimits)

### critic 추가 발견

- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/pin/PinV1Dto.java:75-140 → CreatePinRequest BigDecimal 직접 매핑 (UpdatePinRequest 비대칭 해소 참고)
- frontend/src/app/map/_components/MapboxView.tsx:372-377 → pins 변경 시 supercluster 통째 재생성 (큰 좌표 이동 시 깜빡임 가능)
- frontend/src/app/map/_components/PinPopup.tsx:74-126 → screenPos null 한 프레임 깜빡임 가능 (좌표 변경 시 useEffect 재실행)
- frontend/src/app/map/_components/AddPinPickerContent.tsx → picker 본체 (PinCoordinateEditPicker 작성 시 좌표 추적 패턴 참조, mode prop 분기 안 함)
