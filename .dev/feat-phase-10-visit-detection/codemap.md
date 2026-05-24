## 코드 맵: Phase 10 — 장소 방문 감지

### 핵심 파일
- frontend/src/app/map/MapClient.tsx → Phase 10 통합 지점 (신규 useVisitDetection 훅 연결, VisitToast/VisitMemoSheet 조건부 렌더, marker 전환 트리거)
- frontend/src/app/map/_components/MapboxView.tsx:435-462 → GeolocateControl + geo.on("geolocate") 위치 콜백, 자체 user-location-marker. confetti 애니메이션 트리거 진입점
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationType.java → enum에 VISIT_DETECTED 신규 추가. DB CHECK 제약 동시 확장 필요
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationService.java → createForVisitDetected(groupId, userId, pinId) 신규 메서드
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/Pin.java → applyTag / applyManualMemo (Phase 10에서 재사용)

### 참조 파일
- frontend/src/app/map/_hooks/useGeolocation.ts → 기존 watchPosition 사용 패턴 (정확도/타임아웃 정책 참조)
- frontend/src/app/map/_components/PinPopup.tsx → 핀 팝업 UX 컨벤션
- frontend/src/app/map/_components/PinCoordinateEditPicker.tsx → 시트 컴포넌트 컨벤션 (VisitMemoSheet 참조)
- frontend/src/app/map/actions.ts → pin PATCH 서버 액션 (태그/메모 변경 진입점)
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/pin/PinV1Controller.java → PATCH /pins/{id} + Phase 8 MANUAL_PIN 알림 호출 try-catch 패턴
- frontend/src/app/map/_components/notifications/NotificationItem.tsx → 알림 항목 표시 (VISIT_DETECTED 분기 추가)
- frontend/src/app/map/_components/notifications/NotificationPanel.tsx → 알림 상세 표시 (장소명/주소/메모 최신값 join)

### 설정
- backend/apps/wherewego-api/src/main/resources/db/migration/ → V009__add_visit_detected_notification_type.sql 신규 (CHECK 제약 확장)
- context/pin/phase-10-visit-detection.md → PRD 기반 문서
- context/notification/architecture.md → 알림 도메인 데이터 모델 / fetch 트리거 정책
