## 코드 맵: Phase 2.8 — 핀 도메인 UX 잔여 완성

> 범위: 핀 등록 시 `instagramUrl` 입력 UI / 핀 장소 정보(`placeName`/좌표) 수정 / 삭제 핀 복원 / map ⋮ 메뉴에 삭제 액션

### 핵심 파일

- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinUpdateCommand.java` → 부분 수정 명령. **현재 memo/tag만 지원** → place 정보(`placeName`/좌표/instagramUrl) 확장 필요
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinService.java` → addPin/updatePin/softDeletePin. 복원(restore) API 신규 + 장소 수정 분기 필요
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/Pin.java` → 엔티티 (placeName/address/latitude/longitude/instagramUrl/deletedAt). 복원 메서드 신규 필요
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/pin/PinV1Controller.java` → CRUD 라우트. POST/PATCH `restore` 또는 PATCH 활용 분기 필요
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/pin/PinV1Dto.java` → 요청/응답 DTO. UpdatePinRequest 확장 + RestorePinRequest(또는 query flag) 신규
- `frontend/src/app/map/MapClient.tsx` → PinPopup 호출부. `onDelete` 핸들러 + useOptimistic 마커 제거 패턴 적용 위치
- `frontend/src/app/map/_components/PinPopup.tsx` → 정보창 ⋮ 메뉴. 현재 "태그/메모" 2뷰 → 탭 외부에 "삭제" 텍스트 버튼 추가 필요
- `frontend/src/app/map/_components/AddPinPickerContent.tsx` → 핀 추가 시 `instagramUrl` 입력 필드 신규 필요
- `frontend/src/app/pins/_components/PinEditDialog.tsx` → 핀 편집 다이얼로그. **현재 memo/tag만** → placeName/좌표/instagramUrl 편집 필드 + 삭제 핀 복원 UI 필요
- `frontend/src/app/pins/PinListClient.tsx` → 목록 화면. 삭제 핀 토글(휴지통 보기) + 복원 액션 진입점

### 참조 파일

- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinCreateCommand.java` → 등록 명령. instagramUrl 필드 유무 확인 (확장 여부 판단)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinRepository.java` → 활성 핀 조회. 삭제 핀 포함 조회(소프트 삭제 복원용) 추가 필요
- `backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/pin/PinRepositoryImpl.java` → 구현체. 복원 쿼리 신규
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinSummary.java` → 응답 매핑. deletedAt 노출 여부 결정 필요
- `frontend/src/app/map/_components/PinPopupMemoEditor.tsx` → 정보창 메모 편집 (인라인 패턴 참조)
- `frontend/src/app/pins/_components/PinDeleteConfirm.tsx` → 삭제 확인 모달 (재사용 또는 패턴 참조)
- `frontend/src/app/pins/_components/PinCard.tsx` → 목록 카드. 삭제 핀일 때 복원 버튼 노출
- `frontend/src/app/map/actions.ts` → server action (updatePinTag/Memo 패턴 참조)
- `frontend/src/app/pins/actions.ts` → server action (수정/삭제 패턴 참조)
- `frontend/src/lib/api/pin.ts` → API 클라이언트. updatePlaceInfo/restorePin/instagramUrl 신규 또는 확장
- `frontend/src/lib/api/types.ts` → 타입. PinSummaryResponse에 deletedAt 노출 여부 결정

### 설정

- `backend/apps/wherewego-api/src/main/resources/application.yml` → 설정
- `frontend/src/lib/pin/constants.ts` → `MEMO_MAX_LENGTH=500` 존재. `PLACE_NAME_MAX_LENGTH=200` + `ADDRESS_MAX_LENGTH=500` 신규 export 필요
- `frontend/src/lib/design/tokens.ts` → `pinNew: "#E05A5A"` (삭제 버튼/모달 danger 컬러)

### 탐색 추가 항목 (설계 단계 발견)

- `backend/.../support/error/ErrorType.java:51` → `PIN_UPDATE_EMPTY` 메시지 "memo 또는 tag" → 일반화 필요
- `backend/.../domain/pin/PinUpdateCommandTest.java` → of() 호출 8건 — B1에서 일괄 시그니처 변경
- `backend/.../domain/pin/PinServiceIT.java` → of() 호출 6건 — B1에서 일괄
- `frontend/src/app/pins/PinListClient.tsx:46-64` → useOptimistic reducer 패턴 (MapClient 일반화 참조)
