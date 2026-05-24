# PR Context: Phase 10 — 장소 방문 감지

## 비즈니스 맥락

wherewego는 커플이 함께 가고 싶은 장소를 WISH/REEL 핀으로 저장하는 지도 앱이다. 기존에는 사용자가 핀 근처에 실제로 다녀온 뒤 직접 지도를 열어 태그를 MEMORY로 바꿔야 해 대부분 방치되었고, Phase 11 "우리 기록" 화면이 실제 다녀온 장소를 반영하지 못하는 문제가 있었다.

Phase 10은 GPS 좌표 기반으로 사용자가 WISH/REEL 핀 100m 이내에 30초 이상 머물면 방문 토스트를 자동으로 띄워 MEMORY 전환을 유도한다. 전환 성공 시 짝꿍에게 VISIT_DETECTED 알림이 전달되어 커플이 방문 기억을 자연스럽게 공유할 수 있게 한다.

## 핵심 변경

### 프론트엔드
- **useVisitDetection 훅** (신규): geolocate 콜백마다 평가, 후보 핀 전체 firstEnterAt 누적, GPS 정확도 50m 게이트
- **VisitToast / VisitMemoSheet** (신규): 슬라이드 업 토스트 + 메모 입력 시트. 1차/2차 PATCH 분리 (태그 즉시 변경 → 메모는 선택)
- **MapboxView confetti** (수정): forwardRef + `triggerVisitCelebration(pinId)` imperative API. 마커 element 자식 노드로 하트 ♡ 3개 fan-out + scale bounce 600ms 주입 → 마커 자체에 종속되어 지도 팬/줌해도 좌표 어긋남 없음
- **MapClient 통합**: 동시 1개 패널 정책에 visit-memo 추가, 1차 PATCH 실패 시 인라인 에러 토스트(1.5초 자동 닫힘)
- **NotificationItem / NotificationPinList**: VISIT_DETECTED 분기 추가. 본인이면 "내가 다녀온 장소", 짝꿍이면 "{닉네임}님이 다녀온 장소"

### 백엔드
- **V009 마이그레이션**: CHECK 제약 확장 + `notifications.visit_pin_id` 컬럼 (ON DELETE RESTRICT) + 부분 UNIQUE 인덱스 `WHERE type='VISIT_DETECTED'`로 race-free 중복 차단
- **NotificationVisitWriter** (신규): `@Transactional(REQUIRES_NEW)` + `DataIntegrityViolationException`은 호출자(NotificationService)가 catch → Spring 트랜잭션 모델과 호환
- **NotificationService.createForVisitDetected**: 본인 포함 fan-out (Phase 11 도입 전 과도기). 알림 상세는 `pin.memo` 최신값 join
- **PinUpdateResult record** (신규) + `PinService.updatePin` 시그니처 변경: 태그 전이 정보 컨트롤러 전달
- **PinV1Controller.updatePin**: WISH/REEL → MEMORY 전환 시 BR-3 try-catch로 알림 호출 격리

## 사전 결정 (사용자 Q&A)

`/ttutak:context` 세션에서 9건 확정 (Q1~Q9, 2026-05-23~24):
- 세션 정의: 메모리 Set, MapClient unmount 시 리셋
- 트리거 주기: 매 geolocate 콜백, throttle 없음
- 진입/통과 임계: 30초 머무름
- GPS 정확도 게이트: ≤ 50m
- 차순위 핀: Set 누적 + 다음 콜백부터 자동 평가
- 권한 UX: 조용히 비활성
- PATCH 흐름: 2회 분리, 메모는 선택
- 알림: 무조건 발송, VISIT_DETECTED 신규 유형
- 마커 전환: 하트 confetti 3개 + scale, ~600ms

phase-review 5건 추가 결정:
- AC-VD-14 인라인 에러 토스트 구현 (1.5초 자동 닫힘)
- 본인 알림 발송 유지 + NotificationItem 본인/짝꿍 카피 분기
- createForVisitDetected JavaDoc 멤버십 호출 계약 명시
- V009에 ON DELETE RESTRICT 명시
- PRD FR-VD-27/30 본인 포함 명시

## Audit Summary
- 총 11건 (CRITICAL: 0, HIGH: 3 → 1건 검증 후 INFO 재분류, MEDIUM: 5, Warning: 3)
- [HIGH] createForVisitDetected 멤버십 호출 계약 (JavaDoc 보강으로 해결)
- [HIGH] visit_pin_id FK ON DELETE 정책 미명시 (ON DELETE RESTRICT 추가로 해결)
- [HIGH/INFO] NotificationVisitWriter UnexpectedRollbackException 우려 → IT race-free 케이스 PASS로 미발생 검증
- [Warning] AC-VD-14 인라인 에러 토스트 미구현 → 보강 5건에 포함되어 해결
- [Warning] visit_pin_id와 notification_pins 이중 저장 (의도된 분담, JavaDoc 명시)

## 테스트
- 백엔드 IT: NotificationServiceVisitDetectedIT 신규 6건 (정상 fan-out, race-free UNIQUE, MEMORY→MEMORY 미발송, memo only 미발송, getDetail memo join, soft-delete pin memo null)
- 프론트엔드 단위 테스트: useVisitDetection 7건 + VisitToast 4건 + VisitMemoSheet 4건 = 15건
- 회귀: 백엔드 Phase 10 관련 IT 전체 PASS (NotificationServiceIT 7/7, PinServiceIT 23/23, Controller IT). 13개 기존 IT의 truncate 순서 보강 (notification → pins). 프론트엔드 162/162 PASS

## 인수 검증
product-owner ACCEPT. AC-VD-1~22 22건 모두 충족 (Must 29 + Should 4).

## 후속 작업
- Controller IT 3건 (c/d/g — 동시성 race, Controller WISH→MEMORY, RuntimeException 격리) 별도 이슈
- 알림 실패 운영 가시성 (Prometheus counter)
- 전환율 모니터링 이벤트 트래킹
- Phase 11 "우리 기록" 도입 시 본인 알림 정책 재검토
- PinPopup 칩 경로 MEMORY 전환에서도 confetti 발사 (현재 VISIT_DETECTED 토스트 경로만)
- 차량 정차 오탐 실측 후 튜닝 (콜백 N회 조건 또는 speed 게이트)
