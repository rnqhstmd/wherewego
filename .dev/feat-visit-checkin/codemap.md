# 코드 맵: 방문 체크인·추억 전환 정책 v2

## 핵심 파일

### iOS (감지·UI — 정책 v2의 진입점)
- ios/WhereWeGo/Core/Location/VisitDetectionEngine.swift → 온디바이스 방문 감지 순수 엔진(100m·30초·WISH/REEL 후보·정확도 50m 게이트). 정책 v2에서도 그대로 재사용
- ios/WhereWeGo/Features/Map/VisitToastView.swift → 현행 "함께 방문하셨나요?" 토스트("네 다녀왔어요"/"나중에요"). **정책 v2에서 동행 선택 시트로 교체 대상**
- ios/WhereWeGo/Features/Map/VisitMemoSheet.swift → 전환 후 "다녀온 흔적" 메모 시트 — 재사용
- ios/WhereWeGo/Features/Map/MapViewModel.swift → visitToastPin·confirmVisit(현행 즉시 MEMORY PATCH)·shownPinIds 세션 중복 방지·visitInfoMessage 토스트. 정책 v2 분기(혼자=체크인/동행=전환)의 호스트

### 백엔드 (전환·알림·채팅 카드)
- backend/.../domain/pin/PinService.java:262 → updatePin이 WISH/REEL→MEMORY 전환 1회를 시그널로 반환(VISIT_DETECTED 트리거 판정). **전환 API에 동행 배열·멱등·union 합류 지점**
- backend/.../domain/notification/NotificationVisitWriter.java → VISIT_DETECTED fan-out INSERT(REQUIRES_NEW + 부분 UNIQUE race-free). **정책 v2에서 생성 경로 제거 대상(채팅 카드로 대체)**
- backend/.../domain/notification/NotificationService.java → createForVisitDetected fan-out 루프 — 제거 대상
- backend/.../domain/chat/GroupChatService.java → PIN_REPLY 선례(핀 검증·pinSnapshot 배치 합성·푸시). **방문 채팅 카드("다녀갔어요"/"추억 🎉")의 인프라**

## 참조 파일
- backend/.../interfaces/api/pin/PinV1Controller.java → 핀 PATCH 엔드포인트(전환 시그널 소비)
- backend/.../domain/pin/PinUpdateResult.java → 전환 시그널 DTO
- backend/.../domain/notification/NotificationType.java → VISIT_DETECTED enum
- ios/WhereWeGo/Features/Map/PinDetailContent.swift → 핀 말풍선(보기 모드) — 방문자 아바타 스택 추가 지점
- ios/WhereWeGo/Features/Chat/Group/GroupMessageRow.swift → PIN_REPLY 버블(핀 카드+사진 펼침) — 방문 카드 렌더 선례
- ios/WhereWeGo/Features/Chat/ChatMessageModels.swift:17 → 공용 MessageKind enum. ⚠️ case 추가 시 봇/그룹 양쪽 switch 전수 스캔 필수(직전 CI 2연속 실패 교훈)
- backend/.../test/.../NotificationServiceVisitDetectedIT.java → 기존 VISIT_DETECTED IT — 제거/개편 대상

## 설정
- backend/apps/wherewego-api/src/main/resources/db/migration/ → 최신 V022. pin_visits는 V023
- context/pin/visit-checkin-policy.md → 정책 v2 SSOT(확정 규칙·엣지 판정표·구현 스케치)
- context/pin/phase-10-visit-detection.md → 현행(대체 대상) 정책 문서
